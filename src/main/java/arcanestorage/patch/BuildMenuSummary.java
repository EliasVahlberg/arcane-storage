package arcanestorage.patch;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import arcanestorage.network.NetworkStorage;
import arcanestorage.remote.RemoteTerminal;
import necesse.engine.network.gameNetworkData.GNDItemMap;
import necesse.engine.network.server.Server;
import necesse.engine.network.server.ServerClient;
import necesse.entity.mobs.PlayerMob;
import necesse.inventory.Inventory;
import necesse.inventory.InventoryItem;

/**
 * Builds and sends Build Menu's compact network summary to each connected player.
 *
 * <p>The server half of the client leg: for a player who passes the connected rule (a paired
 * wireless terminal that resolves), the network's contents are reduced to one stack per distinct
 * item and sent on {@link PacketBuildMenuNetworkSummary}. The client then counts from that summary
 * for its "can afford" display; the server keeps consuming from the real units via the placement
 * patch.
 *
 * <p><b>Cadence.</b> {@link #tick} runs on every server tick but does work only once every
 * {@link #INTERVAL_MS}, matching Build Menu's own snapshot cadence. The sweep is cheap enough to
 * not need the change hook: resolving and summarising a connected player's network costs on the
 * order of tens of microseconds (measured for the placement patch), and a player with no terminal
 * costs a scan of their own bag. Polling players at 2 Hz is cheaper than wiring the change hook into
 * this path, and it also catches the login case, where a paired terminal is present but no inventory
 * event has fired since.
 */
public final class BuildMenuSummary {

   private BuildMenuSummary() {
   }

   /** Matches Build Menu's own snapshot cadence. */
   private static final long INTERVAL_MS = 500L;

   /** The last summary signature each connected client was sent, so an unchanged one is not resent. */
   private static final Map<ServerClient, Integer> LAST_SENT = new HashMap<>();

   private static volatile long lastSweepMs;

   /** How many summary packets have been sent. Diagnostic, and the throttle test's evidence. */
   private static volatile long packetsSent;

   public static long packetsSent() {
      return packetsSent;
   }

   /** Drops per-client and throttle state, for the harness between scenarios. */
   public static void forget() {
      LAST_SENT.clear();
      lastSweepMs = 0L;
      packetsSent = 0L;
   }

   /** Called every server tick; sweeps at most once per {@link #INTERVAL_MS}. */
   public static void tick(Server server) {
      if (server == null) {
         return;
      }

      long now = System.currentTimeMillis();
      if (!shouldSweep(now, lastSweepMs)) {
         return;
      }

      lastSweepMs = now;
      sweep(server);
   }

   /** Whether a sweep is due. Extracted so the cadence is a unit test rather than a wait. */
   static boolean shouldSweep(long now, long lastSweepMs) {
      return now - lastSweepMs >= INTERVAL_MS;
   }

   /** Forces a sweep, ignoring the throttle. For the harness, which runs faster than wall-clock. */
   public static void sweepNow(Server server) {
      sweep(server);
   }

   private static void sweep(Server server) {
      for (ServerClient client : server.getClients()) {
         PlayerMob player = client.playerMob;
         if (player == null) {
            continue;
         }

         RemoteTerminal.Resolved resolved = resolveConnected(client, player);
         if (!resolved.ok()) {
            // Stopped being connected: clear the client's cached view once.
            if (LAST_SENT.remove(client) != null) {
               send(client, new Inventory(0));
            }
            continue;
         }

         Inventory summary = summarize(resolved.terminal.getLinkedUnits());
         int signature = signature(summary);
         Integer previous = LAST_SENT.get(client);
         if (previous == null || previous != signature) {
            LAST_SENT.put(client, signature);
            send(client, summary);
         }
      }
   }

   /** Resolves a player's paired terminal, server-side. A null binding resolves to UNPAIRED. */
   private static RemoteTerminal.Resolved resolveConnected(ServerClient client, PlayerMob player) {
      return RemoteTerminal.resolve(client, BuildMenuCompat.findPairedBinding(player));
   }

   /** The compact summary for a player's paired network, or null when not connected. For tests. */
   public static Inventory summaryFor(ServerClient client, PlayerMob player) {
      RemoteTerminal.Resolved resolved = resolveConnected(client, player);
      if (!resolved.ok()) {
         return null;
      }

      return summarize(resolved.terminal.getLinkedUnits());
   }

   private static void send(ServerClient client, Inventory summary) {
      packetsSent++;
      client.sendPacket(new PacketBuildMenuNetworkSummary(summary));
   }

   /**
    * One stack per distinct item -- distinct by item and saved data, so two variants stay apart --
    * with the summed count.
    */
   public static Inventory summarize(List<NetworkStorage> units) {
      List<InventoryItem> distinct = new ArrayList<>();

      for (NetworkStorage unit : units) {
         Inventory inventory = unit.getInventory();
         if (inventory == null) {
            continue;
         }

         for (int slot = 0; slot < inventory.getSize(); slot++) {
            InventoryItem item = inventory.getItem(slot);
            if (item == null || item.item == null || item.getAmount() <= 0) {
               continue;
            }

            mergeInto(distinct, item);
         }
      }

      Inventory summary = new Inventory(distinct.size());
      for (int i = 0; i < distinct.size(); i++) {
         summary.setItem(i, distinct.get(i));
      }

      return summary;
   }

   /** Adds an item's amount to the matching distinct entry, or appends a fresh one. */
   private static void mergeInto(List<InventoryItem> distinct, InventoryItem item) {
      for (InventoryItem existing : distinct) {
         if (existing.item == item.item
               && GNDItemMap.sameKeys(existing.getGndData(), item.getGndData())) {
            existing.setAmount(existing.getAmount() + item.getAmount());
            return;
         }
      }

      distinct.add(item.copy(item.getAmount()));
   }

   /** A deterministic change detector over the summary's counting-relevant contents. */
   private static int signature(Inventory summary) {
      int hash = 1;
      for (int slot = 0; slot < summary.getSize(); slot++) {
         InventoryItem item = summary.getItem(slot);
         if (item != null && item.item != null) {
            hash = hash * 31 + item.item.getID();
            hash = hash * 31 + item.getAmount();
         }
      }

      return hash;
   }
}
