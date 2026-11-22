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

   /** The client-side shape the re-apply reads: a static getter and the list field, as the form has them. */
   public static final class CorrectState {
      public static final CorrectState activeInstance = new CorrectState();

      private final List<InventoryRange> remoteStorageRanges = new ArrayList<InventoryRange>();

      public static CorrectState getActiveInstance() {
         return activeInstance;
      }
   }

   /** The same, with the list field renamed: the re-apply would never see what the target holds. */
   public static final class RenamedListField {
      public static final RenamedListField activeInstance = new RenamedListField();

      private final List<InventoryRange> storageRanges = new ArrayList<InventoryRange>();
   }

   /** The list field holds something that is not a list. */
   public static final class WrongListType {
      private final Object remoteStorageRanges = new Object();
   }

   /** The instance accessor is no longer static, so a static call on it would throw. */
   public static final class NonStaticAccessor {
      public Object getActiveInstance() {
         return this;
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

   @Test
   public void a_target_holding_the_summary_is_left_alone() {
      // The flash, as a rule. Telling a target again is not free: the form rebuilds its item grid, which takes
      // the hovered item's tooltip with it. So a target that already holds this exact range must not be told.
      InventoryRange wanted = new InventoryRange(new Inventory(1));
      List<InventoryRange> held = new ArrayList<InventoryRange>();
      held.add(wanted);

      assertFalse(BuildMenuCompat.needsHandover(held, wanted, wanted));
   }

   @Test
   public void a_target_that_lost_its_list_is_told_once() {
      // Build Menu replaces the list with a fresh empty one when its menu is hidden, and outside a settlement no
      // snapshot ever comes back to refill it. That is the case the re-apply exists for.
      InventoryRange wanted = new InventoryRange(new Inventory(1));

      assertTrue("an emptied list needs the range again",
            BuildMenuCompat.needsHandover(new ArrayList<InventoryRange>(), wanted, wanted));
      assertTrue("a null list is the same case",
            BuildMenuCompat.needsHandover(null, wanted, wanted));
   }

   @Test
   public void a_target_holding_an_older_summary_gets_the_new_one() {
      InventoryRange older = new InventoryRange(new Inventory(1));
      InventoryRange newer = new InventoryRange(new Inventory(2));
      List<InventoryRange> held = new ArrayList<InventoryRange>();
      held.add(older);

      assertTrue("a new summary arrived, so hand it over",
            BuildMenuCompat.needsHandover(held, newer, older));
   }

   @Test
   public void a_cleared_summary_only_tells_a_target_still_holding_the_old_range() {
      InventoryRange older = new InventoryRange(new Inventory(1));
      List<InventoryRange> holding = new ArrayList<InventoryRange>();
      holding.add(older);

      assertTrue("it still holds the range that is now gone",
            BuildMenuCompat.needsHandover(holding, null, older));
      assertFalse("nothing to clear",
            BuildMenuCompat.needsHandover(new ArrayList<InventoryRange>(), null, older));
      assertFalse("and nothing was ever handed over",
            BuildMenuCompat.needsHandover(holding, null, null));
   }

   @Test
   public void build_menus_own_ranges_in_the_list_are_not_ours() {
      // Identity, not content: a range that looks the same is Build Menu's own and tells us nothing.
      InventoryRange ours = new InventoryRange(new Inventory(1));
      InventoryRange theirs = new InventoryRange(new Inventory(1));
      List<InventoryRange> held = new ArrayList<InventoryRange>();
      held.add(theirs);

      assertFalse(BuildMenuCompat.containsIdentity(held, ours));
      assertTrue(BuildMenuCompat.needsHandover(held, ours, null));
   }

   @Test
   public void the_expected_client_state_shape_passes() {
      assertNull(BuildMenuCompat.clientStateProblem(CorrectState.class, "getActiveInstance", null,
            "remoteStorageRanges"));
      assertNull("and the tools' shape, with a static field instead of a getter",
            BuildMenuCompat.clientStateProblem(RenamedListField.class, null, "activeInstance", "storageRanges"));
   }

   @Test
   public void a_renamed_list_field_is_named() {
      String problem = BuildMenuCompat.clientStateProblem(RenamedListField.class, null, null, "remoteStorageRanges");
      assertNotNull(problem);
      assertTrue(problem, problem.contains("remoteStorageRanges"));
   }

   @Test
   public void a_list_field_of_the_wrong_type_is_named() {
      String problem = BuildMenuCompat.clientStateProblem(WrongListType.class, null, null, "remoteStorageRanges");
      assertNotNull(problem);
      assertTrue(problem, problem.contains("not a list"));
   }

   @Test
   public void an_instance_accessor_is_named() {
      String problem = BuildMenuCompat.clientStateProblem(NonStaticAccessor.class, "getActiveInstance", null, null);
      assertNotNull(problem);
      assertTrue(problem, problem.contains("not static"));
   }
}
