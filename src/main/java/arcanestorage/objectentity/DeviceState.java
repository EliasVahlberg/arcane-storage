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
   RULE_CONFLICT("arcanestorage_state_conflict");

   /** Locale key under {@code ui} explaining the state, or null when there is nothing to explain. */
   public final String localeKey;

   DeviceState(String localeKey) {
      this.localeKey = localeKey;
   }

   public boolean isActive() {
      return this == ACTIVE;
   }
}
