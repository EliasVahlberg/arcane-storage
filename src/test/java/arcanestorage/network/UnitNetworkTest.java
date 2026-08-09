package arcanestorage.network;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.HashSet;
import java.util.List;

import org.junit.Test;

/**
 * Tests for the network walk.
 *
 * <p>Layouts are written as string art, where {@code T} is the terminal's tile, {@code U}
 * is a storage unit and {@code .} is anything else. Row 0 is the top, so {@code y}
 * increases downward, matching the game's tile coordinates.
 */
public class UnitNetworkTest {

   /** A lookup over string art. Returns "x,y" for a unit tile, null otherwise. */
   private static UnitNetwork.UnitLookup<String> grid(final String... rows) {
      return (x, y) -> {
         if (y < 0 || y >= rows.length || x < 0 || x >= rows[y].length()) {
            return null;
         }

         return rows[y].charAt(x) == 'U' ? x + "," + y : null;
      };
   }

   private static List<String> discover(int startX, int startY, String... rows) {
      return UnitNetwork.discover(startX, startY, grid(rows), 64);
   }

   /** A conductor test over the same string art, where {@code C} is a conduit. */
   private static UnitNetwork.ConductorTest conduits(final String... rows) {
      return (x, y) -> y >= 0 && y < rows.length && x >= 0 && x < rows[y].length() && rows[y].charAt(x) == 'C';
   }

   private static List<String> discoverWithConduits(int startX, int startY, int maxConduits, String... rows) {
      return UnitNetwork.discover(startX, startY, grid(rows), conduits(rows), 64, maxConduits);
   }

   @Test
   public void conduitsCarryTheNetworkWithoutAddingCapacity() {
      // Two units the terminal could never reach on its own, joined by a run of conduits.
      List<String> found = discoverWithConduits(0, 0, 256, "TCCCUU");
      assertEquals("conduits must not be reported as units", 2, found.size());
      assertTrue(found.contains("4,0"));
      assertTrue(found.contains("5,0"));
   }

   @Test
   public void conduitsDoNotBridgeAGap() {
      // A conduit conducts, it does not teleport: one empty tile still severs the network.
      assertEquals(0, discoverWithConduits(0, 0, 256, "TC.CU").size());
   }

   @Test
   public void conduitsAreBoundedIndependentlyOfUnits() {
      // The cap is what stops a cheap run of conduits making discovery arbitrarily expensive.
      assertEquals(1, discoverWithConduits(0, 0, 3, "TCCCU").size());
      assertEquals(0, discoverWithConduits(0, 0, 2, "TCCCU").size());
      assertEquals(0, discoverWithConduits(0, 0, 0, "TCU").size());
   }

   @Test
   public void aConduitBranchReachesUnitsOnBothSides() {
      // Branching is the reason conduits exist: routing a network around a base.
      List<String> found = discoverWithConduits(1, 0, 256,
         ".T.",
         "UCU");
      assertEquals(2, found.size());
   }

   @Test
   public void ignoresConduitsWhenNoneAreAllowed() {
      // The short overload means "no conductors", which is what the pre-conduit behaviour was.
      assertEquals(0, UnitNetwork.discover(0, 0, grid("TCCU"), 64).size());
   }

   @Test
   public void findsNothingWhenAloneOrUnreachable() {
      assertEquals(0, discover(0, 0, "T....").size());
      // Separated by one empty tile: must not bridge the gap.
      assertEquals(0, discover(0, 0, "T.UUU").size());
   }

   @Test
   public void findsAdjacentUnitsOnAllFourSides() {
      List<String> found = discover(1, 1,
         ".U.",
         "UTU",
         ".U.");
      assertEquals(4, found.size());
   }

   @Test
   public void followsAChainAwayFromTheTerminal() {
      // The whole point of Phase 2: units linked to units, not just to the terminal.
      assertEquals(4, discover(0, 0, "TUUUU").size());
      assertEquals(9, discover(0, 0, "TUUUUUUUUU").size());
   }

   @Test
   public void followsAChainAroundACorner() {
      List<String> found = discover(0, 0,
         "TU..",
         ".U..",
         ".UUU");
      assertEquals(5, found.size());
   }

   @Test
   public void ignoresDiagonallyTouchingUnits() {
      // Connectivity is orthogonal. A diagonal neighbour is a separate network.
      assertEquals(0, discover(0, 0,
         "T.",
         ".U").size());

      // Reachable orthogonally, but the diagonal one is only reachable via the diagonal.
      assertEquals(1, discover(0, 0,
         "TU",
         "..").size());
   }

   @Test
   public void reportsEachUnitExactlyOnceInABlock() {
      // A 2x2 block gives two paths to the far corner. Visiting it twice would register
      // its slots twice and double-count its contents, which looks like duplication.
      List<String> found = discover(0, 0,
         "TUU",
         ".UU");
      assertEquals(4, found.size());
      assertEquals("no unit reported twice", found.size(), new HashSet<>(found).size());
   }

   @Test
   public void reportsEachUnitOnceInALoop() {
      // A ring of units around a hole: every unit has two paths back to the terminal.
      List<String> found = discover(0, 0,
         "TUUU",
         ".U.U",
         ".UUU");
      assertEquals(8, found.size());
      assertEquals("no unit reported twice", found.size(), new HashSet<>(found).size());
   }

   @Test
   public void neverReportsTheStartingTile() {
      // Even if the start tile itself looks like a unit, it is the entry point, not a member.
      List<String> found = UnitNetwork.discover(0, 0, grid("UU"), 64);
      assertEquals(1, found.size());
      assertEquals("1,0", found.get(0));
   }

   @Test
   public void respectsTheUnitCap() {
      assertEquals(3, UnitNetwork.discover(0, 0, grid("TUUUUUUUU"), 3).size());
      assertEquals(0, UnitNetwork.discover(0, 0, grid("TUUUUUUUU"), 0).size());
      assertEquals(0, UnitNetwork.discover(0, 0, grid("TUUUUUUUU"), -1).size());
   }

   @Test
   public void isDeterministic() {
      // Slot indices are assigned in this order, so an unstable order would mean a client
      // and server disagreeing about which unit a slot belongs to.
      String[] layout = {
         "TUUU",
         ".UUU",
         ".U.U"};
      List<String> first = discover(0, 0, layout);
      for (int run = 0; run < 25; run++) {
         assertEquals(first, discover(0, 0, layout));
      }
   }

   @Test
   public void findsUnitsAtNegativeCoordinates() {
      // Tile coordinates are not guaranteed positive, and the visited set packs them into
      // a long — a sign-extension mistake there would collide distinct tiles.
      UnitNetwork.UnitLookup<String> lookup = (x, y) -> x >= -3 && x <= -1 && y == -5 ? x + "," + y : null;
      List<String> found = UnitNetwork.discover(0, -5, lookup, 64);
      assertEquals(3, found.size());
      assertTrue(found.contains("-3,-5"));
   }
}
