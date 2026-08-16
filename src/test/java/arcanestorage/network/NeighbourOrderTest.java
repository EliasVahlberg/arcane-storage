package arcanestorage.network;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

import org.junit.Test;

/**
 * The order of {@link UnitNetwork#NEIGHBOURS} is north, east, south, west, and something visible depends on it.
 *
 * <p>Every walk in the mod uses this array, and for a walk the order is immaterial -- breadth-first over four
 * neighbours reaches the same set whichever way round they are. That is what makes reordering it look free.
 *
 * <p>It is not free. {@code BusObject} keeps sprite suffixes in a parallel array, {@code {"_n", "_e", "_s", "_w"}},
 * and {@code BusObjectEntity.attachedDirection} returns an index into this one. Swapping two entries here would
 * silently rotate every bus in every world: items would keep moving correctly, no test of behaviour would fail, and
 * the sprites would point at nothing.
 *
 * <p>So this test exists to make that a compile-and-test failure rather than something a player notices.
 */
public class NeighbourOrderTest {

   @Test
   public void neighboursAreNorthEastSouthWest() {
      assertEquals("four orthogonal neighbours", 4, UnitNetwork.NEIGHBOURS.length);

      assertArrayEquals("index 0 must be north, matching BusObject's \"_n\" sprite",
         new int[] {0, -1}, UnitNetwork.NEIGHBOURS[0]);
      assertArrayEquals("index 1 must be east, matching BusObject's \"_e\" sprite",
         new int[] {1, 0}, UnitNetwork.NEIGHBOURS[1]);
      assertArrayEquals("index 2 must be south, matching BusObject's \"_s\" sprite",
         new int[] {0, 1}, UnitNetwork.NEIGHBOURS[2]);
      assertArrayEquals("index 3 must be west, matching BusObject's \"_w\" sprite",
         new int[] {-1, 0}, UnitNetwork.NEIGHBOURS[3]);
   }
}
