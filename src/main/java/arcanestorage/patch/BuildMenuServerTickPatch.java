package arcanestorage.patch;

import necesse.engine.modLoader.annotations.ModMethodPatch;
import necesse.engine.network.server.Server;
import net.bytebuddy.asm.Advice;

/**
 * Drives the Build Menu summary sweep on the server's own tick.
 *
 * <p>The work is all inside {@link BuildMenuSummary#tick}, which returns immediately except on its
 * 500 ms cadence, so the per-tick cost here is one clock read and a compare. The body is guarded so
 * a failure in the sweep cannot interrupt the server tick it rides on.
 */
@ModMethodPatch(target = Server.class, name = "tick", arguments = {})
public class BuildMenuServerTickPatch {

   @Advice.OnMethodEnter
   static void onEnter(@Advice.This Server server) {
      try {
         BuildMenuSummary.tick(server);
      } catch (Throwable t) {
         BuildMenuCompat.snapshotFailed(t);
      }
   }
}
