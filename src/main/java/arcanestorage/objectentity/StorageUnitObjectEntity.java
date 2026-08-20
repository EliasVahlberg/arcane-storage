package arcanestorage.objectentity;

import arcanestorage.network.NetworkIndexes;
import arcanestorage.network.NetworkStorage;
import necesse.entity.objectEntity.InventoryObjectEntity;
import necesse.entity.objectEntity.ObjectEntity;
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
public class StorageUnitObjectEntity extends InventoryObjectEntity implements NetworkStorage {

   /** Must never change between versions. */
   public static final String TYPE = "arcanestorageunit";

   /**
    * How fast an item spoils in this unit, from {@link arcanestorage.object.UnitTier#spoilRateModifier}. Held
    * here rather than read from the tier each tick because a unit's own tier cannot change after placement, and
    * this avoids a lookup back through the object for something that never varies for the entity's lifetime.
    */
   private final float spoilRateModifier;

   public StorageUnitObjectEntity(Level level, int x, int y, int slots, float spoilRateModifier) {
      super(level, x, y, slots);
      this.type = TYPE;
      this.spoilRateModifier = spoilRateModifier;

      // A unit appearing changes what networks exist, and this catches the paths the object's placement hook
      // does not: a world loading, and a test placing one directly.
      NetworkIndexes.topologyChanged();
   }

   /**
    * Reasserts this unit's spoil rate every tick, ahead of {@code super}'s call into
    * {@code inventory.tickItems}, which is what actually applies it.
    *
    * <p>{@link InventoryObjectEntity} itself writes {@code spoilRateModifier} in several places -- a "not yet
    * loaded" pause on a freshly placed object, and an unconditional reset to {@code 1.0F} once the object is
    * considered interacted-with -- none of which know this is a Storage Unit with its own tiered rate. Chasing
    * every such site individually would mean re-checking this class against a future engine update that adds
    * another one; reasserting once per tick, right before the tick that reads the field, is correct regardless
    * of how many such sites exist or will exist.
    */
   @Override
   public void serverTick() {
      this.inventory.spoilRateModifier = this.spoilRateModifier;
      super.serverTick();
   }

   @Override
   public void clientTick() {
      this.inventory.spoilRateModifier = this.spoilRateModifier;
      super.clientTick();
   }

   /** Number of occupied slots, for the interact readout. */
   /**
    * How many of this unit's slots hold something.
    *
    * <p>Delegates to the engine, which counts the inventory's backing slot array directly
    * ({@code Arrays.stream(items).filter(Objects::nonNull).count()}). It is therefore a
    * count of occupied slots and never derived from the terminal's aggregated view, so it
    * cannot disagree with what the unit actually holds.
    *
    * <p>The figure is deliberately <b>per unit</b>, not per network: this is reported when
    * inspecting one unit, so a network's total is the sum across its units.
    */
   public int getUsedSlots() {
      return this.inventory.getUsedSlots();
   }

   /**
    * The network needs the entity behind a member to know whether it still exists. For an object
    * entity that is itself, which is the whole reason {@link NetworkStorage} can be a one-line
    * implementation for anyone else too.
    */
   @Override
   public ObjectEntity getObjectEntity() {
      return this;
   }
}
