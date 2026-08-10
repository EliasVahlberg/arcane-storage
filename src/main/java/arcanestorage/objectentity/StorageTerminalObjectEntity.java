package arcanestorage.objectentity;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;

import arcanestorage.ArcaneStorage;
import arcanestorage.network.UnitNetwork;
import necesse.entity.objectEntity.InventoryObjectEntity;
import necesse.entity.objectEntity.ObjectEntity;
import necesse.inventory.recipe.Tech;
import necesse.inventory.InventoryItem;
import necesse.inventory.item.placeableItem.objectItem.ObjectItem;
import necesse.level.gameObject.GameObject;
import necesse.level.gameObject.container.CraftingStationObject;
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

   /**
    * Ceiling on conducting tiles one network may be routed through.
    *
    * <p>Separate from {@link #MAX_UNITS} because conduits cost almost nothing to place and
    * hold no items: without their own bound, a long cheap run would make discovery expensive
    * for a network that stores nothing. Also the practical answer to "how far may a network
    * reach" — reach is now bounded by this rather than by unit count.
    */
   public static final int MAX_CONDUITS = 256;

   /**
    * How many crafting stations one terminal may hold.
    *
    * <p>Ten because that is one row at vanilla's slot pitch, and because the game has eight
    * station <i>families</i> -- workstation, anvil, carpenter, alchemy, cooking pot, roasting
    * station, forge, landscaping -- so ten covers every family at once with headroom. Tiers cost
    * nothing extra: an upgraded bench reports the lower techs as well as its own, so a Demonic
    * Workstation occupies one slot and answers for both.
    */
   public static final int STATION_SLOTS = 10;

   /**
    * Only crafting stations may be installed, one per slot.
    *
    * <p>Asked of the item rather than checked against a list of known benches:
    * {@link ObjectItem#getObject()} reaches the object, and every station in the game -- 26 of
    * them -- is a {@link CraftingStationObject}. A modded bench that extends it therefore works
    * here without this mod knowing it exists.
    */
   @Override
   public boolean isItemValid(int slot, InventoryItem item) {
      return getCraftingStation(item) != null;
   }

   /** One bench per slot, so the slots read as an install list rather than as storage. */
   @Override
   public int getItemStackLimit(int slot, InventoryItem item) {
      return 1;
   }

   /**
    * The station an item would install, or null if it is not a station at all.
    */
   public static CraftingStationObject getCraftingStation(InventoryItem item) {
      if (item == null || !(item.item instanceof ObjectItem)) {
         return null;
      }

      GameObject object = ((ObjectItem) item.item).getObject();
      return object instanceof CraftingStationObject ? (CraftingStationObject) object : null;
   }

   /**
    * The techs the installed stations provide, in slot order.
    *
    * <p>Recomputed on each call rather than cached: the set changes whenever a slot does, and a
    * cache would be one more thing to invalidate for a loop over ten slots. Both sides can call
    * this and get the same answer, because {@code InventoryObjectEntity} already syncs its
    * inventory to the client in its content packet -- which is what lets the client show the right
    * recipes without a packet of our own.
    */
   public LinkedHashSet<Tech> getInstalledTechs() {
      LinkedHashSet<Tech> techs = new LinkedHashSet<>();

      for (int slot = 0; slot < this.inventory.getSize(); slot++) {
         CraftingStationObject station = getCraftingStation(this.inventory.getItem(slot));
         if (station != null) {
            techs.addAll(Arrays.asList(station.getCraftingTechs()));
         }
      }

      return techs;
   }

   public StorageTerminalObjectEntity(Level level, int x, int y, int slots) {
      super(level, x, y, slots);
      this.type = TYPE;
   }

   /**
    * The linked Storage Units, discovered fresh on each call.
    *
    * <p>Membership is connectivity: any unit reachable from this terminal through
    * orthogonally touching units and conduits. Units and conduits both conduct, so a chain or
    * block of them all belongs to one network, but a terminal never bridges two groups. A
    * conduit adds reach without adding capacity. See {@link UnitNetwork} for the
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
      }, (x, y) -> ArcaneStorage.CONDUIT != null && level.getObjectID(x, y) == ArcaneStorage.CONDUIT.getID(),
         MAX_UNITS, MAX_CONDUITS);
   }
}
