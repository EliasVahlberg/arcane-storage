package arcanestorage.container;

import arcanestorage.objectentity.BusObjectEntity;
import necesse.engine.network.NetworkClient;
import necesse.engine.network.Packet;
import necesse.engine.network.PacketReader;
import necesse.engine.network.PacketWriter;
import necesse.engine.network.packet.PacketOpenContainer;
import necesse.engine.network.server.ServerClient;
import necesse.engine.registries.ContainerRegistry;
import necesse.inventory.container.Container;
import necesse.inventory.container.customAction.ContainerCustomAction;
import necesse.inventory.item.ItemCategory;
import necesse.inventory.itemFilter.ItemCategoriesFilter;
import necesse.level.maps.Level;

/**
 * Server-side state for a bus's rule panel.
 *
 * <p>Holds no slots. A bus stores nothing and a player never puts an item into one, so this container
 * exists purely to carry a filter between the two sides — which is also why it does not extend
 * {@code OEInventoryContainer}: there is no inventory to show.
 *
 * <p><b>The client edits a copy.</b> On the server {@link #filter} <i>is</i> the bus's filter; on a client
 * it is a local copy built from the open packet, because a bus's filter is saved but not network-synced
 * and a client-side object entity therefore knows nothing about it. Edits travel back through
 * {@link SetFilterAction}.
 */
public class BusContainer extends Container {

   public final BusObjectEntity bus;

   /** The filter the form edits: the bus's own on the server, a copy of it on a client. */
   public final ItemCategoriesFilter filter;

   public final BusContainer.SetFilterAction setFilterAction;

   /** The server's refusal channel. Registered in the same order on both sides, as all actions must be. */
   public final BusContainer.RejectFilterAction rejectFilterAction;

   public final BusContainer.SetNameAction setNameAction;

   public BusContainer(NetworkClient client, int uniqueSeed, BusObjectEntity bus, Packet content) {
      super(client, uniqueSeed);
      this.bus = bus;
      if (client.isServer()) {
         this.filter = bus.filter;
      } else {
         ItemCategoriesFilter local = new ItemCategoriesFilter(ItemCategory.masterCategory, false);
         if (content != null) {
            local.readPacket(new PacketReader(content));
         }

this.filter = local;
      }

      this.setFilterAction = this.registerAction(new BusContainer.SetFilterAction());
      this.rejectFilterAction = this.registerAction(new BusContainer.RejectFilterAction());
      this.setNameAction = this.registerAction(new BusContainer.SetNameAction());
   }

   /**
    * Opens a bus's panel, with its filter in the open packet.
    *
    * <p>The filter has to travel on open rather than be read from the object entity, because only the
    * server's copy of that entity has ever loaded one.
    */
   /**
    * The packet that opens a bus's panel, carrying the bus's filter to the client that is opening it.
    *
    * <p>Separate from sending it so that a test can drive the client's side of the hand-off, which is
    * otherwise reachable only from a real client. It was not reachable, and the bug that hid there cost a
    * day: the filter was wrapped in a content packet here <i>and</i> again by the factory, so the client
    * began reading at a length prefix and decoded an empty filter every time -- then wrote that emptiness
    * back over the real one. {@link PacketOpenContainer#ObjectEntity} already wraps what it is given, and
    * {@code ContainerRegistry.registerOEContainer} unwraps exactly one layer.
    */
   public static PacketOpenContainer openPacket(int containerID, BusObjectEntity bus) {
      Packet filterContent = new Packet();
      bus.filter.writePacket(new PacketWriter(filterContent));
      return PacketOpenContainer.ObjectEntity(containerID, bus, filterContent);
   }

   public static void openAndSendContainer(int containerID, ServerClient client, Level level, BusObjectEntity bus) {
      if (!level.isServer()) {
         throw new IllegalStateException("Level must be a server level");
      }

      ContainerRegistry.openAndSendContainer(client, openPacket(containerID, bus));
   }



   /**
    * Replaces the bus's whole filter.
    *
    * <p>Deliberately the entire filter on every edit rather than a delta per checkbox. A tri-state
    * category tree with per-item and per-category limits has a large number of possible partial updates,
    * and an out-of-order or dropped one leaves the two sides disagreeing about a rule that moves items —
    * a bug a player would experience as their storage quietly emptying. Sending the whole thing is
    * idempotent and self-correcting, and the traffic is one packet per click on a panel a player opens
    * occasionally. If that ever matters, {@code ItemCategoriesFilter} has the pieces for deltas.
    */
   public class SetFilterAction extends ContainerCustomAction {

      public void runAndSend(ItemCategoriesFilter edited) {
         Packet content = new Packet();
         edited.writePacket(new PacketWriter(content));
         this.runAndSendAction(content);
      }

      @Override
      public void executePacket(PacketReader reader) {
         if (!BusContainer.this.client.isServer()) {
            return;
         }

         // Read into a copy first. The point of an Apply button is that a rule set is adopted or refused as one
         // thing, and a filter read straight into the bus would already be in force by the time anything could
         // object -- possibly with half of it contradicting a neighbour and the other half fine.
         ItemCategoriesFilter proposed = new ItemCategoriesFilter(ItemCategory.masterCategory, false);
         proposed.readPacket(reader);

         String refusal = BusContainer.this.bus.whyRefused(proposed);
         if (refusal != null) {
            // Nothing is applied, not even the parts that were harmless. A half-applied rule set is a state the
            // player did not ask for and cannot see, which is worse than a refusal they can read.
            BusContainer.this.rejectFilterAction.runAndSend(refusal);
            return;
         }

         BusContainer.this.bus.filter.readPacket(new PacketReader(writeOf(proposed)));

         // The network has to be told, or a rule the player just set would wait for some unrelated change to
         // disturb the same item before anything happened. Nothing polls any more, so nothing would notice.
         BusContainer.this.bus.rulesChanged();
      }
   }

   /** A filter as a packet, so an accepted proposal can be copied into the bus's own filter. */
   private static Packet writeOf(ItemCategoriesFilter filter) {
      Packet content = new Packet();
      filter.writePacket(new PacketWriter(content));
      return content;
   }

   /**
    * The server's answer when a rule set is refused, carrying the reason back to the client that sent it.
    *
    * <p>{@code runAndSendAction} is symmetric -- a container on the server sends to its own client -- so the
    * reply needs no new packet type, and it reaches only the player who tried, which is where it belongs.
    */
   public class RejectFilterAction extends ContainerCustomAction {

      public void runAndSend(String reason) {
         Packet content = new Packet();
         new PacketWriter(content).putNextString(reason);
         this.runAndSendAction(content);
      }

      @Override
      public void executePacket(PacketReader reader) {
         String reason = reader.getNextString();
         if (!BusContainer.this.client.isServer()) {
            BusContainer.this.refusal = reason;
         }
      }
   }

   /**
    * Renames the bus.
    *
    * <p>Applied on arrival rather than gathered into the Apply transaction. A name is a label -- nothing reads
    * it to decide anything -- so there is no state it can contradict and nothing to refuse. Putting it in the
    * transaction would mean a refused rule set silently discarded a rename that was never in question.
    */
   public class SetNameAction extends ContainerCustomAction {

      public void runAndSend(String name) {
         Packet content = new Packet();
         new PacketWriter(content).putNextString(name);
         this.runAndSendAction(content);
      }

      @Override
      public void executePacket(PacketReader reader) {
         String name = reader.getNextString();
         if (BusContainer.this.client.isServer()) {
            BusContainer.this.bus.setCustomName(name);
         }
      }
   }

   /**
    * Why the last attempt to apply a rule set was refused, or null.
    *
    * <p>Transient and per-attempt: not saved, not synced to anyone else, and cleared as soon as an attempt
    * succeeds. It belongs to one player's edit, not to the bus.
    */
   public String refusal;
}
