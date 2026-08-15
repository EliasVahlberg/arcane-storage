package arcanestorage.band;

import arcanestorage.network.NetworkIndexes;

/**
 * Watches for layout changes and says when it is worth looking again.
 *
 * <p>Both band devices decide what they are from the world around them, and both need that decision to be free when
 * nothing has changed -- an idle base must cost nothing, which rules out re-walking the network on a timer. The
 * trigger is therefore {@link NetworkIndexes#topologyVersion()}, which every placement and every break already bumps.
 *
 * <h2>Why the answer is delayed by a tick or two</h2>
 *
 * <p><b>Because the bump arrives before the change it announces.</b> The engine's destroy path calls
 * {@code GameObject.onDestroyed} -- which is where this mod bumps the version -- and only then does the caller clear
 * the tile. A device that validated the instant it saw a new version therefore looked at a world in which the broken
 * object was still standing, recorded that as the answer, and never looked again: a Base Station kept transmitting
 * after its transceiver was mined, and only noticed when something unrelated was placed minutes later. That was found
 * by a test, and the same ordering is what happens in play.
 *
 * <p>So a change arms a countdown instead of validating, and a further change re-arms it. Two ticks is a tenth of a
 * second, well under anything a player can perceive, and long enough for the removal to have completed.
 *
 * <p>The one theoretical gap: something bumping the version on <i>every</i> tick forever would keep re-arming and
 * validation would never run. Nothing does -- placements are human-paced and this mod's own bumps settle as soon as
 * the thing they describe stops changing -- but it is the shape to look for if a device ever seems stuck.
 */
public final class LayoutWatch {

   /** Ticks to wait after the last change. Two, because the change lands in the same tick as the bump. */
   private static final int DELAY = 2;

   private long seen = Long.MIN_VALUE;

   private int countdown;

   /**
    * True on the tick a device should re-examine its surroundings.
    *
    * <p>Called once per tick per device. A long comparison and a decrement, which is what makes it affordable to have
    * this in a tick body at all.
    */
   public boolean due() {
      long version = NetworkIndexes.topologyVersion();
      if (version != this.seen) {
         this.seen = version;
         this.countdown = DELAY;
         return false;
      }

      return this.countdown > 0 && --this.countdown == 0;
   }

   /**
    * Adopts the current version after validating.
    *
    * <p>Needed because validating changes things: pruning a stale claim and marking a band live both bump the version,
    * and without this the device would see its own work as a reason to work again.
    */
   public void adopt() {
      this.seen = NetworkIndexes.topologyVersion();
      this.countdown = 0;
   }

   /** Forces the next tick to re-examine, for changes that are not layout changes -- a rename, a retune. */
   public void invalidate() {
      this.seen = Long.MIN_VALUE;
   }
}
