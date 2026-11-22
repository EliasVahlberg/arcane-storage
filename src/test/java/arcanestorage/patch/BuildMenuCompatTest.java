package arcanestorage.patch;

import java.util.ArrayList;
import java.util.List;

import org.junit.Test;

import necesse.entity.mobs.PlayerMob;
import necesse.inventory.InventoryRange;
import necesse.level.maps.Level;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * The signature check and the absent-mod behaviour, which are the two halves of the Build Menu
 * compat that must hold even before a real server boots.
 *
 * <p>A name-based patch of another mod's class binds to an exact signature. A Build Menu update that
 * renames or retypes the method would otherwise fail silently, so the check is a unit test rather
 * than an assumption. The stand-in classes below are the "changed signature" the headless suite
 * cannot produce, because it would need a whole second server boot against a fake jar.
 */
public class BuildMenuCompatTest {

   /** The signature the patch expects: static, four arguments, returning a List. */
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

   @Test
   public void the_expected_signature_passes() {
      assertNull(BuildMenuCompat.signatureProblem(Correct.class));
   }

   @Test
   public void a_changed_parameter_list_is_named() {
      String problem = BuildMenuCompat.signatureProblem(WrongParams.class);
      assertNotNull(problem);
      assertTrue(problem, problem.contains("no buildAvailabilityRanges"));
   }

   @Test
   public void a_changed_return_type_is_named() {
      String problem = BuildMenuCompat.signatureProblem(WrongReturn.class);
      assertNotNull(problem);
      assertTrue(problem, problem.contains("returns"));
   }

   @Test
   public void an_instance_method_is_named() {
      String problem = BuildMenuCompat.signatureProblem(NonStatic.class);
      assertNotNull(problem);
      assertTrue(problem, problem.contains("not static"));
   }

   @Test
   public void install_without_build_menu_is_a_silent_no_op() {
      // Build Menu is not on the unit-test classpath, so this exercises the absent case: nothing
      // logged, compat off, and no exception escaping into the loader.
      BuildMenuCompat.install();
      assertFalse(BuildMenuCompat.isActive());
      assertEquals(0, BuildMenuCompat.mismatchReports());
   }
}
