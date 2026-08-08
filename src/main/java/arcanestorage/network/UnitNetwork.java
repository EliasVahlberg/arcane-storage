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

   /** Orthogonal neighbours, in a fixed order. Changing this order changes slot ordering. */
   static final int[][] NEIGHBOURS = {{0, -1}, {1, 0}, {0, 1}, {-1, 0}};

   /** Resolves a tile to a unit, or {@code null} when that tile holds none. */
   public interface UnitLookup<T> {
      T unitAt(int x, int y);
   }

   private UnitNetwork() {
   }

   /**
    * Every unit reachable from {@code (startX, startY)} through orthogonally connected
    * units, breadth-first, capped at {@code maxUnits}.
    *
    * <p>The starting tile is never itself reported, even if the lookup would resolve it.
    *
    * @param maxUnits hard ceiling on the number of units returned; bounds both the
    *                 container's slot count and the work done on a pathological layout
    */
   public static <T> List<T> discover(int startX, int startY, UnitLookup<T> lookup, int maxUnits) {
      List<T> found = new ArrayList<>();
      if (maxUnits <= 0) {
         return found;
      }

      Set<Long> seen = new HashSet<>();
      Deque<int[]> frontier = new ArrayDeque<>();

      // Marking the start as seen is what stops the walk stepping back onto the terminal.
      seen.add(key(startX, startY));
      expand(startX, startY, seen, frontier);

      while (!frontier.isEmpty() && found.size() < maxUnits) {
         int[] tile = frontier.poll();
         T unit = lookup.unitAt(tile[0], tile[1]);
         if (unit == null) {
            continue;
         }

         found.add(unit);
         expand(tile[0], tile[1], seen, frontier);
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

   /** Packs a tile into one long so the visited set needs no allocation per lookup. */
   private static long key(int x, int y) {
      return (long)x << 32 | (long)y & 0xFFFFFFFFL;
   }
}
