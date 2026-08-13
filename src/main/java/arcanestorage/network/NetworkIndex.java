package arcanestorage.network;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import java.util.IdentityHashMap;

import necesse.engine.GameLog;
import necesse.entity.objectEntity.ObjectEntity;
import necesse.inventory.Inventory;
import necesse.inventory.InventoryItem;
import necesse.inventory.item.Item;
import necesse.inventory.itemFilter.ItemCategoriesFilter;

/**
 * What one network holds, as one shared copy.
 *
 * <p><b>Why this exists.</b> Every device used to count for itself: a bus asked "how many stone does the
 * network hold" once per slot it looked at, and each of those questions walked every unit's every slot. With
 * two buses and one full unit that measured 200 slot scans and 5 network walks per hundred idle ticks, and it
 * grew with the product of devices and slots rather than with either. The counting is not the fault -- asking
 * the same question repeatedly is. So the answer is computed once per network and shared, which is also what
 * the transfer resolver needs: a single canonical copy of derived state that a changeset can be planned
 * against.
 *
 * <p>Vanilla keeps such an index too, in {@code SettlementStorageIndex} and its four registered kinds, which
 * is the precedent for the shape rather than an implementation to reuse -- those are keyed for settler queries
 * and populated from settlement data.
 *
 * <p><b>It is a cache of state we do not own.</b> A chest belongs to the world, and anything may put items in
 * it. This class therefore carries the tick it was built on and the topology version it was built under, and
 * refuses to be trusted beyond either. Step 3 of the resolver plan replaces the rebuild with incremental
 * maintenance driven by a change hook; until then the freshness window is what the buses already had, one
 * second, so nothing observable changes.
 *
 * <p>Counts are keyed by {@link Item} identity, not by string ID. Registry entries are singletons, so
 * identity is the same comparison the string was standing in for, without the hashing.
 */
public final class NetworkIndex {

   /**
    * How long a build may be reused, in ticks.
    *
    * <p>Thirty seconds, and it is a backstop rather than the mechanism. Two things could make a build wrong and
    * neither waits for this: the layout changing bumps {@link NetworkIndexes#topologyChanged()} and every
    * build under the old version is refused immediately, and the contents changing is reported by the
    * {@code updateSlot} hook as it happens. What is left for a timer is the case nobody told us about --
    * membership altered by a path that does not run the destroy hook -- and ten seconds bounds how long such
    * a thing can persist.
    *
    * <p>It was one second before the hook existed, because a bus recounted from scratch that often and a
    * shared copy must not be staler than the code it replaced. With the hook, holding a build for longer costs
    * nothing in accuracy and removes the last reason an idle network does any work at all.
    */
   public static final int FRESH_FOR_TICKS = 600;

   /**
    * How many full rebuilds have happened, for diagnosis rather than behaviour.
    *
    * <p>"The index is shared" is not a testable claim; "two buses on one network caused one rebuild, not two"
    * is. Static because a test process runs one server, and reset by the harness between scenarios.
    */
   public static long rebuilds;

   private final Map<Item, Integer> counts = new HashMap<>();

   /**
    * What the index last saw in every member slot.
    *
    * <p>Needed because {@code updateSlot} says which slot changed and not what it held before, and a count
    * cannot be corrected without the difference. Two flat arrays per member rather than objects per slot: a
    * network of sixty-four units is 2560 slots, and this is touched on every inventory change in the game.
    */
   private final IdentityHashMap<Inventory, Item[]> shadowItems = new IdentityHashMap<>();

   private final IdentityHashMap<Inventory, int[]> shadowAmounts = new IdentityHashMap<>();

   /** When the counts were last checked against the world, for the periodic reconciliation. */
   private long lastReconciled;

   /** How many times a check found the counts wrong. Diagnostic; should stay at zero. */
   public static long resyncs;

   /**
    * Slots read by drift checks.
    *
    * <p>Counted separately from the transfer loop's scans so that "an idle network does no work" cannot be
    * claimed while a safety net quietly scans the whole network behind it. The check is real work and should
    * appear as such.
    */
   public static long driftScans;

   private final List<Inventory> memberInventories = new ArrayList<>();

   private List<NetworkStorage> units;

   private List<ObjectEntity> devices;

   private long builtTick;

   private long topologyVersion;

   private int total;

   /**
    * Bumped on every change to the counts.
    *
    * <p>Step 4's queued actions carry the version they were planned under and are revalidated against it at
    * drain, which is the guard that replaces idempotence: a delta computed against one state must not be
    * applied blindly to another.
    */
   private long version;

   NetworkIndex(List<NetworkStorage> units, List<ObjectEntity> devices, long tick, long topologyVersion) {
      this.rebuild(units, devices, tick, topologyVersion);
   }

   /** Recounts from the units' slots. The only place the whole network is scanned. */
   void rebuild(List<NetworkStorage> units, List<ObjectEntity> devices, long tick, long topologyVersion) {
      this.units = units;
      this.devices = devices;
      this.builtTick = tick;
      this.topologyVersion = topologyVersion;
      this.counts.clear();
      this.memberInventories.clear();
      this.total = 0;
      this.version++;
      rebuilds++;

      IndexedInventories.release(this);
      this.shadowItems.clear();
      this.shadowAmounts.clear();

      for (NetworkStorage unit : units) {
         Inventory inventory = unit.getInventory();
         if (inventory == null) {
            continue;
         }

         this.memberInventories.add(inventory);

         int size = inventory.getSize();
         Item[] items = new Item[size];
         int[] amounts = new int[size];

         for (int slot = 0; slot < size; slot++) {
            InventoryItem item = inventory.getItem(slot);
            if (item != null) {
               this.counts.merge(item.item, item.getAmount(), Integer::sum);
               this.total += item.getAmount();
               items[slot] = item.item;
               amounts[slot] = item.getAmount();
            }
         }

         this.shadowItems.put(inventory, items);
         this.shadowAmounts.put(inventory, amounts);
      }

      this.lastReconciled = tick;
      IndexedInventories.claim(this);
   }

   /**
    * Applies one slot's change, from the hook.
    *
    * <p>Runs on whatever thread mutated the inventory, which for a member of a network is always the server
    * thread: a client's copies of a chest are different objects and are never members. On the server there is
    * no concurrency to guard against, which is the whole reason the rest of this design insists on staying
    * there.
    *
    * <p>Cheap on purpose. The common case is a stack whose amount moved: one array read, one map update.
    */
   void slotChanged(Inventory inventory, int slot) {
      Item[] items = this.shadowItems.get(inventory);
      int[] amounts = this.shadowAmounts.get(inventory);
      if (items == null || slot < 0 || slot >= items.length) {
         return;
      }

      Item wasItem = items[slot];
      int wasAmount = amounts[slot];
      Item nowItem = IndexedInventories.itemIn(inventory, slot);
      int nowAmount = IndexedInventories.amountIn(inventory, slot);

      if (wasItem == nowItem && wasAmount == nowAmount) {
         return;
      }

      if (wasItem == nowItem) {
         this.changed(nowItem, nowAmount - wasAmount);
      } else {
         // A different kind now occupies the slot, so both counts move. Written as two changes rather than
         // one so that the item that left is removed even when nothing replaced it.
         if (wasItem != null) {
            this.changed(wasItem, -wasAmount);
         }

         if (nowItem != null) {
            this.changed(nowItem, nowAmount);
         }
      }

      items[slot] = nowItem;
      amounts[slot] = nowAmount;
   }

   /**
    * Checks the counts against the world every so often, and rebuilds if they disagree.
    *
    * <p>This is not defensive programming for its own sake. The index is a cache of state the mod does not
    * own, and Necesse has hit this exact bug class in its own code -- the version history records fixing
    * crafting lists that did not update when a nearby inventory changed. The check is cheap relative to its
    * interval and it is the difference between a wrong number being corrected and a wrong number persisting
    * for as long as the world is loaded.
    *
    * @return whether a resync was needed
    */
   boolean reconcile(long tick) {
      if (!this.reconcileRequested && tick - this.lastReconciled < RECONCILE_EVERY_TICKS) {
         return false;
      }

      this.reconcileRequested = false;
      this.lastReconciled = tick;
      int drift = this.driftAgainstWorld();
      if (drift == 0) {
         return false;
      }

      resyncs++;
      GameLog.warn.println("Arcane Storage: network index drifted by " + drift
         + " items and was rebuilt. If this repeats, the change hook is missing a path.");
      this.rebuild(this.units, this.devices, tick, this.topologyVersion);
      return true;
   }

   /**
    * How often the counts are checked against the world, in ticks.
    *
    * <p>Ten seconds, and deliberately shorter than {@link #FRESH_FOR_TICKS}: if the two were equal, a build
    * would expire and be rebuilt at the same moment the check was due, so drift would be cleared by the
    * rebuild and the check would never be seen to work. A safety net that cannot be observed working is not
    * one, which is also why the harness can poison an index on purpose.
    */
   public static final int RECONCILE_EVERY_TICKS = 200;

   /**
    * Forces the next use to check, used when the terminal opens.
    *
    * <p>A flag rather than a date far in the past. Setting {@code lastReconciled} to {@code Long.MIN_VALUE}
    * looks equivalent and is not: the interval test subtracts it from the current tick, which overflows to a
    * negative number, so the check it was meant to force never ran at all. Found by the test that induces
    * drift and then opens the terminal.
    */
   public void reconcileSoon() {
      this.reconcileRequested = true;
   }

   private boolean reconcileRequested;

   /** Whether a build may still be trusted, on both counts it can go stale for. */
   boolean isFresh(long tick, long topologyVersion) {
      if (this.topologyVersion != topologyVersion) {
         return false;
      }

      // Without the hook there is nothing watching the contents, so a build is only good for as long as the
      // old per-second recount was. Degrading rather than failing keeps the mod usable after a game update
      // breaks the patch, and the warning at load says which mode it is in.
      long window = IndexedInventories.hookWorks() ? FRESH_FOR_TICKS : 20;
      return tick - this.builtTick < window;
   }

   /** Whether a device is on the network this index describes. Identity, over a handful of entries. */
   public boolean holds(ObjectEntity device) {
      for (ObjectEntity member : this.devices) {
         if (member == device) {
            return true;
         }
      }

      return false;
   }

   /** How many of one item the network holds. */
   public int of(Item item) {
      Integer count = this.counts.get(item);
      return count == null ? 0 : count;
   }

   /** Everything the network holds, of every item. */
   public int total() {
      return this.total;
   }

   /**
    * What the network holds of a category's items, excluding one item.
    *
    * <p>The exclusion is what turns a category's limit into a ceiling for the item being moved: the rest of
    * the category is occupied space, and what is left is this item's room.
    *
    * <p>Iterates the index's distinct items rather than every slot, so its cost is the number of item kinds
    * the network holds and not the number of slots it has.
    */
   public int inCategoryExcept(ItemCategoriesFilter.ItemCategoryFilter category, Item except) {
      int sum = 0;

      for (Map.Entry<Item, Integer> entry : this.counts.entrySet()) {
         Item item = entry.getKey();
         if (item != except && category.category.containsItemOrInChildren(item)) {
            sum += entry.getValue();
         }
      }

      return sum;
   }

   /**
    * Applies a known change without rebuilding.
    *
    * <p>Used by our own transfers now and by the foreign-change hook in step 3. Kept here rather than in the
    * caller so that {@link #version} cannot be bumped without the counts moving with it.
    */
   public void changed(Item item, int delta) {
      if (delta == 0) {
         return;
      }

      int next = this.of(item) + delta;
      if (next <= 0) {
         this.counts.remove(item);
      } else {
         this.counts.put(item, next);
      }

      this.total = Math.max(0, this.total + delta);
      this.version++;
   }

   /**
    * How far the index has drifted from what the units actually hold, as a total absolute difference.
    *
    * <p>Zero is agreement. This is the honest way to talk about a cache of state we do not own: rather than
    * asserting that nothing can change behind us, measure whether anything has. Step 3's reconciliation uses
    * it as a periodic check, and the tests use it to prove the incremental path keeps up.
    *
    * <p>Costs a full scan, so it is a check and not a mechanism.
    */
   public int driftAgainstWorld() {
      Map<Item, Integer> truth = new HashMap<>();

      for (NetworkStorage unit : this.units) {
         Inventory inventory = unit.getInventory();
         if (inventory == null) {
            continue;
         }

         int size = inventory.getSize();
         driftScans += size;

         for (int slot = 0; slot < size; slot++) {
            InventoryItem item = inventory.getItem(slot);
            if (item != null) {
               truth.merge(item.item, item.getAmount(), Integer::sum);
            }
         }
      }

      int drift = 0;
      for (Map.Entry<Item, Integer> entry : truth.entrySet()) {
         drift += Math.abs(entry.getValue() - this.of(entry.getKey()));
      }

      for (Map.Entry<Item, Integer> entry : this.counts.entrySet()) {
         if (!truth.containsKey(entry.getKey())) {
            drift += Math.abs(entry.getValue());
         }
      }

      return drift;
   }

   /** The units, in discovery order. */
   public List<NetworkStorage> units() {
      return this.units;
   }

   /** The units' inventories, as transfer targets. */
   public List<Inventory> memberInventories() {
      return this.memberInventories;
   }

   /** The devices standing on the network: buses, terminals, anything that took part in the walk. */
   public List<ObjectEntity> devices() {
      return this.devices;
   }

   /** The state the counts are in. Compared, never interpreted. */
   public long version() {
      return this.version;
   }

   /** How many distinct kinds the network holds. For diagnosis. */
   public int kinds() {
      return this.counts.size();
   }
}
