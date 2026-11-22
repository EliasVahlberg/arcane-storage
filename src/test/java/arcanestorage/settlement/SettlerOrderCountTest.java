package arcanestorage.settlement;

import static org.junit.Assert.assertEquals;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.function.Predicate;

import org.junit.Test;

import necesse.entity.mobs.Mob;
import necesse.entity.mobs.job.EntityJobWorker;

/**
 * The arithmetic a settler's "craft until N" order is answered with.
 *
 * <p>Everything here is the rule in {@link SettlerOrderCount#towards}: what an order is told it already has, given
 * what the engine counted and what this mod adds. It is tested with plain integers rather than with items, and that
 * is not a shortcut -- an {@code InventoryItem} cannot be constructed outside a running game, because it builds a GND
 * data map from a registry the game fills at load. The alternative would have been a rule with no test at all; this
 * way the part where an off-by-one costs a player a stack is covered in a second, and only the inventory walk is left
 * to the scenario suite.
 *
 * <p>The two properties worth stating because they are the whole safety argument: this can never report <i>more</i>
 * than the order's number, so a huge network cannot make an order of fifty look unfinished, and it can never report
 * less than the engine did, so a miscount here can only ever stop an order early -- the direction the design chose
 * deliberately, because over-counting halts work rather than repeating it forever.
 */
public class SettlerOrderCountTest {

   /** Amounts that "match", for tests about summing: any positive stack, as a real filter would say. */
   private static final Predicate<Integer> POSITIVE = amount -> amount > 0;

   private static int sum(int already, int max, Iterable<Integer> amounts) {
      return SettlerOrderCount.towards(already, max, amounts, POSITIVE, Integer::intValue);
   }

   @Test
   public void matchingAmountsAreAddedToWhatTheEngineFound() {
      assertEquals("thirty in the units on top of the engine's twenty", 50, sum(20, 100, Arrays.asList(10, 15, 5)));
   }

   @Test
   public void theAnswerIsCappedAtTheOrdersNumber() {
      assertEquals("an order of fifty is satisfied by far more than fifty", 50, sum(0, 50, Arrays.asList(400)));
   }

   @Test
   public void anOrderTheEngineHasAlreadySatisfiedGainsNothing() {
      assertEquals("nothing to add when the count already reaches the number", 50, sum(50, 50, Arrays.asList(10)));
      assertEquals("and never more than the number itself", 50, sum(80, 50, Arrays.asList(10)));
   }

   @Test
   public void aStackAboveTheOrdersNumberCountsOnlyAsFarAsTheNumber() {
      // A network slot holds one stack of a stackable item, so the interesting size is a full stack against a small
      // order: the number, not the stack, is what the order compares against.
      assertEquals(5, sum(0, 5, Arrays.asList(100)));
   }

   @Test
   public void everythingThatDoesNotMatchAddsNothing() {
      List<Integer> amounts = Arrays.asList(0, -3, 0);

      assertEquals("non-matching entries are skipped rather than summed", 7, sum(7, 100, amounts));
   }

   @Test
   public void twoUnitsSum() {
      List<Integer> first = Arrays.asList(20);
      List<Integer> second = Arrays.asList(30);
      List<Integer> both = new ArrayList<>(first);
      both.addAll(second);

      assertEquals(50, sum(0, 100, both));
   }

   @Test
   public void nothingAtAllIsTheEnginesAnswerUnchanged() {
      assertEquals(12, sum(12, 100, Collections.<Integer>emptyList()));
   }

   @Test
   public void theWalkStopsAtTheOrder() {
      // The load-bearing performance property. This runs during settler job selection, so an order of five must not
      // walk the thousand stacks behind it -- and if it does, the fix makes every settler slower for nothing.
      Counting amounts = new Counting(new int[] {3, 3, 3, 3, 3, 3, 3, 3, 3, 3});

      assertEquals(5, sum(0, 5, amounts));
      assertEquals("two stacks of three reach five; the other eight must not be read", 2, amounts.pulled);
   }

   @Test
   public void oneEntryThatReachesTheNumberIsTheLastOneRead() {
      Counting amounts = new Counting(new int[] {99, 1, 1});

      assertEquals(99, sum(0, 99, amounts));
      assertEquals("the first entry alone satisfies it", 1, amounts.pulled);
   }

   @Test
   public void aWorkerThatIsNotASettlerAddsNothing() {
      // The engine only ever asks this question for settlers, so anything else -- a player's own job worker, another
      // mod's mob -- must leave the answer exactly as the engine gave it.
      long failuresBefore = SettlerOrderCount.failures();

      assertEquals("a worker with no mob behind it is not a settler", 12,
            SettlerOrderCount.withUnits(workerReturning(null), amount -> true, 12, 100));
      assertEquals("and that is the normal path, not a swallowed exception", failuresBefore,
            SettlerOrderCount.failures());
   }

   @Test
   public void aFailingWorkerReturnsTheEnginesAnswerAndIsCounted() {
      // The failure policy: this runs inside settler job selection, so a fault here must cost a count, never a job.
      long failuresBefore = SettlerOrderCount.failures();
      EntityJobWorker broken = workerThatThrows();

      assertEquals("the engine's own number, unchanged", 12,
            SettlerOrderCount.withUnits(broken, amount -> true, 12, 100));
      assertEquals("one failure counted", failuresBefore + 1, SettlerOrderCount.failures());
      assertEquals("and again, so a repeat fault is still visible in the count", 12,
            SettlerOrderCount.withUnits(broken, amount -> true, 12, 100));
      assertEquals(failuresBefore + 2, SettlerOrderCount.failures());
   }

   /** A job worker that reports no mob at all: what any non-settler worker looks like to this code. */
   private static EntityJobWorker workerReturning(Mob mob) {
      return (EntityJobWorker)Proxy.newProxyInstance(
            EntityJobWorker.class.getClassLoader(),
            new Class<?>[] {EntityJobWorker.class},
            (proxy, method, args) -> "getMobWorker".equals(method.getName()) ? mob : null);
   }

   /** A job worker that throws when asked for its mob: the shape any linkage or game-API fault takes here. */
   private static EntityJobWorker workerThatThrows() {
      return (EntityJobWorker)Proxy.newProxyInstance(
            EntityJobWorker.class.getClassLoader(),
            new Class<?>[] {EntityJobWorker.class},
            (proxy, method, args) -> {
               throw new IllegalStateException("no mob for you");
            });
   }

   /** Amounts that count how many were walked, so an early-out can be observed rather than assumed. */
   private static final class Counting implements Iterable<Integer> {
      private final int[] amounts;

      private int pulled;

      Counting(int[] amounts) {
         this.amounts = amounts;
      }

      @Override
      public Iterator<Integer> iterator() {
         return new Iterator<Integer>() {
            private int index;

            @Override
            public boolean hasNext() {
               return this.index < Counting.this.amounts.length;
            }

            @Override
            public Integer next() {
               Counting.this.pulled++;
               return Counting.this.amounts[this.index++];
            }
         };
      }
   }
}
