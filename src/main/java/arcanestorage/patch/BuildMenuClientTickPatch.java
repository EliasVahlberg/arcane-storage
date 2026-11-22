package arcanestorage.patch;

import necesse.engine.modLoader.annotations.ModMethodPatch;
import necesse.engine.network.client.Client;
import net.bytebuddy.asm.Advice;

/**
 * Drives the client-side re-apply of the cached Build Menu summary on the client's own tick.
 *
 * <p>The work is all inside {@link BuildMenuCompat#clientTickReapply()}, which returns immediately
 * unless a summary is cached, the compat is active and its 500 ms cadence has passed — so the
 * per-tick cost is one boolean read on a client that never receives a summary. On a dedicated server
 * the client tick never runs, so this is dormant there. The body is guarded so a failure cannot
 * interrupt the client tick it rides on.
 */
@ModMethodPatch(target = Client.class, name = "tick", arguments = {})
public class BuildMenuClientTickPatch {

   @Advice.OnMethodEnter
   static void onEnter() {
      try {
         BuildMenuCompat.clientTickReapply();
      } catch (Throwable t) {
         BuildMenuCompat.snapshotFailed(t);
      }
   }
}
