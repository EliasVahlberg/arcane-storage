package arcanestorage.network;

import java.util.IdentityHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import necesse.engine.GameLog;
import necesse.inventory.Inventory;
import necesse.inventory.InventoryItem;
import necesse.inventory.item.Item;

/**
 * Which network each indexed inventory belongs to, so a slot change can be applied without scanning.
 *
 * <p><b>Why a registry and not a listener.</b> {@code Inventory} keeps a list of slot-update listeners and
 * notifies exactly one of them -- read the source: the loop over {@code slotUpdateListeners} is an {@code if},
 * not a {@code while}. Attaching to a container we do not own would therefore either be ignored, or silently
 * take the notification the game's own form was relying on. That is a bug we would be shipping into other
 * people's UI, so the only honest hook is the method every mutation inside {@code Inventory} funnels through,
 * {@code updateSlot(int)}, patched at exit where the new contents are already in place. Fifteen call sites
 * inside that class route through it, and it is where both the notify and {@code markDirty} already live.
 *
 * <p><b>The cost of being on that path.</b> The hook runs for every inventory in the game -- every chest, every
 * player's backpack, every settler's bag -- so the question "is this one on a network?" has to be answered in
 * a hash lookup with no allocation and nothing else. Everything expensive happens only after that lookup hits.
 *
 * <p><b>Why a shadow of each slot.</b> {@code updateSlot} says which slot changed, not what it held before, and
 * a count cannot be corrected without the difference. So the index remembers what it last saw in every member
 * slot; the hook compares, applies the change, and stores the new value. That is what makes maintenance O(1)
 * per change rather than a rescan, and it is also what lets a foreign change -- a player, a settler, another
 * mod -- be picked up exactly rather than waited out.
 *
 * <p>Thread safety is why the map is a {@link ConcurrentHashMap} rather than an {@link IdentityHashMap}:
 * singleplayer runs a client and a server in one process, and the client's inventories are updated on its own
 * thread. Those inventories are never keys here, so no counts are touched from a client thread -- but the
 * lookup itself must not race a server-side write, and a plain hash map resizing under a concurrent read is a
 * documented way to hang. {@code Inventory} does not override {@code equals} or {@code hashCode}, so a
 * concurrent map keyed by it is already an identity map.
 */
public final class IndexedInventories {

   private static final Map<Inventory, NetworkIndex> OWNERS = new ConcurrentHashMap<>();

   /**
    * How many slot updates the hook has seen, of any inventory anywhere.
    *
    * <p>Diagnostic, and the evidence that the patch applied at all: a number that never moves means the
    * advice was not woven in, which is the failure this class must not suffer silently.
    */
   public static volatile long notifications;

   /** How many of those were an inventory on one of our networks. */
   public static volatile long relevant;

   private IndexedInventories() {
   }

   /** Called from the patch, for every inventory in the game. Must stay cheap. */
   public static void slotChanged(Inventory inventory, int slot) {
      notifications++;

      NetworkIndex index = OWNERS.get(inventory);
      if (index == null) {
         return;
      }

      relevant++;
      index.slotChanged(inventory, slot);
   }

   /** Points every member inventory of an index at it. Called when an index is built or rebuilt. */
   static void claim(NetworkIndex index) {
      for (Inventory inventory : index.memberInventories()) {
         OWNERS.put(inventory, index);
      }
   }

   /**
    * Drops the inventories an index no longer holds.
    *
    * <p>Called before a rebuild, because a unit that has left the network must stop reporting to it -- and
    * because leaving a broken container in the map would keep its inventory alive for as long as the process
    * ran.
    */
   static void release(NetworkIndex index) {
      OWNERS.entrySet().removeIf(entry -> entry.getValue() == index);
   }

   /** Everything, for the harness between scenarios. */
   public static void forget() {
      OWNERS.clear();
   }

   /** How many inventories are being watched. For diagnosis. */
   public static int watched() {
      return OWNERS.size();
   }

   /**
    * Proves the patch is woven in, and complains loudly in the log if it is not.
    *
    * <p>Asserted rather than assumed, because the failure is invisible: a patch that silently did not apply
    * leaves an index that believes in iron that somebody carried away ten minutes ago, and no error anywhere.
    * A method patch binds to an exact signature, so a game update is enough to break it.
    *
    * <p>The probe is a one-slot inventory that belongs to nobody. It is not registered as a member, so the
    * hook does its lookup, misses, and returns -- all this checks is that the hook ran at all, which is
    * exactly the thing that cannot be checked any other way.
    *
    * @return whether the hook fired
    */
   public static boolean verifyHook() {
      long before = notifications;
      Inventory probe = new Inventory(1);
      probe.setItem(0, new InventoryItem("stone", 1));

      boolean fired = notifications > before;
      if (!fired) {
         GameLog.warn.println("Arcane Storage: the Inventory.updateSlot patch did not apply. Networks will "
            + "fall back to periodic recounting, so item counts may lag by up to a second and a rule may act "
            + "on a stale number. This usually means the game updated and the method's signature changed.");
      }

      return fired;
   }

   /** Whether the hook is known to work. Read by the reconciliation, which does more when it does not. */
   public static boolean hookWorks() {
      return hookVerified;
   }

   private static boolean hookVerified;

   public static void recordVerification(boolean verified) {
      hookVerified = verified;
   }

   /** The item in a slot, or null. Kept here so the shadow and the hook read a slot the same way. */
   static Item itemIn(Inventory inventory, int slot) {
      InventoryItem item = inventory.getItem(slot);
      return item == null ? null : item.item;
   }

   /** The amount in a slot, or zero. */
   static int amountIn(Inventory inventory, int slot) {
      InventoryItem item = inventory.getItem(slot);
      return item == null ? 0 : item.getAmount();
   }
}
