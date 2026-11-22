package arcanestorage.patch;

import java.util.ArrayList;
import java.util.List;

import org.junit.Test;

import necesse.entity.mobs.PlayerMob;
import necesse.inventory.Inventory;
import necesse.inventory.InventoryRange;
import necesse.level.maps.Level;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * The signature check and the absent-mod behaviour, which are the two halves of the Build Menu
 * compat that must hold even before a real server boots.
 *
 * <p>A name-based patch of another mod's class binds to an exact signature. A Build Menu update that
 * renames or retypes a method would otherwise fail silently, so the check is a unit test rather than
 * an assumption. The stand-in classes below are the "changed signature" the headless suite cannot
 * produce, because it would need a whole second server boot against a fake jar.
 */
public class BuildMenuCompatTest {

   private static final Class<?>[] SERVER_ARGS = {Level.class, PlayerMob.class, int.class, int.class};
   private static final Class<?>[] SNAPSHOT_ARGS = {List.class};

   /** The server signature the patch expects: static, four arguments, returning a List. */
   public static final class Correct {
      public static List<InventoryRange> buildAvailabilityRanges(Level level, PlayerMob player, int x, int y) {
         return new ArrayList<InventoryRange>();
      }
   }

   /** A changed parameter list: the method name survives but the shape does not. */
   public static final class WrongParams {
      public static List<InventoryRange> buildAvailabilityRanges(Level level, PlayerMob player) {
         return new ArrayList<InventoryRange>();
      }
   }

   /** A changed return type: still a method, but not the list the caller consumes. */
   public static final class WrongReturn {
      public static Object buildAvailabilityRanges(Level level, PlayerMob player, int x, int y) {
         return new Object();
      }
   }

   /** Made instance-side: a static call on it would throw, so it must be refused. */
   public static final class NonStatic {
      public List<InventoryRange> buildAvailabilityRanges(Level level, PlayerMob player, int x, int y) {
         return new ArrayList<InventoryRange>();
      }
   }

   /** The client snapshot signature: static, one List argument, returning void. */
   public static final class CorrectSnapshot {
      public static void onStorageSnapshot(List<InventoryRange> ranges) {
      }
   }

   /** A client snapshot that returns something: it can no longer be the setter the advice expects. */
   public static final class WrongSnapshotReturn {
      public static List<InventoryRange> onStorageSnapshot(List<InventoryRange> ranges) {
         return ranges;
      }
   }

   @Test
   public void the_expected_server_signature_passes() {
      assertNull(BuildMenuCompat.signatureProblem(Correct.class, "buildAvailabilityRanges", SERVER_ARGS, List.class));
   }

   @Test
   public void a_changed_parameter_list_is_named() {
      String problem = BuildMenuCompat.signatureProblem(WrongParams.class, "buildAvailabilityRanges", SERVER_ARGS, List.class);
      assertNotNull(problem);
      assertTrue(problem, problem.contains("no buildAvailabilityRanges"));
   }

   @Test
   public void a_changed_return_type_is_named() {
      String problem = BuildMenuCompat.signatureProblem(WrongReturn.class, "buildAvailabilityRanges", SERVER_ARGS, List.class);
      assertNotNull(problem);
      assertTrue(problem, problem.contains("returns"));
   }

   @Test
   public void an_instance_method_is_named() {
      String problem = BuildMenuCompat.signatureProblem(NonStatic.class, "buildAvailabilityRanges", SERVER_ARGS, List.class);
      assertNotNull(problem);
      assertTrue(problem, problem.contains("not static"));
   }

   @Test
   public void the_expected_snapshot_signature_passes() {
      assertNull(BuildMenuCompat.signatureProblem(CorrectSnapshot.class, "onStorageSnapshot", SNAPSHOT_ARGS, null));
   }

   @Test
   public void a_snapshot_that_returns_is_named() {
      String problem = BuildMenuCompat.signatureProblem(WrongSnapshotReturn.class, "onStorageSnapshot", SNAPSHOT_ARGS, null);
      assertNotNull(problem);
      assertTrue(problem, problem.contains("returns"));
   }

   @Test
   public void install_without_build_menu_is_a_silent_no_op() {
      // Build Menu is not on the unit-test classpath, so this exercises the absent case: nothing
      // logged, compat off, and no exception escaping into the loader.
      BuildMenuCompat.install();
      assertFalse(BuildMenuCompat.isActive());
      assertEquals(0, BuildMenuCompat.mismatchReports());
   }

   @Test
   public void a_mismatch_is_logged_once() {
      // The signature check runs once at load, but a repeated failure -- say, if the advice could
      // not be installed -- must not fill the log on every placement.
      BuildMenuCompat.resetMismatchReporting();
      BuildMenuCompat.logMismatch("first");
      BuildMenuCompat.logMismatch("second");
      assertEquals(1, BuildMenuCompat.mismatchReports());
   }

   @Test
   public void the_client_view_appends_the_network_summary() {
      BuildMenuCompat.applyNetworkSummary(new Inventory(1));
      List<InventoryRange> base = new ArrayList<InventoryRange>();

      List<InventoryRange> result = BuildMenuCompat.withNetworkSummary(base);

      assertEquals(1, result.size());
      assertEquals("the argument is replaced, never mutated", 0, base.size());
      assertNotNull(BuildMenuCompat.networkSummaryRange());
   }

   @Test
   public void the_client_view_clears_when_disconnected() {
      BuildMenuCompat.applyNetworkSummary(new Inventory(0));
      List<InventoryRange> base = new ArrayList<InventoryRange>();

      List<InventoryRange> result = BuildMenuCompat.withNetworkSummary(base);

      assertSame("no summary means Build Menu's list is returned untouched", base, result);
      assertNull(BuildMenuCompat.networkSummaryRange());
   }

   @Test
   public void the_network_summary_comes_after_build_menus_list() {
      BuildMenuCompat.applyNetworkSummary(new Inventory(1));
      List<InventoryRange> base = new ArrayList<InventoryRange>();
      InventoryRange buildMenus = new InventoryRange(new Inventory(1));
      base.add(buildMenus);

      List<InventoryRange> result = BuildMenuCompat.withNetworkSummary(base);

      assertEquals(2, result.size());
      assertSame("Build Menu's own list stays first", buildMenus, result.get(0));
      assertSame("the network is the last resort", BuildMenuCompat.networkSummaryRange(), result.get(1));
   }

   @Test
   public void a_reapply_is_due_only_after_its_cadence() {
      assertTrue("a large elapsed time since reset", BuildMenuCompat.shouldReapply(1_000_000, 0));
      assertTrue("at exactly the cadence", BuildMenuCompat.shouldReapply(500, 0));
      assertFalse("just under the cadence", BuildMenuCompat.shouldReapply(499, 0));
      assertFalse("no time elapsed", BuildMenuCompat.shouldReapply(0, 0));
   }

   @Test
   public void reapplying_the_same_list_does_not_accumulate() {
      BuildMenuCompat.applyNetworkSummary(new Inventory(1));
      List<InventoryRange> base = new ArrayList<InventoryRange>();

      List<InventoryRange> once = BuildMenuCompat.withNetworkSummary(base);
      List<InventoryRange> twice = BuildMenuCompat.withNetworkSummary(base);

      assertEquals("one summary appended", 1, once.size());
      assertEquals("and still one on the next pass, not two", 1, twice.size());
      assertEquals("Build Menu's own list is never mutated", 0, base.size());
   }
}
