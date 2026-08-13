package arcanestorage.objectentity;

/**
 * Why a device is, or is not, doing anything.
 *
 * <p><b>Fail closed.</b> A device that cannot satisfy its rules does nothing rather than guessing. For a
 * storage mod that is the right default: moving items wrongly is worse than not moving them, and it is the
 * same instinct as an export bus starting with nothing ticked.
 *
 * <p><b>Derived, never saved.</b> The state is a function of the world and the rules, so it is recomputed
 * rather than persisted, and a reload cannot resurrect a stale one. It <i>is</i> synced to clients, because
 * the sprite is drawn client-side.
 *
 * <p>An enum rather than a boolean so the two placement mistakes get the same treatment as a rule conflict —
 * a bus with no chest beside it was previously invisible unless the player opened it — and so a manual off
 * switch can reuse the path without touching anything that draws or reports.
 */
public enum DeviceState {

   /** Working. */
   ACTIVE(null),

   /** Nothing beside it to move items to or from. The likeliest mistake when placing a bus. */
   NO_CONTAINER("arcanestorage_bus_nocontainer"),

   /** Not touching a network. */
   NO_NETWORK("arcanestorage_bus_nonetwork"),

   /**
    * Its rules and another device's cannot both be satisfied, so obeying both would move items forever.
    *
    * <p>Both devices in the pair enter this state, each naming the other. That is deliberate: picking a
    * winner would leave a rule silently doing nothing, which is the failure mode this whole mechanism
    * exists to remove.
    */
   RULE_CONFLICT("arcanestorage_state_conflict"),

   /**
    * An item kept moving without the network getting any closer to its rules, so the device stopped.
    *
    * <p>Separate from {@link #RULE_CONFLICT} because the cause is different and so is the fix. A rule conflict
    * is two of our devices contradicting each other, which the static check can see and name. This is a loop
    * closed outside the network -- a settler hauling items back, a hopper, another mod's pipe -- which nothing
    * can see in advance and which is therefore caught on the evidence of the work itself.
    */
   CHURN("arcanestorage_state_churn");

   /** Locale key under {@code ui} explaining the state, or null when there is nothing to explain. */
   public final String localeKey;

   DeviceState(String localeKey) {
      this.localeKey = localeKey;
   }

   /**
    * Whether this state means the device has been deliberately stopped, as against merely describing a
    * situation.
    *
    * <p>The distinction matters because the states are recomputed on a heartbeat and can therefore be a second
    * out of date, and a stale description must not stop a device working. A missing container or network is a
    * fact the move path checks for itself as it goes; a rule conflict or a churn stop is a decision, and it is
    * the decision that has to hold.
    *
    * <p>Got this wrong first: a bus that had been placed before its chest kept "no container" until the next
    * heartbeat and refused to move anything in the meantime, which looked exactly like the bug this whole
    * design was meant to remove.
    */
   public boolean stopsWork() {
      return this == RULE_CONFLICT || this == CHURN;
   }

   public boolean isActive() {
      return this == ACTIVE;
   }
}
