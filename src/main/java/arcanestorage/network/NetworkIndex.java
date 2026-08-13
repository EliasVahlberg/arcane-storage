package arcanestorage.network;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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
    * <p>Twenty is one second, which is exactly how often a bus used to recount from scratch, so a decision
    * made from a reused build is never staler than one made by the old code. It is also a backstop rather
    * than the mechanism: {@link NetworkIndexes#topologyChanged()} invalidates immediately when the layout
    * changes, and this bounds how long anything missed can persist.
    */
   public static final int FRESH_FOR_TICKS = 20;

   /**
    * How many full rebuilds have happened, for diagnosis rather than behaviour.
    *
    * <p>"The index is shared" is not a testable claim; "two buses on one network caused one rebuild, not two"
    * is. Static because a test process runs one server, and reset by the harness between scenarios.
    */
   public static long rebuilds;

   private final Map<Item, Integer> counts = new HashMap<>();

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

      for (NetworkStorage unit : units) {
         Inventory inventory = unit.getInventory();
         if (inventory == null) {
            continue;
         }

         this.memberInventories.add(inventory);

         for (int slot = 0; slot < inventory.getSize(); slot++) {
            InventoryItem item = inventory.getItem(slot);
            if (item != null) {
               this.counts.merge(item.item, item.getAmount(), Integer::sum);
               this.total += item.getAmount();
            }
         }
      }
   }

   /** Whether a build may still be trusted, on both counts it can go stale for. */
   boolean isFresh(long tick, long topologyVersion) {
      return this.topologyVersion == topologyVersion && tick - this.builtTick < FRESH_FOR_TICKS;
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

         for (int slot = 0; slot < inventory.getSize(); slot++) {
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
