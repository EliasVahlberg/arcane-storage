package arcanestorage.band;

/**
 * Why a band device is not working, or that it is.
 *
 * <p>One enum for both ends rather than one each: every value is an answer to the same question -- why is this thing
 * dark -- and the two devices need to word their answers consistently anyway, since a player looking at a dark
 * Access Point and a dark Base Station is looking at one problem from two sides.
 *
 * <p>Modelled on {@code DeviceState}, which does the same job for the buses, but deliberately separate from it. The
 * bus states are about moving items and this is about carrying a network; sharing one enum would give each device a
 * set of states it can never be in, and the compiler would stop helping.
 */
public enum BandState {

   /** Transmitting, or connected. */
   ACTIVE(null),

   /** The station has no Wireless Transceiver on its own cluster, so there is nothing to transmit through. */
   NO_TRANSCEIVER("arcanestorage_band_notransceiver"),

   /**
    * Another Base Station is on the same network.
    *
    * <p>Both go dark, neither wins. Same reasoning as a bus rule conflict: picking a winner would leave the other
    * station silently doing nothing, and the player would have no way to tell which one they were looking at.
    */
   STATION_CONFLICT("arcanestorage_band_conflict"),

   /** Nothing tuned yet. The state every Access Point starts in. */
   NO_BAND("arcanestorage_band_notuned"),

   /** The band this Access Point is tuned to no longer exists -- its station was broken. */
   BAND_GONE("arcanestorage_band_gone"),

   /** The band exists but is not transmitting. The reason is at the station. */
   BAND_DOWN("arcanestorage_band_down"),

   /** The channel is no longer held by this Access Point. */
   NOT_CLAIMED("arcanestorage_band_lostchannel"),

   /** Further from the station than the band reaches. */
   TOO_FAR("arcanestorage_band_outofrange");

   /** Locale key under {@code ui}, or null when there is nothing to explain. */
   public final String localeKey;

   BandState(String localeKey) {
      this.localeKey = localeKey;
   }

   public boolean isActive() {
      return this == ACTIVE;
   }
}
