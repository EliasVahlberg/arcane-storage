package arcanestorage.container;

import necesse.engine.localization.message.GameMessage;
import necesse.entity.objectEntity.ObjectEntity;
import necesse.inventory.Inventory;
import arcanestorage.network.NetworkStations;
import arcanestorage.network.NetworkStorage;

/**
 * A network member that exists only in a client's memory, standing in for one the client cannot reach.
 *
 * <p>An {@link Inventory} of the right size and nothing else. It deliberately implements both member interfaces,
 * because the two differ only in which list they belong to and a second near-identical class would be two places to
 * keep one decision.
 *
 * <p><b>Why a client ever needs one.</b> A client holds only the level it stands on, and only the regions near its
 * player -- the server sends object entities per region. So there are two distinct cases where a real member cannot be
 * resolved client-side: a wireless terminal opened from another level, and a locally-opened terminal whose network
 * reaches through a Base Station to storage far enough away that the client has never been sent it. The second case is
 * why this class is no longer private to the remote path: it presented as storage that accepted items but showed
 * nothing, since the client's own walk quietly produced a shorter member list than the server's.
 *
 * <p>The two overridden defaults matter. {@code isOnNetwork()} derives from an object entity in the real
 * implementations, and here there is none -- left inherited it would report every stand-in as gone, and the container
 * closes itself when a unit drops off the network, so the terminal would shut the instant it opened.
 * {@code tileOrder()} is likewise synthesised from the position in the sent list, which is already the tile order the
 * server enumerated: order is what makes a slot index mean the same thing on both sides.
 */
public final class MirroredMember implements NetworkStorage, NetworkStations {

   private final Inventory inventory;

   private final GameMessage name;

   private final int order;

   public MirroredMember(int size, GameMessage name, int order) {
      this.inventory = new Inventory(Math.max(size, 0));
      this.name = name;
      this.order = order;
   }

   @Override
   public Inventory getInventory() {
      return this.inventory;
   }

   @Override
   public GameMessage getInventoryName() {
      return this.name;
   }

   @Override
   public ObjectEntity getObjectEntity() {
      return null;
   }

   @Override
   public boolean isOnNetwork() {
      return true;
   }

   @Override
   public long tileOrder() {
      return this.order;
   }
}
