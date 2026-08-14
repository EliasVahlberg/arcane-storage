package arcanestorage.upgrade;

import necesse.engine.network.PacketReader;
import necesse.engine.network.PacketWriter;
import necesse.inventory.container.events.ContainerEvent;

/**
 * Everything the upgrade panel draws, pushed from the server when it changes.
 *
 * <p>Exists because the panel's numbers are <b>derived</b> rather than stored. The terminal needs nothing like
 * this: its slots are the unit inventories themselves, so the engine's container-slot synchronisation already
 * delivers one player's deposit to every other player's open interface, which is why cross-player visibility
 * works there without a line of our code. An availability figure has no slot to ride along with -- "how many
 * demonic bars can this player reach" is a sum over a network and a backpack, and nothing in the engine syncs
 * a sum.
 *
 * <p>The alternative was to have the form ask on a timer. Rejected: a poll is either too slow to be honest or
 * too frequent to be cheap, and it would put this interface behind the rest of the mod, where a change is seen
 * because it was sent rather than because somebody looked. Vanilla makes the same call for the same reason --
 * {@code ShopWealthUpdateEvent} and {@code SingleShopStockUpdateEvent} push derived shop figures to open
 * containers rather than letting the shop screen ask.
 *
 * <p>The tile is carried so a client with a panel open on one unit ignores an event about another. Coalescing
 * is the container's job, not this class's; see {@link UnitUpgradeContainer#tick()}.
 */
public class UpgradeStateEvent extends ContainerEvent {

   public final int tileX;

   public final int tileY;

   /** Occupied slots, for the used/total readout. */
   public final int used;

   public final int total;

   /** Whether every requirement is met, which is what greys the button out. */
   public final boolean affordable;

   /**
    * How many of each material the player can reach, in the order {@link UnitUpgrade#cost} returns them.
    *
    * <p>Deliberately just the counts, not the item IDs: the client already knows what the cost is, because the
    * tier ladder is compiled into both sides. Sending the IDs would be sending a constant over the wire on
    * every change.
    */
   public final int[] available;

   public UpgradeStateEvent(int tileX, int tileY, int used, int total, boolean affordable, int[] available) {
      this.tileX = tileX;
      this.tileY = tileY;
      this.used = used;
      this.total = total;
      this.affordable = affordable;
      this.available = available;
   }

   public UpgradeStateEvent(PacketReader reader) {
      super(reader);
      this.tileX = reader.getNextInt();
      this.tileY = reader.getNextInt();
      this.used = reader.getNextInt();
      this.total = reader.getNextInt();
      this.affordable = reader.getNextBoolean();
      this.available = new int[reader.getNextByteUnsigned()];

      for (int i = 0; i < this.available.length; i++) {
         this.available[i] = reader.getNextInt();
      }
   }

   @Override
   public void write(PacketWriter writer) {
      writer.putNextInt(this.tileX);
      writer.putNextInt(this.tileY);
      writer.putNextInt(this.used);
      writer.putNextInt(this.total);
      writer.putNextBoolean(this.affordable);
      writer.putNextByteUnsigned(this.available.length);

      for (int amount : this.available) {
         writer.putNextInt(amount);
      }
   }

   /** Whether this says anything new, so an unchanged network sends nothing at all. */
   public boolean sameAs(UpgradeStateEvent other) {
      if (other == null
         || other.used != this.used
         || other.total != this.total
         || other.affordable != this.affordable
         || other.available.length != this.available.length) {
         return false;
      }

      for (int i = 0; i < this.available.length; i++) {
         if (other.available[i] != this.available[i]) {
            return false;
         }
      }

      return true;
   }
}
