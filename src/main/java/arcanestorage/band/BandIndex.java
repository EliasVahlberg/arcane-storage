package arcanestorage.band;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import arcanestorage.ArcaneStorage;
import arcanestorage.network.NetworkIndexes;
import arcanestorage.object.UnitTier;
import necesse.engine.save.LoadData;
import necesse.engine.save.SaveData;
import necesse.level.maps.Level;
import necesse.level.maps.levelData.LevelData;

/**
 * Every Arcane frequency band on one level: which Base Station owns it, and which Access Point holds each channel.
 *
 * <p><b>The single authority.</b> A Base Station reads its own record here rather than holding one; an Access Point
 * stores the band and channel it wants and is only connected if this index agrees. Two devices that each kept half
 * the truth would drift the moment one of them was broken while the other's region was unloaded, and the drift would
 * show up as a silo that is sometimes part of the network.
 *
 * <p><b>Why level data.</b> This has to be answerable while the tiles it describes are unloaded -- see
 * {@link Band}'s note -- and {@code LevelData} is the engine's own per-level persisted state, saved and loaded with
 * the level for free. It is created on demand rather than at world generation, because a mod's level data does not
 * exist in a world that predates the mod.
 *
 * <p>Server-side. The client is told what to draw: a Base Station's panel and an Access Point's panel both receive
 * what they need in their open packet, so no copy of this exists on a client to disagree with it.
 */
public class BandIndex extends LevelData {

   /** Must never change: it is the key this data is saved under. Letters and digits only, per the manager. */
   public static final String KEY = "arcanestorageband";

   private final Map<Integer, Band> bands = new HashMap<>();

   /**
    * Bumped on every change, so the link map below can be rebuilt lazily rather than eagerly.
    *
    * <p>Lazily because the expensive consumer is a network walk, which asks for links far more often than the bands
    * change: a rebuild per change would be work nobody had asked for yet, and a rebuild per ask would be the walk
    * paying for it repeatedly.
    */
   private long version;

   private long linksBuiltUnder = -1;

   /**
    * Tile to the tiles the network continues from, both directions, or absent when a tile links nowhere.
    *
    * <p>Symmetric by construction: a station's entry lists its Access Points and each of those lists the station
    * back. That symmetry is what makes every member of a bridged network discover the same set. Built once per
    * {@link #version} and read on every conducting tile of every walk, which is why it is a map lookup and not a
    * search.
    */
   private final Map<Long, long[]> links = new HashMap<>();

   /** The index for a level, created and attached the first time it is asked for. */
   public static BandIndex of(Level level) {
      LevelData existing = level.getLevelData(KEY);
      if (existing instanceof BandIndex) {
         return (BandIndex)existing;
      }

      BandIndex created = new BandIndex();
      level.addLevelData(KEY, created);
      return created;
   }

   /**
    * The index for a level if one exists, without creating it.
    *
    * <p>Used by the traversal, which asks about every conducting tile on every walk and must not attach level data
    * to a level that has no bands -- and must not attach anything at all to a client's level.
    */
   public static BandIndex existing(Level level) {
      LevelData existing = level == null ? null : level.getLevelData(KEY);
      return existing instanceof BandIndex ? (BandIndex)existing : null;
   }

   public static long tile(int x, int y) {
      return (long)x << 32 | (long)y & 0xFFFFFFFFL;
   }

   public static int tileX(long tile) {
      return (int)(tile >> 32);
   }

   public static int tileY(long tile) {
      return (int)tile;
   }

   public List<Band> bands() {
      List<Band> all = new ArrayList<>(this.bands.values());
      all.sort((a, b) -> Integer.compare(a.id, b.id));
      return all;
   }

   public Band byId(int id) {
      return this.bands.get(id);
   }

   public Band byStation(int x, int y) {
      for (Band band : this.bands.values()) {
         if (band.stationX == x && band.stationY == y) {
            return band;
         }
      }

      return null;
   }

   /**
    * The band a Base Station owns, created if this is the first time the station has been seen.
    *
    * <p>Idempotent, and called from the station's tick rather than from placement, so a station that predates its
    * band -- a world where the index was lost, a station placed by another mod, a save edited by hand -- acquires
    * one instead of being permanently mute.
    */
   public Band register(int x, int y, UnitTier tier) {
      Band existing = this.byStation(x, y);
      if (existing != null) {
         if (existing.tier != tier) {
            existing.tier = tier;
            existing.resize();
            this.changed();
         }

         return existing;
      }

      Band created = new Band(this.nextId(), x, y, tier);
      this.bands.put(created.id, created);
      this.changed();
      return created;
   }

   /**
    * Lowest unused id, so bands read as 1, 2, 3 and a number is reused once its station is gone.
    *
    * <p>Reuse is the right call rather than a monotonic counter: the number is a label a player types, and a base
    * rebuilt in the same place should get its old number back rather than climb forever.
    */
   private int nextId() {
      for (int candidate = 1; candidate <= this.bands.size() + 1; candidate++) {
         if (!this.bands.containsKey(candidate)) {
            return candidate;
         }
      }

      return this.bands.size() + 1;
   }

   /** Removes a station's band. Its Access Points keep their setting and report the band as gone. */
   public void unregister(int x, int y) {
      Band band = this.byStation(x, y);
      if (band != null) {
         this.bands.remove(band.id);
         this.changed();
      }
   }

   /** Why a claim was refused, as a locale key under {@code ui}, or null when it was granted. */
   public String claim(int bandId, int channel, int apX, int apY) {
      Band band = this.byId(bandId);
      if (band == null) {
         return "arcanestorage_band_noband";
      }

      if (channel < 1 || channel > band.channelCount()) {
         return "arcanestorage_band_nochannel";
      }

      long tile = tile(apX, apY);
      long occupant = band.occupant(channel);
      if (occupant != Band.FREE && occupant != tile) {
         return "arcanestorage_band_taken";
      }

      if (distance(band.stationX, band.stationY, apX, apY) > ArcaneStorage.SETTINGS.bandRange) {
         return "arcanestorage_band_toofar";
      }

      // Released first, so retuning to another channel on the same band does not leave the old one held. A device
      // holding two channels would show up twice in the station's list and be jumped to twice by a walk.
      this.releaseEverywhere(tile);
      band.set(channel, tile);
      this.changed();
      return null;
   }

   /** Drops an Access Point from whatever channel it held, on any band. */
   public void releaseEverywhere(long tile) {
      boolean any = false;
      for (Band band : this.bands.values()) {
         if (band.channelOf(tile) != 0) {
            band.release(tile);
            any = true;
         }
      }

      if (any) {
         this.changed();
      }
   }

   /**
    * Drops claims and bands whose devices are no longer there.
    *
    * <p><b>Only for tiles whose region is loaded.</b> That distinction is the whole difficulty: an unloaded tile reads
    * as empty, so pruning on "nothing there" would free every channel of every silo the moment nobody was standing in
    * it -- and then hand those channels to the next Access Point, leaving the silo permanently dark for a reason its
    * owner never did anything to cause. {@code RegionManager.isTileLoaded} is the honest question; a null check on
    * the object entity is not.
    *
    * <p>Breaking either device with a tool already withdraws it from the index directly, so this is the path for
    * everything else: a world edited by hand, an object removed by another mod, a test's scratch world being cleared.
    * Without it a claim outlives its device forever and the channel can never be reused.
    */
   public void prune(Level level) {
      if (level == null || !level.isServer()) {
         return;
      }

      boolean changed = false;
      for (Band band : new ArrayList<>(this.bands.values())) {
         if (isLoaded(level, band.stationX, band.stationY)
               && !(level.getObject(band.stationX, band.stationY)
                     instanceof arcanestorage.object.ArcaneBaseStationObject)) {
            this.bands.remove(band.id);
            changed = true;
            continue;
         }

         long[] channels = band.rawChannels();
         for (int i = 0; i < channels.length; i++) {
            if (channels[i] == Band.FREE) {
               continue;
            }

            int x = tileX(channels[i]);
            int y = tileY(channels[i]);
            if (!isLoaded(level, x, y)) {
               continue;
            }

            necesse.entity.objectEntity.ObjectEntity at = level.entityManager.getObjectEntity(x, y);
            boolean holds = at instanceof arcanestorage.objectentity.ArcaneAccessPointObjectEntity
                  && ((arcanestorage.objectentity.ArcaneAccessPointObjectEntity)at).getBandId() == band.id
                  && ((arcanestorage.objectentity.ArcaneAccessPointObjectEntity)at).getChannel() == i + 1;
            if (!holds) {
               channels[i] = Band.FREE;
               changed = true;
            }
         }
      }

      if (changed) {
         this.changed();
      }
   }

   /**
    * Whether a tile's region is in memory.
    *
    * <p>{@code RegionManager.isTileLoaded} rather than a null check on {@code getRegionByTile(x, y, false)}: the
    * latter goes through a one-entry cache that {@code removeRegion} never invalidates, so it hands back regions that
    * have already been unloaded and disposed. That cost an afternoon in the harness once.
    */
   private static boolean isLoaded(Level level, int x, int y) {
      return level.regionManager != null && level.regionManager.isTileLoaded(x, y);
   }

   /** Records whether a band is transmitting. Called from the station's heartbeat. */
   public void setLive(Band band, boolean live) {
      if (band.live != live) {
         band.live = live;
         this.changed();
      }
   }

   /**
    * Tiles the network continues from at this tile, or null.
    *
    * <p>The one method the traversal calls. Everything expensive -- deciding which links exist, whether the band is
    * transmitting, whether each end is in range -- happened when the index last changed.
    */
   public long[] linksFrom(int x, int y) {
      this.ensureLinks();
      return this.links.get(tile(x, y));
   }

   /**
    * The link lookup for a level's walks, or null when the level has no live band.
    *
    * <p>Null rather than an empty lookup, so a world without a Base Station pays nothing at all for the feature: the
    * traversal skips the whole branch on a null.
    */
   public static arcanestorage.network.UnitNetwork.LinkLookup linksOn(Level level) {
      BandIndex index = existing(level);
      return index == null || !index.hasLinks() ? null : index::linksFrom;
   }

   public boolean hasLinks() {
      this.ensureLinks();
      return !this.links.isEmpty();
   }

   private void ensureLinks() {
      if (this.linksBuiltUnder == this.version) {
         return;
      }

      this.linksBuiltUnder = this.version;
      this.links.clear();

      int range = ArcaneStorage.SETTINGS.bandRange;
      for (Band band : this.bands.values()) {
         if (!band.live) {
            continue;
         }

         long station = tile(band.stationX, band.stationY);
         List<Long> reachable = new ArrayList<>();

         for (long ap : band.occupants()) {
            if (distance(band.stationX, band.stationY, tileX(ap), tileY(ap)) > range) {
               continue;
            }

            reachable.add(ap);
            this.links.put(ap, new long[]{station});
         }

         if (!reachable.isEmpty()) {
            long[] fromStation = new long[reachable.size()];
            for (int i = 0; i < fromStation.length; i++) {
               fromStation[i] = reachable.get(i);
            }

            this.links.put(station, fromStation);
         }
      }
   }

   /**
    * Tiles apart, floored, as {@code Reach} measures a wireless pairing.
    *
    * <p>The same metric for the same reason: it is the number the refusal message shows, so it has to be the number
    * a player would arrive at by counting, and Euclidean is what "200 blocks away" means to anyone who has not read
    * the source.
    */
   public static int distance(int ax, int ay, int bx, int by) {
      double dx = ax - bx;
      double dy = ay - by;
      return (int)Math.floor(Math.sqrt(dx * dx + dy * dy));
   }

   /**
    * Marks the index changed: the link map is stale, and so is every shared network index.
    *
    * <p>The second half is not optional. Claiming a channel joins two sets of units into one network, and the
    * counts a bus is acting on were computed for the network as it was a moment ago.
    */
   private void changed() {
      this.version++;
      NetworkIndexes.topologyChanged();
   }

   @Override
   public void addSaveData(SaveData save) {
      super.addSaveData(save);
      for (Band band : this.bands()) {
         SaveData one = new SaveData("BAND");
         one.addInt("id", band.id);
         one.addInt("x", band.stationX);
         one.addInt("y", band.stationY);
         one.addUnsafeString("tier", band.tier.name());

         long[] channels = band.rawChannels();
         for (int i = 0; i < channels.length; i++) {
            if (channels[i] != Band.FREE) {
               SaveData claim = new SaveData("CH");
               claim.addInt("n", i + 1);
               claim.addInt("x", tileX(channels[i]));
               claim.addInt("y", tileY(channels[i]));
               one.addSaveData(claim);
            }
         }

         save.addSaveData(one);
      }
   }

   @Override
   public void applyLoadData(LoadData save) {
      super.applyLoadData(save);
      this.bands.clear();

      for (LoadData one : save.getLoadDataByName("BAND")) {
         UnitTier tier;
         try {
            tier = UnitTier.valueOf(one.getUnsafeString("tier", UnitTier.DEMONIC.name()));
         } catch (IllegalArgumentException e) {
            tier = UnitTier.DEMONIC;
         }

         Band band = new Band(one.getInt("id", 1), one.getInt("x", 0), one.getInt("y", 0), tier);

         // Sized from the saved claims as well as from the tier, so lowering the channel count in the config cannot
         // silently drop a player's Access Point on load. The station's next validation reports the overflow.
         long[] channels = band.rawChannels();
         List<LoadData> claims = one.getLoadDataByName("CH");
         int highest = channels.length;
         for (LoadData claim : claims) {
            highest = Math.max(highest, claim.getInt("n", 0));
         }

         if (highest > channels.length) {
            long[] grown = new long[highest];
            Arrays.fill(grown, Band.FREE);
            System.arraycopy(channels, 0, grown, 0, channels.length);
            band.setRawChannels(grown);
         }

         for (LoadData claim : claims) {
            band.set(claim.getInt("n", 0), tile(claim.getInt("x", 0), claim.getInt("y", 0)));
         }

         this.bands.put(band.id, band);
      }

      this.version++;
   }
}
