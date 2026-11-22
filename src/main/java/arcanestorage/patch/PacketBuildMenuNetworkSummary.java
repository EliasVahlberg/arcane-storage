package arcanestorage.patch;

import necesse.engine.network.NetworkPacket;
import necesse.engine.network.Packet;
import necesse.engine.network.PacketReader;
import necesse.engine.network.PacketWriter;
import necesse.engine.network.client.Client;
import necesse.engine.network.server.Server;
import necesse.engine.network.server.ServerClient;
import necesse.inventory.Inventory;

/**
 * The compact network summary sent to a connected client, so Build Menu's own affordability check
 * can count what the server will actually take.
 *
 * <p>The payload is one synthetic {@code Inventory}: a single stack per distinct item, with the
 * summed amount. Amounts above an item's stack size ride in that one slot -- {@code InventoryItem}
 * does not clamp, and its content packet reads and writes the raw amount, so a count of 5000 stays
 * 5000 across the wire rather than being split or truncated. The client only ever counts from this;
 * consumption happens on the server against the real units.
 *
 * <p>Server-to-client only. The server side of this packet is a no-op, and the handler stores the
 * summary and asks Build Menu to refresh its view.
 */
public class PacketBuildMenuNetworkSummary extends Packet {

   /** The synthetic summary inventory, or an empty one when the player stopped being connected. */
   public final Inventory summary;

   public PacketBuildMenuNetworkSummary(byte[] data) {
      super(data);
      PacketReader reader = new PacketReader(this);
      this.summary = Inventory.getInventory(reader);
   }

   public PacketBuildMenuNetworkSummary(Inventory summary) {
      this.summary = summary;
      PacketWriter writer = new PacketWriter(this);
      this.summary.writeContent(writer);
   }

   @Override
   public void processServer(NetworkPacket packet, Server server, ServerClient client) {
   }

   @Override
   public void processClient(NetworkPacket packet, Client client) {
      BuildMenuCompat.applyNetworkSummary(this.summary);
   }
}
