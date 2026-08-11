package arcanestorage.network;

import necesse.entity.objectEntity.ObjectEntity;
import necesse.entity.objectEntity.interfaces.OEInventory;

/**
 * An object entity that contributes its slots to a storage network.
 *
 * <p>This is the seam another mod joins through. Implement it on an object entity, place the object
 * against a network, and its slots are part of that network — no patch, and nothing here needs to know
 * the type exists. It is the reason this mod asks "does this contribute storage?" rather than "is this
 * my Storage Unit?".
 *
 * <p><b>Built on the game's own interface rather than a new accessor.</b> {@link OEInventory} is what
 * vanilla uses for "object entity with an inventory", and it is what
 * {@code OEInventoryContainerSlot(OEInventory, int)} takes — so extending it means a member's slots
 * can be handed to vanilla's own slot type unchanged, and the member inherits the vanilla answers to
 * quick-stack, restock, sort and settlement storage. Inventing a {@code getNetworkInventory()} would
 * have duplicated {@link OEInventory#getInventory()} and thrown that away.
 *
 * <p>The one method added is {@link #getObjectEntity()}, because membership is recomputed from the
 * world on every access and a member that has been broken must drop out. {@link OEInventory} has no
 * route back to the entity, and an implementation is one line: {@code return this;}.
 *
 * <p><b>What a member must not assume.</b> Nothing calls it on a schedule; a network is a walk over
 * tiles performed when a terminal asks. A member that needs to tick should tick itself as any object
 * entity does.
 */
public interface NetworkStorage extends OEInventory {

   /**
    * This member as an object entity, for the two things the network needs from the world: whether it
    * still exists, and where it is.
    */
   ObjectEntity getObjectEntity();

   /**
    * Whether this member should currently be counted.
    *
    * <p>Default is "as long as it exists". A member with a reason to leave the network temporarily —
    * powered down, sealed, being emptied — can say so here, and the network will stop counting its
    * slots without the player having to break anything.
    */
   default boolean isOnNetwork() {
      ObjectEntity entity = this.getObjectEntity();
      return entity != null && !entity.removed();
   }
}
