package arcanestorage.command;

import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Runs work on the server thread, for callers that are not on it.
 *
 * <p>This exists because of a fault that took three server crashes to understand. Console commands
 * do not run on the server thread — {@code ServerScanThread} reads a line and calls
 * {@code CommandsManager.runServerCommand} directly, on its own thread. Anything that then mutates
 * the level is racing the tick, and the engine does not expect that: {@code Level.setObject} reaches
 * settlement data while holding a region lock, region *generation* takes the level monitor while
 * holding a region lock, and the tick takes those same pairs in the opposite order. Each inversion
 * was caught by the engine's own {@code ThreadFreezeMonitor}, which kills the server.
 *
 * <p>Two of them were fixed by taking the locks in the tick's order, which worked but was
 * whack-a-mole: it can only fix an inversion that has already been observed. The harness also spaced
 * commands apart, which looked like a fix and was not — removing the delay deadlocked reliably after
 * about a hundred commands, and merely shortening it still hung one run in three. A harness that
 * fails one run in three is worse than one that fails every time, because the silence in between
 * looks like success.
 *
 * <p>So the work is handed to the server thread instead, which is where the engine performs it and
 * where every lock order is consistent by construction. Nothing is being worked around any more.
 */
public final class ServerThreadTasks {
   private static final Queue<Runnable> PENDING = new ConcurrentLinkedQueue<>();

   /**
    * The thread currently draining, so a caller already on the server thread runs inline instead of
    * queueing work only it could execute. A player issuing the command through chat arrives on the
    * server thread already, and would otherwise wait forever for itself.
    */
   private static volatile Thread drainThread;

   private ServerThreadTasks() {
   }

   /** Called from the server tick. Runs everything queued since the last one. */
   public static void drain() {
      drainThread = Thread.currentThread();

      Runnable task;
      while ((task = PENDING.poll()) != null) {
         try {
            task.run();
         } catch (Throwable t) {
            // A failing task must not take the server's tick down with it.
            System.err.println("Arcane Storage: queued server task failed");
            t.printStackTrace();
         }
      }
   }

   /**
    * Runs {@code work} on the server thread and waits for it to finish.
    *
    * @return true if it ran; false if the server thread never picked it up within the timeout, in
    *     which case the caller should say so rather than pretend the work happened.
    */
   public static boolean runAndWait(Runnable work, long timeoutMillis) {
      if (Thread.currentThread() == drainThread) {
         work.run();
         return true;
      }

      CountDownLatch done = new CountDownLatch(1);
      PENDING.add(() -> {
         try {
            work.run();
         } finally {
            done.countDown();
         }
      });

      try {
         return done.await(timeoutMillis, TimeUnit.MILLISECONDS);
      } catch (InterruptedException e) {
         Thread.currentThread().interrupt();
         return false;
      }
   }
}
