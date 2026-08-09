package arcanestorage.network;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import arcanestorage.objectentity.StorageUnitObjectEntity;
import necesse.inventory.Inventory;
import necesse.inventory.InventoryItem;
import necesse.level.maps.Level;

/**
 * Reads the combined contents of a set of storage units.
 *
 * <p>Separate from the container on purpose. {@code Container} needs a
 * {@code NetworkClient} and therefore a player, so anything expressed in terms of a
 * container can only be tested with a client connected. Expressed over units and their
 * inventories instead, the same logic is reachable from a server console command, which is
 * what lets the scenario harness assert on it with no player present.
 */
public final class NetworkContents {

   private NetworkContents() {
   }

   /**
    * The units' contents as one deduplicated list, each entry carrying the summed amount
    * across every unit.
    *
    * <p>Identity is {@code InventoryItem.equals(level, other, ignoreMeta, ignoreGNDData,
    * purpose)} with {@code ignoreMeta = true} and {@code ignoreGNDData = false}: amounts
    * must be ignored because they are exactly what is being summed, while GND data must
    * <b>not</b> be, or an enchanted item would merge into a plain stack and lose its
    * enchantment the moment it was withdrawn.
    *
    * <p>Entries are copies. Summing into a slot's own {@code InventoryItem} would edit the
    * unit's real contents.
    */
   public static List<InventoryItem> aggregate(Level level, List<StorageUnitObjectEntity> units, String purpose) {
      List<InventoryItem> aggregated = new ArrayList<>();

      // Candidates bucketed by item string ID, so merging a slot compares it only against
      // entries that could possibly match instead of against every entry found so far.
      //
      // The naive linear scan is quadratic in distinct items: a full 64-unit network is 2560
      // slots, and with several hundred distinct items that is over a million calls to
      // InventoryItem.equals per aggregation -- while the interface aggregates as it draws.
      // Bucketing keeps identity exactly as it was, because two items with different string
      // IDs can never be equal, so this only skips comparisons that were always going to fail.
      Map<String, List<InventoryItem>> byStringID = new HashMap<>();

      for (StorageUnitObjectEntity unit : units) {
         Inventory inventory = unit.inventory;

         for (int slot = 0; slot < inventory.getSize(); slot++) {
            InventoryItem item = inventory.getItem(slot);
            if (item == null) {
               continue;
            }

            // Output order stays first-seen, which keeps the list deterministic for a given
            // layout -- the bucket map is only an index into it, never the order itself.
            List<InventoryItem> candidates = byStringID.computeIfAbsent(item.item.getStringID(), id -> new ArrayList<>());
            boolean merged = false;

            for (InventoryItem existing : candidates) {
               if (existing.equals(level, item, true, false, purpose)) {
                  existing.setAmount(existing.getAmount() + item.getAmount());
                  merged = true;
                  break;
               }
            }

            if (!merged) {
               InventoryItem copy = item.copy();
               aggregated.add(copy);
               candidates.add(copy);
            }
         }
      }

      return aggregated;
   }

   /**
    * Slots holding something, across every unit.
    *
    * <p>Counted in slots rather than in items because that is what actually runs out: a unit
    * with 40 slots holds 40 stacks whatever their size, so a network can be simultaneously
    * "nearly empty" by item count and completely full. Reporting stacks would tell the player
    * the comfortable number rather than the one that will stop a deposit.
    */
   public static int usedSlots(List<StorageUnitObjectEntity> units) {
      int used = 0;

      for (StorageUnitObjectEntity unit : units) {
         used += unit.inventory.getUsedSlots();
      }

      return used;
   }

   /** Slots in total, across every unit. Zero for a network with no units. */
   public static int totalSlots(List<StorageUnitObjectEntity> units) {
      int total = 0;

      for (StorageUnitObjectEntity unit : units) {
         total += unit.inventory.getSize();
      }

      return total;
   }

   /**
    * Whether an item could be deposited somewhere in the network.
    *
    * <p>A free slot is not the only way in: an existing stack with room takes an item without
    * consuming a slot, so a network with every slot occupied can still accept more of what it
    * already holds. Answering this by slot count alone would refuse deposits that would in fact
    * succeed.
    */
   public static boolean canFit(Level level, List<StorageUnitObjectEntity> units, InventoryItem item, String purpose) {
      for (StorageUnitObjectEntity unit : units) {
         Inventory inventory = unit.inventory;

         for (int slot = 0; slot < inventory.getSize(); slot++) {
            InventoryItem existing = inventory.getItem(slot);
            if (existing == null) {
               return true;
            }

            if (existing.getAmount() < existing.item.getStackSize() && existing.equals(level, item, true, false, purpose)) {
               return true;
            }
         }
      }

      return false;
   }

   /** The total amount of one item across the units, by string ID. */
   public static int totalOf(List<StorageUnitObjectEntity> units, String itemStringID) {      int total = 0;

      for (StorageUnitObjectEntity unit : units) {
         Inventory inventory = unit.inventory;

         for (int slot = 0; slot < inventory.getSize(); slot++) {
            InventoryItem item = inventory.getItem(slot);
            if (item != null && item.item.getStringID().equals(itemStringID)) {
               total += item.getAmount();
            }
         }
      }

      return total;
   }
}
