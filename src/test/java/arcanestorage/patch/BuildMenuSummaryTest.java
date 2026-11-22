package arcanestorage.patch;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * The summary sweep cadence, in isolation. The sweep itself touches a running server, so it lives in
 * the headless suite; this pins the one line that bounds how often it may run.
 */
public class BuildMenuSummaryTest {

   @Test
   public void a_sweep_is_due_only_after_the_cadence() {
      assertTrue("a large elapsed time since reset", BuildMenuSummary.shouldSweep(1_000_000, 0));
      assertTrue("at exactly the cadence", BuildMenuSummary.shouldSweep(500, 0));
      assertFalse("just under the cadence", BuildMenuSummary.shouldSweep(499, 0));
      assertFalse("no time elapsed", BuildMenuSummary.shouldSweep(0, 0));
      assertTrue("a later window", BuildMenuSummary.shouldSweep(1000, 500));
      assertFalse("still inside a later window", BuildMenuSummary.shouldSweep(999, 500));
   }
}
