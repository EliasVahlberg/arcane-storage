package arcanestorage.container;

import java.util.ArrayList;
import java.util.List;

import arcanestorage.band.BandOption;
import arcanestorage.objectentity.ArcaneAccessPointObjectEntity;
import necesse.engine.localization.Localization;
import necesse.engine.network.NetworkClient;
import necesse.engine.network.Packet;
import necesse.engine.network.PacketReader;
import necesse.engine.network.PacketWriter;
import necesse.engine.network.packet.PacketOpenContainer;
import necesse.engine.network.server.ServerClient;
import necesse.engine.registries.ContainerRegistry;
import necesse.inventory.container.Container;
import necesse.inventory.container.customAction.ContainerCustomAction;
import necesse.level.maps.Level;

/**
 * The Access Point's panel: which band, which channel, and what to call it.
 *
 * <p>The bands travel in the open packet, for the same reason a bus's filter does: {@code BandIndex} is server-side
 * and a client's level has no copy of it. What the Access Point has chosen does <b>not</b> travel here -- it is on the
 * object entity, which syncs itself, so the panel and the sprite cannot disagree about whether the thing is connected.
 *
 * <p>Tuning sends band and channel together. Two separate actions would let a client be half-tuned between packets,
 * and "channel 3 of nothing" is not a state worth being able to represent.
 */
public class AccessPointContainer extends Container {

   public final ArcaneAccessPointObjectEntity point;

   /** The bands offered, as of the moment the panel opened. Empty on the server, which does not need them. */
   public final List<BandOption> bands;

   public final AccessPointContainer.TuneAction tuneAction;

   public final AccessPointContainer.RejectAction rejectAction;

   public final AccessPointContainer.SetNameAction setNameAction;

   /** Why the last attempt was refused, already localized, or null. One player's attempt, not the device's state. */
   public String refusal;

   public AccessPointContainer(
      NetworkClient client, int uniqueSeed, ArcaneAccessPointObjectEntity point, Packet content
   ) {
      super(client, uniqueSeed);
      this.point = point;
      this.bands = client.isServer() || content == null
         ? new ArrayList<>()
         : BandOption.readAll(new PacketReader(content));

      this.tuneAction = this.registerAction(new AccessPointContainer.TuneAction());
      this.rejectAction = this.registerAction(new AccessPointContainer.RejectAction());
      this.setNameAction = this.registerAction(new AccessPointContainer.SetNameAction());
   }

   public static PacketOpenContainer openPacket(int containerID, ArcaneAccessPointObjectEntity point) {
      Packet content = new Packet();
      BandOption.writeAll(new PacketWriter(content),
            BandOption.visibleFrom(point.getLevel(), point.tileX, point.tileY));

      // One layer of wrapping, not two: PacketOpenContainer.ObjectEntity wraps what it is given and the registry
      // unwraps exactly one. Wrapping again here is the bug that cost a day on the bus panel.
      return PacketOpenContainer.ObjectEntity(containerID, point, content);
   }

   public static void openAndSendContainer(
      int containerID, ServerClient client, Level level, ArcaneAccessPointObjectEntity point
   ) {
      if (!level.isServer()) {
         throw new IllegalStateException("Level must be a server level");
      }

      point.invalidate();
      ContainerRegistry.openAndSendContainer(client, openPacket(containerID, point));
   }

   /**
    * Tunes the Access Point, or reports why not.
    *
    * <p>The server re-decides everything: the band may have gone, the channel may have been taken by someone else's
    * click a moment earlier, and the range is not the client's to check. What the client sends is a request.
    */
   public class TuneAction extends ContainerCustomAction {

      public void runAndSend(int bandId, int channel) {
         Packet content = new Packet();
         PacketWriter writer = new PacketWriter(content);
         writer.putNextShortUnsigned(Math.max(0, bandId));
         writer.putNextShortUnsigned(Math.max(0, channel));
         this.runAndSendAction(content);
      }

      @Override
      public void executePacket(PacketReader reader) {
         int bandId = reader.getNextShortUnsigned();
         int channel = reader.getNextShortUnsigned();
         if (!AccessPointContainer.this.client.isServer()) {
            return;
         }

         ArcaneAccessPointObjectEntity point = AccessPointContainer.this.point;
         if (point == null) {
            return;
         }

         String refusal = point.tune(bandId, channel);
         if (refusal != null) {
            AccessPointContainer.this.rejectAction.runAndSend(Localization.translate("ui", refusal));
         }
      }
   }

   /** The server's answer when a tuning is refused, reaching only the player who tried. */
   public class RejectAction extends ContainerCustomAction {

      public void runAndSend(String reason) {
         Packet content = new Packet();
         new PacketWriter(content).putNextString(reason);
         this.runAndSendAction(content);
      }

      @Override
      public void executePacket(PacketReader reader) {
         String reason = reader.getNextString();
         if (!AccessPointContainer.this.client.isServer()) {
            AccessPointContainer.this.refusal = reason;
         }
      }
   }

   /** Renames it. A label, so it applies on arrival and cannot be refused -- as a bus's name is. */
   public class SetNameAction extends ContainerCustomAction {

      public void runAndSend(String name) {
         Packet content = new Packet();
         new PacketWriter(content).putNextString(name);
         this.runAndSendAction(content);
      }

      @Override
      public void executePacket(PacketReader reader) {
         String name = reader.getNextString();
         if (AccessPointContainer.this.client.isServer() && AccessPointContainer.this.point != null) {
            AccessPointContainer.this.point.setCustomName(name);
         }
      }
   }
}
