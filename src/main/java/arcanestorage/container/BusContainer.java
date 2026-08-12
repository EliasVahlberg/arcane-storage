package arcanestorage.container;

import arcanestorage.objectentity.BusObjectEntity;
import necesse.engine.GameLog;
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

         // Temporary, and the decisive line for the open bug: if this says content=null or count=0 while
         // the server's open line said count=10, the filter never crossed and the panel is editing an
         // empty filter that will overwrite the real one on the next edit.
         GameLog.warn.println("[bus] client copy built, content=" + (content == null ? "null" : "present")
               + " " + describeFilter(local));
         this.filter = local;
      }

      this.setFilterAction = this.registerAction(new BusContainer.SetFilterAction());
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

   /** Temporary diagnostic: what the filter actually allows, whatever it is. */
   static String describeFilter(ItemCategoriesFilter filter) {
      int count = 0;
      StringBuilder names = new StringBuilder();
      for (necesse.inventory.item.Item item : necesse.engine.registries.ItemRegistry.getItems()) {
         if (item != null && filter.isItemAllowed(item)) {
            count++;
            if (count <= 8) {
               necesse.inventory.itemFilter.ItemCategoriesFilter.ItemLimits limits = filter.getItemLimits(item);
               names.append(names.length() == 0 ? "" : ",").append(item.getStringID()).append("=")
                  .append(limits == null || limits.isDefault() ? "-" : String.valueOf(limits.getMaxItems()));
            }
         }
      }

      return "count=" + count + " [" + names + "] mode=" + filter.limitMode
         + " maxAmount=" + (filter.maxAmount == Integer.MAX_VALUE ? "-" : String.valueOf(filter.maxAmount));
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
         boolean server = BusContainer.this.client.isServer();
         GameLog.warn.println("[bus] setFilter arrived, isServer=" + server);
         if (server) {
            BusContainer.this.bus.filter.readPacket(reader);
            GameLog.warn.println("[bus] applied, now " + describeFilter(BusContainer.this.bus.filter));
         }
      }
   }
}
