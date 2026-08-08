package arcanestorage.objectentity;

import necesse.entity.objectEntity.InventoryObjectEntity;
import necesse.level.maps.Level;

/**
 * Object entity behind a Storage Unit — the network's actual capacity.
 *
 * <p>Structurally identical to {@link StorageTerminalObjectEntity}: an inventory attached
 * to a tile. The difference is entirely in the object, which refuses to open a container.
 * See {@code arcanestorage.object.StorageUnitObject}.
 *
 * <p>As with the terminal, object entities are not registered anywhere in Necesse, so the
 * only requirement is that {@link #TYPE} is stable across versions — a mismatch makes the
 * saved data load as invalid and the unit comes back empty.
 */
public class StorageUnitObjectEntity extends InventoryObjectEntity {

   /** Must never change between versions. */
   public static final String TYPE = "arcanestorageunit";

   public StorageUnitObjectEntity(Level level, int x, int y, int slots) {
      super(level, x, y, slots);
      this.type = TYPE;
   }

   /** Number of occupied slots, for the interact readout. */
   public int getUsedSlots() {
      int used = 0;
      for (int i = 0; i < this.inventory.getSize(); i++) {
         if (!this.inventory.isSlotClear(i)) {
            used++;
         }
      }
      return used;
   }
}
