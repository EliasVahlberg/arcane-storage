package arcanestorage.remote;

import arcanestorage.objectentity.BusSummary;
import java.util.ArrayList;
import java.util.List;
import necesse.engine.network.PacketReader;
import necesse.engine.network.PacketWriter;
import necesse.inventory.container.events.ContainerEvent;

/**
 * The Logistics tab's contents, for a viewer who is not on the network's level.
 *
 * <p>The terminal object entity already sends its bus list to clients, in {@code setupContentPacket}, and that is
 * exactly why this class exists: an object entity's content reaches clients through
 * {@code sendToClientsWithEntity}, which is proximity-based, so a remote viewer receives it never. The tab was
 * simply blank -- no buses, no warnings -- which reads as a network with no buses rather than as a gap.
 *
 * <p>Sent as a whole list rather than as changes. A network has a handful of buses where it has hundreds of slots,
 * and each summary already carries its conflict partner's name, so a partial update would have to reason about
 * pairs to stay coherent. The slot mirror earns its diffing; this does not.
 */
public class BusMirrorEvent extends ContainerEvent {

   public final List<BusSummary> buses;

   public BusMirrorEvent(List<BusSummary> buses) {
      this.buses = buses;
   }

   public BusMirrorEvent(PacketReader reader) {
      int count = reader.getNextShortUnsigned();
      List<BusSummary> read = new ArrayList<>(count);
      for (int i = 0; i < count; i++) {
         read.add(BusSummary.readPacket(reader));
      }

      this.buses = read;
   }

   @Override
   public void write(PacketWriter writer) {
      writer.putNextShortUnsigned(this.buses.size());
      for (BusSummary summary : this.buses) {
         summary.writePacket(writer);
      }
   }
}
