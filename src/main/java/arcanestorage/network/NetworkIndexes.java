package arcanestorage.network;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

import necesse.entity.objectEntity.ObjectEntity;
import necesse.level.maps.Level;

/**
 * The live {@link NetworkIndex} for each network, so devices on one network share one copy.
 *
 * <p><b>The point is not caching, it is sharing.</b> Two buses on the same network are asking the same
 * question about the same items, and before this each answered it privately: measured, five network walks and
 * two hundred slot scans per hundred idle ticks with two buses and one unit. Sharing makes the cost a property
 * of the network rather than of how many devices watch it, which is the property that has to hold before a
 * network can be worth building out.
 *
 * <p><b>Identity of a network.</b> A network is a set of connected units with no object of its own, so it is
 * named by its lowest-ordered member tile. Every device on it discovers the same set -- connectivity is
 * symmetric -- so they all arrive at the same name without agreeing on one in advance. Nothing is stored in
 * the world: a network that loses its last unit simply stops being named, and the entry ages out.
 *
 * <p><b>Two ways a shared copy goes wrong, and both are handled.</b> The layout can change, so any place or
 * break of a network object bumps {@link #topologyChanged()} and every index built under the old version is
 * refused. And the contents can change behind us, which until step 3's change hook lands is bounded by
 * {@link NetworkIndex#FRESH_FOR_TICKS} -- the same one second a bus already waited between recounts, so a
 * decision from a shared copy is never staler than one the old code made.
 *
 * <p>Server-side only. The client is told what to draw and never asks what a network holds, so an index on a
 * client would be a second truth with nothing to keep it honest.
 */
public final class NetworkIndexes {

   /**
    * Weak on the level, because a level is unloaded when nobody is on it and its networks go with it.
    *
    * <p>A strong map here would keep an entire {@code Level} -- its regions, entities and inventories -- alive
    * for as long as the process ran, which is the classic way a cache turns into a leak.
    */
   private static final WeakHashMap<Level, HashMap<Long, NetworkIndex>> BY_LEVEL = new WeakHashMap<>();

   /**
    * Bumped whenever the shape of any network may have changed.
    *
    * <p>One counter for every level rather than one each: the cost of a false invalidation is a rebuild that
    * would have happened within the second anyway, and the cost of a missed one is a device acting on a
    * network it has left. Global and coarse is the safe direction, and placing objects is rare.
    */
   private static long topologyVersion;

   private NetworkIndexes() {
   }

   /** Called when a network object is placed or broken, or when membership may otherwise have changed. */
   public static void topologyChanged() {
      topologyVersion++;
   }

   /** The current topology version, for anything holding a build under it. */
   public static long topologyVersion() {
      return topologyVersion;
   }

   /**
    * The shared index for the network a device has just walked.
    *
    * <p>The caller does the walk, because only it knows where to start and what counts as a member. What this
    * adds is that the second caller within the freshness window gets the first one's answer.
    *
    * @param units the members, as discovered
    * @param devices our object entities standing on the network, as discovered
    */
   public static NetworkIndex share(Level level, List<NetworkStorage> units, List<ObjectEntity> devices) {
      if (units.isEmpty()) {
         return null;
      }

      long tick = tickOf(level);
      long key = nameOf(units);
      HashMap<Long, NetworkIndex> indexes = BY_LEVEL.computeIfAbsent(level, l -> new HashMap<>());

      NetworkIndex existing = indexes.get(key);
      if (existing != null && existing.isFresh(tick, topologyVersion)) {
         return existing;
      }

      if (existing != null) {
         // Rebuilt in place rather than replaced, so that every device already holding a reference follows
         // along. A replacement would leave them each walking to find the new one, which is the cost this
         // class exists to remove.
         existing.rebuild(units, devices, tick, topologyVersion);
         return existing;
      }

      NetworkIndex built = new NetworkIndex(units, devices, tick, topologyVersion);
      indexes.put(key, built);
      return built;
   }

   /**
    * Whether a device may reuse the index it already holds, without walking to check.
    *
    * <p>This is the whole saving: a device that walked once keeps its reference, and while the reference is
    * fresh -- which another device on the same network may have refreshed on its behalf -- it does no work at
    * all.
    */
   public static boolean stillGood(Level level, NetworkIndex index, ObjectEntity device) {
      return index != null
         && index.isFresh(tickOf(level), topologyVersion)
         && index.holds(device);
   }

   /** Runs an index's periodic self-check, if it is due one. */
   public static void reconcile(Level level, NetworkIndex index) {
      if (index != null) {
         index.reconcile(tickOf(level));
      }
   }

   /**
    * Asks every network on a level to check itself next time it is used.
    *
    * <p>Called when a terminal opens, which is both the moment a player is most likely to notice a wrong
    * number and the moment they are about to act on one.
    */
   public static void reconcileSoon(Level level) {
      for (NetworkIndex index : on(level)) {
         index.reconcileSoon();
      }
   }

   /**
    * A network's name: the lowest tile order among its members.
    *
    * <p>Order, not position, so it is stable under discovery order. Any device on the network computes the
    * same value from its own walk.
    */
   private static long nameOf(List<NetworkStorage> units) {
      long lowest = Long.MAX_VALUE;

      for (NetworkStorage unit : units) {
         ObjectEntity entity = unit.getObjectEntity();
         if (entity == null) {
            continue;
         }

         long order = ((long)entity.tileY << 32) | (entity.tileX & 0xFFFFFFFFL);
         if (order < lowest) {
            lowest = order;
         }
      }

      return lowest;
   }

   /**
    * The server's tick count.
    *
    * <p>{@code WorldEntity.getGameTicks()} rather than wall-clock time or a counter of our own: it advances
    * once per tick, it is the same number on every level, and a paused server does not advance it -- so a
    * freshness window measured in it cannot expire while nothing is happening.
    */
   private static long tickOf(Level level) {
      return level == null || level.getWorldEntity() == null ? 0L : level.getWorldEntity().getGameTicks();
   }

   /** Drops every index. For the harness, whose scenarios reuse one server across worlds. */
   public static void forget() {
      BY_LEVEL.clear();
      IndexedInventories.forget();
      topologyChanged();
   }

   /** How many networks are indexed on a level. For diagnosis. */
   public static int indexedOn(Level level) {
      HashMap<Long, NetworkIndex> indexes = BY_LEVEL.get(level);
      return indexes == null ? 0 : indexes.size();
   }

   /** Every index on a level, for reconciliation and diagnosis. */
   public static List<NetworkIndex> on(Level level) {
      HashMap<Long, NetworkIndex> indexes = BY_LEVEL.get(level);
      return indexes == null ? new ArrayList<>() : new ArrayList<>(indexes.values());
   }
}
