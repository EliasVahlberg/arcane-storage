package arcanestorage.band;

import java.util.Arrays;

import arcanestorage.ArcaneStorage;
import arcanestorage.object.UnitTier;

/**
 * One Arcane frequency band: a Base Station, the channels it offers, and which Access Point holds each.
 *
 * <p>A plain record owned by {@link BandIndex}, which is the authority. Nothing here reaches into the level or
 * decides anything -- the point of keeping it dumb is that the band's shape is then testable without a game, and
 * that there is exactly one copy of it rather than one per device that cares.
 *
 * <h2>Why the channels live here and not on the Base Station's object entity</h2>
 *
 * <p>Because an object entity only exists while its region is loaded, and a band has to answer questions when it is
 * not. An Access Point 200 tiles from its Base Station is routinely in a region nobody is standing in; so is the
 * Base Station, seen from the Access Point. If the channel table lived on the station's entity, then tuning an
 * Access Point, listing the free channels, or asking whether a claim conflicts would all fail -- silently and
 * intermittently -- depending on where the player happened to be standing. Level data is loaded whenever the level
 * is, which is the lifetime this question has.
 */
public final class Band {

   /** Where the Base Station stands. Identity: a band <i>is</i> its station, and the id is a handle for players. */
   public final int stationX;

   public final int stationY;

   /**
    * The number the player sees and tunes to, unique per level and reused once a band is gone.
    *
    * <p>Small on purpose. It could have been the station's coordinates -- they identify it already -- but a band is
    * something a player reads off one panel and types into another, and "Band 2" survives that trip in a way that a
    * pair of five-digit signed numbers does not.
    */
   public final int id;

   /** The station's tier, which decides how many channels the band offers. */
   public UnitTier tier;

   /**
    * Channel n-1 holds the tile of the Access Point tuned to channel n, or {@link #FREE}.
    *
    * <p>Sized to the tier's channel count and resized when the station is upgraded. Indexed by channel so the
    * station's panel can list 1..N in order without sorting anything, and so a claim is a single array write.
    */
   private long[] channels;

   /** No Access Point on this channel. Not a valid packed tile, since a real one always has a low half. */
   public static final long FREE = Long.MIN_VALUE;

   /**
    * Whether the band is currently transmitting: the station has a Wireless Transceiver on its own cluster and is
    * the only station there.
    *
    * <p><b>Deliberately not saved.</b> It is derived from the layout, and the station recomputes it whenever the
    * layout changes. Saving it would mean trusting a flag written before whatever the player did next.
    *
    * <p>It lives on the band rather than on the station's entity for one specific reason: <b>both ends of a link
    * read it</b>, and they must read the same value. A walk that starts in a silo asks whether it may cross to the
    * main cluster; a walk that starts at the terminal asks whether it may cross to the silo. If those two could
    * disagree, two members of one network would compute different networks -- and since a network is named by its
    * lowest member tile, they would then use different shared indexes over overlapping sets of units, which shows
    * up as items counted twice.
    */
   public boolean live;

   Band(int id, int stationX, int stationY, UnitTier tier) {
      this.id = id;
      this.stationX = stationX;
      this.stationY = stationY;
      this.tier = tier;
      this.channels = new long[channelsFor(tier)];
      Arrays.fill(this.channels, FREE);
   }

   /** How many channels a station of this tier offers, from the config. */
   public static int channelsFor(UnitTier tier) {
      return ArcaneStorage.SETTINGS.bandChannels(tier);
   }

   /**
    * Resizes the channel table to the tier's count, keeping every claim that still fits.
    *
    * <p>Shrinking is possible in only two ways -- the config was edited, or a station was somehow downgraded -- and
    * in both cases the Access Points on the lost channels are dropped rather than moved. Moving them would put a
    * player's silo on a channel they did not choose, and they find out either way: an unclaimed Access Point goes
    * dark and says why.
    */
   void resize() {
      int wanted = channelsFor(this.tier);
      if (wanted == this.channels.length) {
         return;
      }

      long[] next = new long[wanted];
      Arrays.fill(next, FREE);
      System.arraycopy(this.channels, 0, next, 0, Math.min(wanted, this.channels.length));
      this.channels = next;
   }

   public int channelCount() {
      return this.channels.length;
   }

   /** The packed tile on a channel, or {@link #FREE}. Channels are 1-based, as the player sees them. */
   public long occupant(int channel) {
      return channel < 1 || channel > this.channels.length ? FREE : this.channels[channel - 1];
   }

   public boolean isFree(int channel) {
      return this.occupant(channel) == FREE;
   }

   void set(int channel, long tile) {
      if (channel >= 1 && channel <= this.channels.length) {
         this.channels[channel - 1] = tile;
      }
   }

   /** The channel an Access Point holds on this band, or 0. */
   public int channelOf(long tile) {
      for (int i = 0; i < this.channels.length; i++) {
         if (this.channels[i] == tile) {
            return i + 1;
         }
      }

      return 0;
   }

   /** Drops an Access Point from whatever channel it held. */
   void release(long tile) {
      for (int i = 0; i < this.channels.length; i++) {
         if (this.channels[i] == tile) {
            this.channels[i] = FREE;
         }
      }
   }

   public int freeChannels() {
      int free = 0;
      for (long channel : this.channels) {
         if (channel == FREE) {
            free++;
         }
      }

      return free;
   }

   /** Every claimed Access Point tile, in channel order, so a walk's link order is stable. */
   long[] occupants() {
      long[] found = new long[this.channels.length - this.freeChannels()];
      int at = 0;
      for (long channel : this.channels) {
         if (channel != FREE) {
            found[at++] = channel;
         }
      }

      return found;
   }

   /** The lowest channel with nothing on it, or 0 when the band is full. */
   public int firstFreeChannel() {
      for (int i = 0; i < this.channels.length; i++) {
         if (this.channels[i] == FREE) {
            return i + 1;
         }
      }

      return 0;
   }

   long[] rawChannels() {
      return this.channels;
   }

   void setRawChannels(long[] channels) {
      this.channels = channels;
   }
}
