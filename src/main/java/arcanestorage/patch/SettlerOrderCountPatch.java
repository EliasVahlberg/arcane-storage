package arcanestorage.patch;

import java.util.function.Predicate;

import arcanestorage.settlement.SettlerOrderCount;
import necesse.engine.modLoader.annotations.ModMethodPatch;
import necesse.entity.mobs.job.EntityJobWorker;
import necesse.inventory.InventoryItem;
import necesse.level.maps.levelData.jobs.HasStorageLevelJob;
import net.bytebuddy.asm.Advice;

/**
 * Lets a settler's "craft until N" order count this mod's Storage Units inside the settlement.
 *
 * <p><b>Why a patch.</b> The decision this order makes -- is there enough of the result already -- is taken inside one
 * static engine method, {@code HasStorageLevelJob.getItemCount}, which walks the settlement's assigned storage and
 * sums what it finds. There is no interface, hook or listener anywhere on that path, and no place a mod can contribute
 * a count, so the alternatives were a patch or leaving the reported loop in place. The method is static, called from
 * exactly one place (the {@code DO_UNTIL} branch of {@code UseWorkstationLevelJob.getJobSequence}), and only ever on
 * the server.
 *
 * <p><b>Woven at exit, replacing the result.</b> The engine's count is the right starting point and is what the
 * previous behaviour was, so the advice adds to what the method returned rather than computing the whole answer: the
 * parts of the mechanic this mod has no business in -- the settlement's own assigned storage, drop-off simulation,
 * future workstation output -- are all still the engine's own arithmetic.
 *
 * <p><b>The body is guarded, inside the advice.</b> This runs during settler job selection, and an exception escaping
 * here would not break this mod's interface; it would break the job search of every settler the game tries to assign,
 * for a reason nothing in the interface could explain. Wrapping in {@code SettlerOrderCount.withUnits} instead would
 * leave a {@code LinkageError} from loading <i>this</i> class -- the likeliest failure of a patch -- escaping into the
 * engine's method uncaught, so the catch is written here and inlined alongside the call.
 */
@ModMethodPatch(target = HasStorageLevelJob.class, name = "getItemCount",
      arguments = {EntityJobWorker.class, Predicate.class, int.class, boolean.class, boolean.class})
public class SettlerOrderCountPatch {

   @Advice.OnMethodExit
   static void onExit(
         @Advice.Argument(0) EntityJobWorker worker,
         @Advice.Argument(1) Predicate<InventoryItem> filter,
         @Advice.Argument(2) int maxCount,
         @Advice.Return(readOnly = false) int count) {
      try {
         count = SettlerOrderCount.withUnits(worker, filter, count, maxCount);
      } catch (Throwable t) {
         SettlerOrderCount.failed(t);
      }
   }
}
