package arcanestorage.objectentity;

import java.util.ArrayList;
import java.util.List;

import necesse.entity.objectEntity.InventoryObjectEntity;
import necesse.entity.objectEntity.ObjectEntity;
import necesse.level.maps.Level;

/**
 * Object entity behind the Storage Terminal.
 *
 * <p>Object entities are <b>not registered</b> anywhere in Necesse — there is no
 * {@code ObjectEntityRegistry}. {@code ObjectEntitySave.loadSave} rebuilds them by
 * calling {@code level.getLevelObject(x, y).getNewObjectEntity()} and then uses the
 * saved {@code stringID} purely as a consistency check against {@link #type}. So all
 * this class has to do is pick a stable, distinct type string.
 *
 * <p>{@link InventoryObjectEntity}'s only constructor hardcodes
 * {@code super(level, "inventory", x, y)}, so the type is reassigned here. {@code type}
 * is a public mutable field on {@code ObjectEntity} and is read nowhere else in the
 * game outside that save check.
 */
public class StorageTerminalObjectEntity extends InventoryObjectEntity {

   /** Must never change between versions: a mismatch makes the save data load as invalid. */
   public static final String TYPE = "arcanestorageterminal";

   /** Orthogonal neighbours only, in a fixed order so the linked list is deterministic. */
   private static final int[][] NEIGHBOURS = {{0, -1}, {1, 0}, {0, 1}, {-1, 0}};

   public StorageTerminalObjectEntity(Level level, int x, int y, int slots) {
      super(level, x, y, slots);
      this.type = TYPE;
   }

   /**
    * The linked Storage Units, discovered fresh on each call.
    *
    * <p><b>Scaffolding for Phase 1.</b> Membership is currently "a Storage Unit is directly
    * adjacent", which needs no persistence, no packets and no UI. Real membership —
    * whether that is adjacency with connectors, an explicit link action, or a range
    * bound — is Phase 2.
    *
    * <p>Order is fixed by {@link #NEIGHBOURS} rather than by iteration over a map, because
    * withdraw and deposit must resolve to the same inventory on the server as the client's
    * slot index implies. A non-deterministic order here would be an item-duplication bug.
    *
    * <p>Only this mod's own units qualify. Vanilla chests are deliberately not scanned:
    * silently absorbing a nearby chest would be surprising, and a unit is distinguishable
    * precisely because the player cannot open it.
    */
   public List<StorageUnitObjectEntity> getLinkedUnits() {
      List<StorageUnitObjectEntity> units = new ArrayList<>(NEIGHBOURS.length);
      Level level = this.getLevel();
      if (level == null) {
         return units;
      }

      for (int[] offset : NEIGHBOURS) {
         ObjectEntity neighbour = level.entityManager.getObjectEntity(this.tileX + offset[0], this.tileY + offset[1]);
         if (neighbour instanceof StorageUnitObjectEntity && !neighbour.removed()) {
            units.add((StorageUnitObjectEntity)neighbour);
         }
      }

      return units;
   }
}
