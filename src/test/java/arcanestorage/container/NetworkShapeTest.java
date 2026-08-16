package arcanestorage.container;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import necesse.engine.network.Packet;
import necesse.engine.network.PacketReader;
import java.util.List;
import org.junit.Test;
import arcanestorage.network.NetworkStations;
import arcanestorage.network.NetworkStorage;

/**
 * The packet the server sends a client that is opening a terminal, and the stand-ins built from it.
 *
 * <p>Worth testing here rather than in a scenario because the scenario harness has no client: it drives a server, so
 * the entire failure this shape exists to fix -- a client computing a shorter network than the server -- is invisible
 * to it. What can be checked without a game is that the bytes survive the round trip and that an unresolvable member
 * becomes a stand-in of the right size rather than being dropped, which is exactly how the slot indices used to shift.
 */
public class NetworkShapeTest {

   private static NetworkShape roundTrip(NetworkShape shape) {
      Packet packet = shape.toPacket();
      return NetworkShape.fromPacket(new PacketReader(packet));
   }

   @Test
   public void tilesAndSizesSurviveThePacket() {
      NetworkShape shape = new NetworkShape(
         new long[] {tile(3, 4)}, new int[] {6},
         new long[] {tile(-12, 900), tile(0, 0)}, new int[] {40, 20});

      NetworkShape read = roundTrip(shape);

      assertEquals(1, read.socketCounts.length);
      assertEquals(6, read.socketCounts[0]);
      assertEquals(tile(3, 4), read.stationTiles[0]);

      assertEquals(2, read.unitSizes.length);
      assertEquals(40, read.unitSizes[0]);
      assertEquals(20, read.unitSizes[1]);
      assertEquals("a negative tile must survive, since a base can sit west or north of the origin",
         tile(-12, 900), read.unitTiles[0]);
   }

   @Test
   public void anEmptyNetworkIsStillAValidShape() {
      NetworkShape read = roundTrip(new NetworkShape(new long[0], new int[0], new long[0], new int[0]));

      assertEquals(0, read.socketCounts.length);
      assertEquals(0, read.unitSizes.length);
   }

   @Test
   public void anUnresolvableMemberBecomesAStandInOfTheStatedSize() {
      // A null level is the extreme of the case this exists for: nothing can be resolved, so everything stands in.
      // The sizes are what matter -- they are what fixes the slot indices -- and the count must never shrink, because
      // a missing member is what used to shift every index after it.
      NetworkShape shape = new NetworkShape(
         new long[] {tile(1, 1)}, new int[] {3},
         new long[] {tile(2, 2), tile(3, 3)}, new int[] {40, 10});

      List<NetworkStorage> units = shape.units(null, null);
      List<NetworkStations> stations = shape.stationUnits(null, null);

      assertEquals(2, units.size());
      assertEquals(40, units.get(0).getInventory().getSize());
      assertEquals(10, units.get(1).getInventory().getSize());
      assertEquals(1, stations.size());
      assertEquals(3, stations.get(0).getInventory().getSize());

      assertTrue("a stand-in must report itself on the network, or the container closes the instant it opens",
         units.get(0).isOnNetwork());
      assertEquals("tile order comes from the sent order, which is what makes an index mean the same thing on "
         + "both sides", 0L, stations.get(0).tileOrder());
   }

   @Test
   public void aZeroTileIsNeverResolved() {
      // of() writes a zero tile for a member whose entity is missing. It must not be read as tile (0,0), which is a
      // real tile somebody's base could stand on.
      NetworkShape shape = new NetworkShape(new long[0], new int[0], new long[] {0L}, new int[] {40});

      assertEquals(1, shape.units(null, null).size());
      assertEquals(40, shape.units(null, null).get(0).getInventory().getSize());
   }

   private static long tile(int x, int y) {
      return (long)x << 32 | (long)y & 0xFFFFFFFFL;
   }
}
