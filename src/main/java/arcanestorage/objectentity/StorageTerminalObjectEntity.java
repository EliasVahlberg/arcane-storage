package arcanestorage.objectentity;

import java.util.ArrayList;
import java.util.List;

import arcanestorage.network.UnitNetwork;
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

   /**
    * Ceiling on how many units one network may contain, and so on the container's slot
    * count — 40 slots each, so 64 units is 2560 slots.
    *
    * <p>A first guess, to be revised by measurement rather than argument. It bounds the
    * unit <i>count</i>, not the distance walked, so a very long thin chain can still reach
    * further than the client has level data loaded. That is tolerable: the two sides
    * disagreeing about membership shows stale contents until the terminal is reopened, and
    * cannot move items wrongly, because no network slot index is ever sent by the client —
    * withdrawal sends an item and the server re-resolves it against its own units.
    */
   public static final int MAX_UNITS = 64;

   public StorageTerminalObjectEntity(Level level, int x, int y, int slots) {
      super(level, x, y, slots);
      this.type = TYPE;
   }

   /**
    * The linked Storage Units, discovered fresh on each call.
    *
    * <p>Membership is connectivity: any unit reachable from this terminal through
    * orthogonally touching units. Units conduct, so a chain or block of them all belongs to
    * one network, but a terminal never bridges two groups. See {@link UnitNetwork} for the
    * traversal and the reasoning behind its guarantees.
    *
    * <p>Still no persistence: the network is recomputed each time rather than stored, so
    * there is nothing to keep in sync with the world. That is why breaking a unit needs no
    * cleanup. Persisted membership only becomes necessary if linking stops being a pure
    * function of layout.
    *
    * <p>Only this mod's own units qualify. Vanilla chests are deliberately not scanned:
    * silently absorbing a nearby chest would be surprising, and a unit is distinguishable
    * precisely because the player cannot open it.
    */
   public List<StorageUnitObjectEntity> getLinkedUnits() {
      final Level level = this.getLevel();
      if (level == null) {
         return new ArrayList<>();
      }

      return UnitNetwork.discover(this.tileX, this.tileY, (x, y) -> {
         ObjectEntity candidate = level.entityManager.getObjectEntity(x, y);
         if (candidate instanceof StorageUnitObjectEntity && !candidate.removed()) {
            return (StorageUnitObjectEntity)candidate;
         }

         return null;
      }, MAX_UNITS);
   }
}
