package arcanestorage.network;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Walks a network of connected storage units outward from a starting tile.
 *
 * <p>Deliberately knows nothing about Necesse: it takes a lookup from tile coordinates to
 * whatever the caller considers a unit, and returns the ones it reaches. That keeps the
 * part most likely to contain an off-by-one or a missed visited-check testable without
 * launching the game.
 *
 * <p>Three properties are load-bearing rather than incidental:
 *
 * <ul>
 *   <li><b>Units conduct; the starting tile does not.</b> The search spreads only from
 *       tiles that hold a unit, so a terminal is an entry point and never a bridge
 *       between two separate groups of units.
 *   <li><b>A tile is enqueued at most once.</b> This is a correctness requirement, not an
 *       optimisation: a 2x2 block of units offers two paths to the same unit, and visiting
 *       it twice would register its slots twice and make the aggregated view double-count
 *       its contents — which reads to a player as item duplication.
 *   <li><b>Order is deterministic</b> — a fixed neighbour order and a FIFO frontier — so
 *       the same layout always produces the same list in the same order.
 * </ul>
 *
 * <p>Connectivity is orthogonal only. Diagonally touching units are separate networks.
 */
public final class UnitNetwork {

   /**
    * Orthogonal neighbours, in a fixed order. Changing this order changes slot ordering.
    *
    * <p>Public because everything that reasons about adjacency must use this one order or the mod will
    * contradict itself: the conduit's sprite mask indexes frames by it, and a bus looks for its container
    * with it.
    */
   public static final int[][] NEIGHBOURS = {{0, -1}, {1, 0}, {0, 1}, {-1, 0}};

   /** Resolves a tile to a unit, or {@code null} when that tile holds none. */
   public interface UnitLookup<T> {
      T unitAt(int x, int y);
   }

   /** Whether a tile carries the network onward without holding items itself. */
   public interface ConductorTest {
      boolean conductsAt(int x, int y);
   }

   /**
    * Tiles the network continues from that are not neighbours of this one.
    *
    * <p>How a frequency band joins two clusters: an Arcane Base Station links to its Access Points and each of them
    * links back. Everything downstream -- capacity, buses, crafting, the terminal's view -- then works on a bridged
    * network without knowing one exists, which is the requirement this hook was added for.
    *
    * <p><b>Both directions have to be reported, or the mod contradicts itself.</b> A network is named by its lowest
    * member tile and every member is expected to discover the same set, so that all of them share one index. If a
    * link were one-way, a bus in a silo would compute a smaller network than the terminal does, the two would build
    * separate indexes over overlapping units, and the same items would be counted twice.
    *
    * @return packed {@code x << 32 | y} tiles, or null when this tile links nowhere. Null rather than an empty array
    *     because this is asked about every conducting tile of every walk.
    */
   public interface LinkLookup {
      long[] linksFrom(int x, int y);
   }

   private UnitNetwork() {
   }

   /**
    * Units reachable through other units only, with nothing conducting between them.
    */
   public static <T> List<T> discover(int startX, int startY, UnitLookup<T> lookup, int maxUnits) {
      return discover(startX, startY, lookup, (x, y) -> false, maxUnits, 0);
   }

   /**
    * Every unit reachable from {@code (startX, startY)} through orthogonally connected units
    * and conductors, breadth-first.
    *
    * <p>The starting tile is never itself reported, even if the lookup would resolve it.
    *
    * <p>Conductors extend reach without adding capacity, so a network can be routed around a
    * base instead of being one solid block of units. They are counted separately and capped
    * separately: {@code maxUnits} bounds the container's slot count, while
    * {@code maxConductors} bounds the distance walked. Without the second cap a long run of
    * cheap conductors would make the traversal arbitrarily expensive even though the network
    * holds nothing.
    *
    * @param maxUnits      hard ceiling on the number of units returned
    * @param maxConductors hard ceiling on conducting tiles walked through
    */
   public static <T> List<T> discover(
      int startX, int startY, UnitLookup<T> lookup, ConductorTest conducts, int maxUnits, int maxConductors
   ) {
      return discover(startX, startY, lookup, conducts, null, maxUnits, maxConductors, 0);
   }

   /**
    * Every unit reachable through connected units, conductors, and any links the conductors carry.
    *
    * @param links       consulted only at conducting tiles, or null for a walk that does not cross bands
    * @param maxLinks    hard ceiling on links followed, bounding a walk over a pathological band table
    */
   public static <T> List<T> discover(
      int startX, int startY, UnitLookup<T> lookup, ConductorTest conducts, LinkLookup links,
      int maxUnits, int maxConductors, int maxLinks
   ) {
      List<T> found = new ArrayList<>();
      if (maxUnits <= 0) {
         return found;
      }

      Set<Long> seen = new HashSet<>();
      Deque<int[]> frontier = new ArrayDeque<>();
      int conductors = 0;
      int linksFollowed = 0;

      // Marking the start as seen is what stops the walk stepping back onto the terminal.
      seen.add(key(startX, startY));
      expand(startX, startY, seen, frontier);

      while (!frontier.isEmpty() && found.size() < maxUnits) {
         int[] tile = frontier.poll();
         T unit = lookup.unitAt(tile[0], tile[1]);

         if (unit != null) {
            found.add(unit);
            expand(tile[0], tile[1], seen, frontier);
            continue;
         }

         // A conductor carries the walk onward but is never reported, so capacity stays a
         // property of units alone.
         if (conductors < maxConductors && conducts.conductsAt(tile[0], tile[1])) {
            conductors++;
            expand(tile[0], tile[1], seen, frontier);

            // Links are asked for at conducting tiles only, which costs nothing to enforce -- everything this mod
            // places conducts except the terminal -- and keeps the lookup off the hot path of ordinary tiles. The
            // linked tile is enqueued rather than expanded: it is a device in its own right, and it is reached
            // exactly as though it had been a neighbour.
            if (links != null && linksFollowed < maxLinks) {
               long[] linked = links.linksFrom(tile[0], tile[1]);
               if (linked != null) {
                  for (long target : linked) {
                     if (linksFollowed >= maxLinks) {
                        break;
                     }

                     int lx = (int)(target >> 32);
                     int ly = (int)target;
                     if (seen.add(key(lx, ly))) {
                        frontier.add(new int[]{lx, ly});
                        linksFollowed++;
                     }
                  }
               }
            }
         }
      }

      return found;
   }

   /** Queues the neighbours of a tile, skipping any already queued or visited. */
   private static void expand(int x, int y, Set<Long> seen, Deque<int[]> frontier) {
      for (int[] offset : NEIGHBOURS) {
         int nx = x + offset[0];
         int ny = y + offset[1];
         if (seen.add(key(nx, ny))) {
            frontier.add(new int[]{nx, ny});
         }
      }
   }

   /**
    * A tile as one comparable number, row-major, matching {@code DeviceOnNetwork.tileOrder}.
    *
    * <p>Deliberately a different packing from {@link #key}: this one sorts into reading order, which is what makes a
    * network's member list stable and its leader election agree between devices. The visited set does not care about
    * order, so it uses the cheaper column-major packing.
    */
   public static long order(int x, int y) {
      return (long)y << 32 | (long)x & 0xFFFFFFFFL;
   }

   /** Packs a tile into one long so the visited set needs no allocation per lookup. */
   private static long key(int x, int y) {
      return (long)x << 32 | (long)y & 0xFFFFFFFFL;
   }
}
