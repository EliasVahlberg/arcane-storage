package arcanestorage.objectentity;

import necesse.entity.objectEntity.InventoryObjectEntity;
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

   public StorageTerminalObjectEntity(Level level, int x, int y, int slots) {
      super(level, x, y, slots);
      this.type = TYPE;
   }
}
