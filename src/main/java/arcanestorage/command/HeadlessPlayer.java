package arcanestorage.command;

import java.util.Collections;

import necesse.engine.GameInfo;
import necesse.engine.commands.CommandLog;
import necesse.engine.network.networkInfo.InvalidNetworkInfo;
import necesse.engine.network.packet.PacketClientInstalledDLC;
import necesse.engine.network.packet.PacketDisconnect;
import necesse.engine.network.packet.PacketPlayerAppearance;
import necesse.engine.network.server.Server;
import necesse.engine.network.server.ServerClient;
import necesse.level.maps.Level;

/**
 * A player on a dedicated server that has no player.
 *
 * <p>Half of this mod is a container UI, and a container is built from a player's inventory, so
 * the scenarios that exercise withdraw, deposit, quick stack and restock could only ever be run
 * by hand from chat. That is the least reliable part of the test suite: it is the part a person
 * has to remember to do.
 *
 * <p>The engine turns out to allow this. {@code NetworkInfo} is a four-method abstract class and
 * the game already ships {@link InvalidNetworkInfo}, whose {@code send} discards its bytes -- so a
 * client with no socket is a state the engine supports rather than a hole being punched in it.
 * {@code OneWorldMigration} constructs a {@code ServerClient} with a null {@code NetworkInfo}
 * during save migration, and singleplayer's local client legitimately has one too, which is why
 * {@code ServerClient.onFirstJoined} tests for {@code networkInfo == null}.
 *
 * <p>Everything outbound is therefore thrown away, which is correct: nothing is listening. What
 * matters is that inbound work runs the same server-side code a real client's packets would.
 */
public final class HeadlessPlayer {

   /**
    * An arbitrary authentication ID. It only has to be stable, so the player file is reused across
    * boots rather than a new character being created every run, and distinctive enough to be
    * obvious in a log or a save directory.
    */
   private static final long AUTH = 7723001L;

   private static ServerClient client;

   private HeadlessPlayer() {
   }

   /** The synthetic client, or null if none has been spawned. */
   public static ServerClient current() {
      return client != null && client.isServerClient() ? client : null;
   }

   public static ServerClient spawn(Server server, Level level, CommandLog logs) {
      if (current() != null) {
         logs.add("a headless player is already connected");
         return client;
      }

      // The version is compared by string in Server.addClient, so read the game's own constant
      // rather than writing "1.3.2" here -- a mismatch after an update would show up as a
      // rejected connection with no obvious cause.
      boolean added = server.addClient(
            new InvalidNetworkInfo(),
            AUTH,
            GameInfo.version,
            false,
            false,
            new PacketClientInstalledDLC(0, Collections.emptyList()));

      if (!added) {
         logs.add("FAIL the server refused the headless client; see the log above for its reason");
         return null;
      }

      ServerClient spawned = server.getClientByAuth(AUTH);
      if (spawned == null) {
         logs.add("FAIL the server accepted the headless client but no client exists for its auth");
         return null;
      }

      // A new character is created with needAppearance set, and the server has a
      // MISSING_APPEARANCE disconnect code for clients that never submit one. A real client
      // submits it from the character screen; here the default look the PlayerMob was
      // constructed with is already valid, so it is handed straight back.
      if (spawned.needAppearance()) {
         spawned.applyAppearancePacket(new PacketPlayerAppearance(spawned));
      }

      // A real client answers PacketConnectApproved by loading and then asking to be placed.
      // Nothing is going to answer here, so do that part directly.
      spawned.changeLevel(level.getIdentifier());

      client = spawned;
      logs.add("headless player '" + spawned.getName() + "' connected"
            + ", appearance " + (spawned.needAppearance() ? "MISSING" : "ok")
            + ", level " + level.getIdentifier()
            + ", mob " + (spawned.playerMob == null ? "null"
                  : (spawned.playerMob.getLevel() == null ? "no level" : "in level")));
      return client;
   }

   public static void despawn(Server server, CommandLog logs) {
      if (current() == null) {
         logs.add("no headless player to disconnect");
         return;
      }
      server.disconnectClient(client, PacketDisconnect.Code.SERVER_STOPPED);
      logs.add("headless player disconnected");
      client = null;
   }
}
