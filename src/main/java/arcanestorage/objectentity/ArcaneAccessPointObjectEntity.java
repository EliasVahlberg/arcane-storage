package arcanestorage.objectentity;

import java.util.ArrayList;
import java.util.List;

import arcanestorage.band.Band;
import arcanestorage.band.BandIndex;
import arcanestorage.band.BandState;
import arcanestorage.network.NetworkConductor;
import arcanestorage.network.NetworkIndexes;
import arcanestorage.network.NetworkStorage;
import arcanestorage.network.UnitNetwork;
import arcanestorage.remote.RemoteTerminal;
import necesse.engine.localization.Localization;
import necesse.engine.network.PacketReader;
import necesse.engine.network.PacketWriter;
import necesse.engine.save.LoadData;
import necesse.engine.save.SaveData;
import necesse.entity.objectEntity.ObjectEntity;
import necesse.level.maps.Level;

/**
 * The Arcane Access Point: the far end of a band, and the tile that makes a distant cluster part of a network.
 *
 * <p>What it stores is what the player chose -- a band and a channel -- and nothing about the result. Whether it is
 * actually connected is decided by {@link BandIndex}, which is the only thing that can arbitrate a channel, and is
 * re-read whenever the layout changes. So a player's setting survives the station being broken, a region unloading,
 * or the band going quiet: the Access Point keeps asking for channel 3 of band 1, and lights up again when that is
 * once more something it can have.
 *
 * <p><b>It contributes nothing itself.</b> No inventory, no capacity, no rules. Everything that gives a silo its
 * contents is the ordinary units and conduits around it, discovered by the ordinary walk -- which is the whole point
 * of the design: a bridged cluster is not a special kind of member, it is the same members reached a different way.
 */
public class ArcaneAccessPointObjectEntity extends ObjectEntity {

   /** Must never change between versions. */
   public static final String TYPE = "arcanestorageaccesspoint";

   /** Long enough for "Mine Silo" and short enough for a row in the station's list. As a bus's name. */
   public static final int MAX_NAME_LENGTH = 24;

   private static final int PIN_INTERVAL = 20;

   /** The band the player tuned to, or 0 for none. Persisted: it is a setting, not a result. */
   private int bandId;

   /** The channel the player asked for, or 0. Persisted for the same reason. */
   private int channel;

   private String customName;

   private BandState state = BandState.NO_BAND;

   private final arcanestorage.band.LayoutWatch watch = new arcanestorage.band.LayoutWatch();

   private int ticksUntilPin;

   public ArcaneAccessPointObjectEntity(Level level, int x, int y) {
      super(level, TYPE, x, y);
   }

   public int getBandId() {
      return this.bandId;
   }

   public int getChannel() {
      return this.channel;
   }

   public BandState getState() {
      return this.state;
   }

   public boolean isInactive() {
      return !this.state.isActive();
   }

   /**
    * Tunes this Access Point, or refuses.
    *
    * <p>The one place a channel is claimed, called from the panel and from the harness alike, so a refusal cannot
    * differ between the two. A band of 0 means "tune to nothing", which is how a player disconnects a silo without
    * breaking anything.
    *
    * @return a locale key under {@code ui} explaining the refusal, or null when it was accepted
    */
   public String tune(int bandId, int channel) {
      Level level = this.getLevel();
      if (level == null || !level.isServer()) {
         return null;
      }

      BandIndex index = BandIndex.of(level);
      long tile = BandIndex.tile(this.tileX, this.tileY);

      if (bandId <= 0) {
         index.releaseEverywhere(tile);
         this.bandId = 0;
         this.channel = 0;
         this.state = BandState.NO_BAND;
         this.invalidate();
         this.markDirty();
         return null;
      }

      String refusal = index.claim(bandId, channel, this.tileX, this.tileY);
      if (refusal != null) {
         return refusal;
      }

      this.bandId = bandId;
      this.channel = channel;
      this.invalidate();
      this.markDirty();

      // The station reports how many of its channels are taken, and that number just changed. Left alone it would
      // catch up on the next thing anyone placed, which for a player watching both panels reads as a bug.
      Band band = index.byId(bandId);
      if (band != null) {
         ObjectEntity station = level.entityManager.getObjectEntity(band.stationX, band.stationY);
         if (station instanceof ArcaneBaseStationObjectEntity) {
            ((ArcaneBaseStationObjectEntity)station).invalidate();
         }
      }

      return null;
   }

   /** Drops this Access Point's claim. Called when it is broken. */
   public void releaseClaim() {
      Level level = this.getLevel();
      if (level != null && level.isServer()) {
         BandIndex.of(level).releaseEverywhere(BandIndex.tile(this.tileX, this.tileY));
      }
   }

   public void invalidate() {
      this.watch.invalidate();
   }

   @Override
   public void serverTick() {
      super.serverTick();
      Level level = this.getLevel();
      if (level == null || !level.isServer()) {
         return;
      }

      if (this.watch.due()) {
         this.validate(level);
         this.watch.adopt();
      }

      // Its own cluster, kept in memory while the Access Point itself is. The station pins this tile; this pins what
      // this tile is attached to, which is the only place that knows how far the silo actually extends.
      if (--this.ticksUntilPin <= 0) {
         this.ticksUntilPin = PIN_INTERVAL;
         if (this.state.isActive()) {
            this.pinCluster(level);
         }
      }
   }

   /** Re-reads the index and settles on a state. No walking: every answer is a lookup. */
   private void validate(Level level) {
      // Pruned from here as well as from a station's tick, because a band whose station is gone has nothing left that
      // ticks: without this, an Access Point would keep reporting itself connected to a band that no longer exists,
      // since the flag saying the band transmits was last written by a station that is no longer there to update it.
      BandIndex.of(level).prune(level);

      BandState next = this.evaluate(level);
      if (next != this.state) {
         this.state = next;
         this.markDirty();
         this.invalidateStation();
      }
   }

   private BandState evaluate(Level level) {
      if (this.bandId <= 0 || this.channel <= 0) {
         return BandState.NO_BAND;
      }

      BandIndex index = BandIndex.existing(level);
      Band band = index == null ? null : index.byId(this.bandId);
      if (band == null) {
         return BandState.BAND_GONE;
      }

      if (band.occupant(this.channel) != BandIndex.tile(this.tileX, this.tileY)) {
         return BandState.NOT_CLAIMED;
      }

      if (BandIndex.distance(band.stationX, band.stationY, this.tileX, this.tileY)
            > arcanestorage.ArcaneStorage.SETTINGS.bandRange) {
         return BandState.TOO_FAR;
      }

      return band.live ? BandState.ACTIVE : BandState.BAND_DOWN;
   }

   private void pinCluster(Level level) {
      List<long[]> tiles = new ArrayList<>();
      tiles.add(new long[]{this.tileX, this.tileY});

      // The walk follows no band links: what is being pinned is this silo, and the other end of the band pins itself
      // or is where a player is standing.
      List<NetworkStorage> members = UnitNetwork.discover(this.tileX, this.tileY, (x, y) -> {
         ObjectEntity candidate = level.entityManager.getObjectEntity(x, y);
         return candidate instanceof NetworkStorage && ((NetworkStorage)candidate).isOnNetwork()
            ? (NetworkStorage)candidate
            : null;
      }, (x, y) -> level.getObject(x, y) instanceof NetworkConductor,
            StorageTerminalObjectEntity.MAX_UNITS, StorageTerminalObjectEntity.MAX_CONDUITS);

      for (NetworkStorage member : members) {
         ObjectEntity entity = member.getObjectEntity();
         if (entity != null) {
            tiles.add(new long[]{entity.tileX, entity.tileY});
         }
      }

      RemoteTerminal.pinRegions(level, tiles);
   }

   /** What to call this Access Point: what the player named it, or its band and channel. */
   public String name() {
      if (this.customName != null && !this.customName.isEmpty()) {
         return this.customName;
      }

      if (this.bandId <= 0 || this.channel <= 0) {
         return Localization.translate("object", "arcanestorageaccesspoint");
      }

      return Localization.translate("ui", "arcanestorage_band_apname",
            "band", String.valueOf(this.bandId), "channel", String.valueOf(this.channel));
   }

   public String getCustomName() {
      return this.customName == null ? "" : this.customName;
   }

   /** Renames it. A label and nothing else, so there is nothing to refuse -- as with a bus. */
   public void setCustomName(String name) {
      String trimmed = name == null ? "" : name.replaceAll("[\\p{Cntrl}\u00A7]", "").trim();
      if (trimmed.length() > MAX_NAME_LENGTH) {
         trimmed = trimmed.substring(0, MAX_NAME_LENGTH);
      }

      String next = trimmed.isEmpty() ? null : trimmed;
      if (java.util.Objects.equals(this.customName, next)) {
         return;
      }

      this.customName = next;
      this.markDirty();

      // The station's list shows this name, and nothing about a rename changes the layout -- so without this the row
      // would keep the old name until the next thing anybody placed.
      this.invalidateStation();
   }

   /** Tells this Access Point's station to rebuild its channel list. */
   private void invalidateStation() {
      Level level = this.getLevel();
      BandIndex index = level == null || !level.isServer() ? null : BandIndex.existing(level);
      Band band = index == null ? null : index.byId(this.bandId);
      if (band == null) {
         return;
      }

      ObjectEntity station = level.entityManager.getObjectEntity(band.stationX, band.stationY);
      if (station instanceof ArcaneBaseStationObjectEntity) {
         ((ArcaneBaseStationObjectEntity)station).invalidate();
      }
   }

   @Override
   public void setupContentPacket(PacketWriter writer) {
      super.setupContentPacket(writer);
      writer.putNextEnum(this.state);
      writer.putNextShortUnsigned(Math.max(0, Math.min(65535, this.bandId)));
      writer.putNextShortUnsigned(Math.max(0, Math.min(65535, this.channel)));
      writer.putNextString(this.customName == null ? "" : this.customName);
   }

   @Override
   public void applyContentPacket(PacketReader reader) {
      super.applyContentPacket(reader);
      this.state = reader.getNextEnum(BandState.class);
      this.bandId = reader.getNextShortUnsigned();
      this.channel = reader.getNextShortUnsigned();
      String name = reader.getNextString();
      this.customName = name.isEmpty() ? null : name;
   }

   @Override
   public boolean shouldRequestPacket() {
      return true;
   }

   @Override
   public void addSaveData(SaveData save) {
      super.addSaveData(save);
      save.addInt("bandId", this.bandId);
      save.addInt("channel", this.channel);
      if (this.customName != null) {
         // Safe rather than unsafe: this is whatever a player typed, and the format's own delimiter would corrupt
         // the rest of this entity's data.
         save.addSafeString("customName", this.customName);
      }
   }

   @Override
   public void applyLoadData(LoadData save) {
      super.applyLoadData(save);
      this.bandId = save.getInt("bandId", 0, false);
      this.channel = save.getInt("channel", 0, false);
      String name = save.getSafeString("customName", "", false);
      this.customName = name.isEmpty() ? null : name;
   }
}
