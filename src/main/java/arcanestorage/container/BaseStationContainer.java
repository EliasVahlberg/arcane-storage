package arcanestorage.container;

import arcanestorage.objectentity.ArcaneBaseStationObjectEntity;
import necesse.engine.network.NetworkClient;
import necesse.engine.network.Packet;
import necesse.engine.network.packet.PacketOpenContainer;
import necesse.engine.network.server.ServerClient;
import necesse.engine.registries.ContainerRegistry;
import necesse.inventory.container.Container;
import necesse.level.maps.Level;

/**
 * The Base Station's panel: a view of the band's channels, and nothing to change.
 *
 * <p>No slots, and one action: a way into the upgrade panel. Everything the panel draws arrives with the object
 * entity's own sync, so there is no open payload either -- see {@link ArcaneBaseStationObjectEntity#getRows()}.
 *
 * <p>The upgrade action exists because right-clicking a station opens this list rather than the upgrade panel that a
 * unit or a transceiver opens, so without a button here the only route to the next rung would be breaking the station
 * -- which takes the band and every channel on it. Reusing the upgrade panel rather than putting a cost display here
 * keeps one place that knows what an upgrade costs and what to say when it is refused.
 *
 * <p>Read-only is a design decision rather than a stage of construction. A channel belongs to the Access Point that
 * claimed it, and the Access Point is the device standing next to the storage it speaks for; moving those controls
 * here would let a player rewire a silo they cannot see, and the refusal messages -- out of range, channel taken --
 * would be about a place they are not standing. The station answers "what is on my band", which is the question you
 * walk to a station to ask.
 */
public class BaseStationContainer extends Container {

   public final ArcaneBaseStationObjectEntity station;

   public final BaseStationContainer.OpenUpgradeAction openUpgradeAction;

   public BaseStationContainer(NetworkClient client, int uniqueSeed, ArcaneBaseStationObjectEntity station) {
      super(client, uniqueSeed);
      this.station = station;
      this.openUpgradeAction = this.registerAction(new BaseStationContainer.OpenUpgradeAction());
   }

   /**
    * Swaps this panel for the upgrade panel.
    *
    * <p>Carries nothing: the server knows which station this container is for, and a tile from a client would be a
    * tile the client chose.
    */
   public class OpenUpgradeAction extends necesse.inventory.container.customAction.ContainerCustomAction {

      public void runAndSend() {
         this.runAndSendAction(new Packet());
      }

      @Override
      public void executePacket(necesse.engine.network.PacketReader reader) {
         if (!BaseStationContainer.this.client.isServer()) {
            return;
         }

         ServerClient serverClient = BaseStationContainer.this.client.getServerClient();
         ArcaneBaseStationObjectEntity station = BaseStationContainer.this.station;
         if (serverClient != null && station != null) {
            arcanestorage.upgrade.UnitUpgradeContainer.open(
                  arcanestorage.ArcaneStorage.UPGRADE_CONTAINER, serverClient, station);
         }
      }
   }

   public static void openAndSendContainer(
      int containerID, ServerClient client, Level level, ArcaneBaseStationObjectEntity station
   ) {
      if (!level.isServer()) {
         throw new IllegalStateException("Level must be a server level");
      }

      // The station is validated before the panel opens, so a player who has just placed a transceiver does not open
      // a window still saying they need one. Cheap: one walk, and only on a click.
      station.invalidate();
      ContainerRegistry.openAndSendContainer(client,
            PacketOpenContainer.ObjectEntity(containerID, station, new Packet()));
   }
}
