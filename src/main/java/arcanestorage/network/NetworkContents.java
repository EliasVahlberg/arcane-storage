package arcanestorage.network;

import java.util.ArrayList;
import java.util.List;

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

      for (StorageUnitObjectEntity unit : units) {
         Inventory inventory = unit.inventory;

         for (int slot = 0; slot < inventory.getSize(); slot++) {
            InventoryItem item = inventory.getItem(slot);
            if (item == null) {
               continue;
            }

            boolean merged = false;

            for (InventoryItem existing : aggregated) {
               if (existing.equals(level, item, true, false, purpose)) {
                  existing.setAmount(existing.getAmount() + item.getAmount());
                  merged = true;
                  break;
               }
            }

            if (!merged) {
               aggregated.add(item.copy());
            }
         }
      }

      return aggregated;
   }

   /** The total amount of one item across the units, by string ID. */
   public static int totalOf(List<StorageUnitObjectEntity> units, String itemStringID) {
      int total = 0;

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
