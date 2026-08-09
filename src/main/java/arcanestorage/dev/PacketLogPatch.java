package arcanestorage.dev;

import necesse.engine.modLoader.annotations.ModMethodPatch;
import necesse.engine.network.NetworkPacket;
import necesse.engine.network.Packet;
import necesse.engine.network.server.Server;
import necesse.engine.network.server.ServerClient;
import net.bytebuddy.asm.Advice;

/**
 * Development-only: logs every packet the server receives from a client.
 *
 * <p>Purpose is reconnaissance, not replay. The scenario harness can drive anything that
 * does not need a player, but a container needs one, so the player-coupled half of the mod
 * is still tested by hand. Knowing exactly which packets a real click produces, in order,
 * is what makes it possible to write a command that invokes those same code paths — a
 * synthesised action reproduces intent, whereas replayed bytes carry container IDs, unique
 * seeds, slot indices and entity IDs that are only valid in the session that produced them.
 *
 * <p>Inert unless {@code -Darcanestorage.packetlog} is set, so it costs one static boolean
 * read in normal play and prints nothing in a release.
 *
 * <p>{@code NetworkPacket.processServer} is the right hook because it is where a concrete
 * {@link Packet} type is resolved, just before the packet is handled. Patches bind to exact
 * signatures, so a game update can silently break this; it is a dev tool and nothing
 * depends on it.
 */
@ModMethodPatch(
   target = NetworkPacket.class,
   name = "processServer",
   arguments = {Server.class, ServerClient.class}
)
public class PacketLogPatch {

   public static final boolean ENABLED = System.getProperty("arcanestorage.packetlog") != null;

   @Advice.OnMethodEnter
   static void onEnter(@Advice.This NetworkPacket networkPacket, @Advice.Argument(1) ServerClient client) {
      if (PacketLogPatch.ENABLED) {
         Packet packet = networkPacket.getTypePacket();
         if (packet != null) {
            // Own timestamp rather than relying on the log's: GameLog only decorates its own
            // streams, so a plain System.out line may arrive undecorated and unorderable.
            System.out.println(
               "[PKT] " + System.currentTimeMillis() % 1000000L
                  + " " + packet.getClass().getSimpleName()
                  + " slot=" + (client == null ? -1 : client.slot)
            );
         }
      }
   }
}
