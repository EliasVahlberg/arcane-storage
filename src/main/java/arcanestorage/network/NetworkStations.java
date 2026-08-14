package arcanestorage.network;

import necesse.entity.objectEntity.ObjectEntity;
import necesse.entity.objectEntity.interfaces.OEInventory;

/**
 * A network member that hosts crafting stations rather than items.
 *
 * <p>Extends {@link OEInventory} because the sockets have to be a real inventory: the terminal's
 * container wraps their slots directly, so a copy would make installing a bench a no-op that looked
 * like it had worked. That also means an implementation inherits {@code OEInventory}'s defaults, and
 * <b>two of them are wrong here and must be refused</b> -- quick-stack would file loose items into
 * sockets, and {@code getSettlementStorage} would offer installed benches to settlers as haulable
 * goods. See {@code StationUnitObjectEntity}.
 *
 * <p>Deliberately <b>not</b> {@link NetworkStorage}, even though both are an inventory attached to a
 * tile and the temptation to reuse it is obvious. A Storage Unit's slots are capacity the player can
 * fill with anything; a Station Unit's slots are sockets that only accept a bench. If they shared an
 * interface, every count of the network's capacity would silently include the sockets, the aggregated
 * item view would list installed benches as stored goods, and a quick-stack would try to file a
 * Carpenters Bench into one. Two interfaces cost a file; conflating them would cost correctness in
 * several places at once.
 *
 * <p>Kept as an interface for the reason the rest of the network is: membership is a capability, not a
 * class. Anything that can hold a bench and say where it is can host stations, including something
 * another mod adds.
 */
public interface NetworkStations extends OEInventory {

   /** This member as an object entity, for whether it still exists and where it is. */
   ObjectEntity getObjectEntity();

   /**
    * Position as a single sortable number, {@code tileX} before {@code tileY}.
    *
    * <p><b>This is load-bearing and not merely tidy.</b> Station slots are addressed by index, and the
    * client sends the index when it moves an item, so both sides must agree on the order without
    * consulting each other. Membership is discovered independently per side by walking the network, so
    * the only thing guaranteeing agreement is that both sides sort the result the same way -- and a
    * walk's own visit order is not a guarantee, since it depends on where the walk started.
    *
    * <p>Position is the right key because it is the one property both sides already know and neither
    * can disagree about. A counter or an install order would need syncing, which is the problem.
    */
   default long tileOrder() {
      ObjectEntity entity = this.getObjectEntity();
      return entity == null ? 0L : (long)entity.tileX << 32 | (long)entity.tileY & 0xFFFFFFFFL;
   }

   /** Whether this unit should currently be counted. Default is "as long as it exists". */
   default boolean isOnNetwork() {
      ObjectEntity entity = this.getObjectEntity();
      return entity != null && !entity.removed();
   }
}
