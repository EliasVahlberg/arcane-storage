package arcanestorage.objectentity;

import arcanestorage.object.UnitTier;
import arcanestorage.object.WirelessTransceiverObject;
import necesse.level.maps.Level;

/**
 * The Wireless Transceiver's object entity: a network access point that is never opened locally.
 *
 * <h2>Why it extends the terminal's entity</h2>
 *
 * <p>A transceiver has to answer exactly the questions a Storage Terminal answers -- which units, stations and
 * buses its conduits reach -- and it has to answer them for a container built to talk to a terminal. Extending is
 * therefore not laziness but the point: the wireless container gets the same network view, the same bus summaries
 * and the same per-tick revalidation, and there is no second copy of the traversal to keep in step with the first.
 *
 * <p>It carries no crafting sockets, hence zero slots. A terminal's slots are installed benches, and a transceiver
 * is not a place a player stands and crafts -- the crafting happens at whatever the network's Station Units offer,
 * which the wireless container reads from the network rather than from here. Zero slots also means breaking a
 * transceiver drops nothing but itself, which is the behaviour to want: nothing of the player's is stored in it.
 */
public class WirelessTransceiverObjectEntity extends StorageTerminalObjectEntity {

   /** A transceiver installs nothing, so its inventory has no slots at all. */
   public static final int SLOTS = 0;

   public WirelessTransceiverObjectEntity(Level level, int x, int y) {
      super(level, x, y, SLOTS);
   }

   /**
    * The tier of the object standing on this tile, or null if it is somehow not a transceiver.
    *
    * <p>Read from the object rather than stored here, so an in-place upgrade -- which replaces the object and
    * builds a fresh entity -- cannot leave the two disagreeing about which tier this is.
    */
   public UnitTier tier() {
      if (this.getLevel() == null) {
         return null;
      }

      necesse.level.gameObject.GameObject object = this.getLevel().getObject(this.tileX, this.tileY);
      return object instanceof WirelessTransceiverObject ? ((WirelessTransceiverObject)object).tier : null;
   }
}
