package arcanestorage.objectentity;

import java.util.ArrayList;
import java.util.List;

import arcanestorage.band.Band;
import arcanestorage.band.BandIndex;
import arcanestorage.band.BandState;
import arcanestorage.band.ChannelRow;
import arcanestorage.network.NetworkConductor;
import arcanestorage.network.NetworkIndexes;
import arcanestorage.network.NetworkStorage;
import arcanestorage.network.UnitNetwork;
import arcanestorage.object.ArcaneBaseStationObject;
import arcanestorage.object.UnitTier;
import arcanestorage.remote.RemoteTerminal;
import necesse.engine.network.PacketReader;
import necesse.engine.network.PacketWriter;
import necesse.entity.objectEntity.ObjectEntity;
import necesse.level.maps.Level;

/**
 * The Arcane Base Station: the one device on a network that generates a frequency band.
 *
 * <p><b>It holds no band state.</b> The band lives in {@link BandIndex}, the level's own persisted record, and this
 * entity is the thing that keeps it honest: it registers the band, decides once per layout change whether the band
 * transmits, and keeps its Access Points' regions in memory. Everything a player configures lives at the other end,
 * on the Access Point -- which is why this station's panel is a view rather than an editor.
 *
 * <h2>What has to be true for a band to transmit</h2>
 *
 * <ol>
 *   <li><b>A Wireless Transceiver on the station's own cluster.</b> The transceiver is the network's antenna --
 *       already, for the wireless terminal -- and the band is the same hardware pointed at the player's own
 *       outbuildings instead of at the player. Requiring it means the two wireless features are one investment
 *       rather than two, and it gives the station's sprite something true to be dark about.
 *   <li><b>Exactly one station on that cluster.</b> Two stations would mean two bands over one set of units, and an
 *       Access Point tuned to either would bridge the same network twice. Both go dark instead, for the reason a
 *       bus rule conflict stops both buses: a silent winner is worse than a visible refusal.
 * </ol>
 *
 * <p>Both are read from a walk that <b>does not follow band links</b>. That is a hard requirement rather than an
 * optimisation: the walk that answers "may this band be crossed" cannot itself cross bands, or it would ask itself
 * the same question forever.
 *
 * <h2>Why this recomputes on layout changes only</h2>
 *
 * <p>Neither condition can change without something being placed or broken, and every network object already bumps
 * {@link NetworkIndexes#topologyVersion()} when that happens. So the heartbeat here is a long comparison, and the
 * walk happens on the tick after a change rather than once a second forever. An idle base costs nothing, which is
 * the property the whole mod is measured against.
 */
public class ArcaneBaseStationObjectEntity extends necesse.entity.objectEntity.InventoryObjectEntity {

   /** Must never change between versions: a mismatch makes the entity's save data load as invalid. */
   public static final String TYPE = "arcanestoragebasestation";

   /**
    * Zero, and the reason is the in-place upgrade rather than storage.
    *
    * <p>A station holds nothing -- it is not a container and cannot be opened as one. But
    * {@code UnitUpgrade.attempt} requires an {@code InventoryObjectEntity} at the tile, because upgrading a unit
    * moves its slots into the replacement, and a station has to be upgradeable in place or upgrading one would
    * unregister its band and leave every silo on it retuning by hand. An empty inventory satisfies that machinery
    * with a transfer that copies nothing, which is a smaller price than a second upgrade path.
    */
   public static final int SLOTS = 0;

   /** Ticks between refreshing the hold on the Access Points' regions. A hold lasts thirty seconds. */
   private static final int PIN_INTERVAL = 20;

   private BandState state = BandState.NO_TRANSCEIVER;

   /** The other station on this network when {@link BandState#STATION_CONFLICT}, for the message. */
   private int conflictX;

   private int conflictY;

   /** How many channels the band offers and how many are taken, for the client's hover tip. */
   private int channelCount;

   private int channelsUsed;

   /**
    * The band's channels as the panel shows them, free ones included, synced to clients.
    *
    * <p>Pushed with the entity rather than fetched by the panel. Sixteen rows is small, changes are rare -- a player
    * tuning a silo -- and the alternative is a client asking once a second for a list that almost never differs,
    * which is the polling this mod removes everywhere else.
    */
   private List<ChannelRow> rows = new ArrayList<>();

   /** The band's number, for the panel's title and for the player to read off and type into an Access Point. */
   private int bandId;

   private final arcanestorage.band.LayoutWatch watch = new arcanestorage.band.LayoutWatch();

   private int ticksUntilPin;

   public ArcaneBaseStationObjectEntity(Level level, int x, int y) {
      super(level, x, y, SLOTS);
      // InventoryObjectEntity hardcodes the type "inventory" in its only constructor, and the type is what the save
      // consistency check compares. Reassigned here for the same reason the terminal does it.
      this.type = TYPE;
   }

   /** This station's tier, read from the object standing on the tile rather than stored twice. */
   public UnitTier tier() {
      Level level = this.getLevel();
      if (level != null && level.getObject(this.tileX, this.tileY) instanceof ArcaneBaseStationObject) {
         return ((ArcaneBaseStationObject)level.getObject(this.tileX, this.tileY)).tier;
      }

      return UnitTier.DEMONIC;
   }

   /** This station's band, registering one if the level has never seen this station. Server-side. */
   public Band band() {
      Level level = this.getLevel();
      if (level == null || !level.isServer()) {
         return null;
      }

      return BandIndex.of(level).register(this.tileX, this.tileY, this.tier());
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

      // Kept in memory rather than merely found: an Access Point in an unloaded region reads as an empty tile, and a
      // silo that vanishes when nobody stands in it is a silo whose items appear to vanish. Each Access Point pins
      // its own cluster from its own tick, so this only has to reach far enough for that tick to happen.
      if (--this.ticksUntilPin <= 0) {
         this.ticksUntilPin = PIN_INTERVAL;
         this.pinAccessPoints(level);
      }
   }

   private void pinAccessPoints(Level level) {
      Band band = this.band();
      if (band == null || !band.live) {
         return;
      }

      List<long[]> tiles = new ArrayList<>();
      for (int channel = 1; channel <= band.channelCount(); channel++) {
         long occupant = band.occupant(channel);
         if (occupant != Band.FREE) {
            tiles.add(new long[]{BandIndex.tileX(occupant), BandIndex.tileY(occupant)});
         }
      }

      RemoteTerminal.pinRegions(level, tiles);
   }

   /**
    * Decides whether the band transmits, and records the state the client draws from.
    *
    * <p>One walk answers both questions, because both are about what is on this station's cluster and the walk is
    * the expensive part. The walk deliberately passes no link lookup -- see the class comment.
    */
   private void validate(Level level) {
      // Before anything is counted: a claim whose Access Point is gone would otherwise hold its channel forever, and
      // the station is the only device on a band that ticks reliably enough to be the one that notices.
      BandIndex.of(level).prune(level);

      final List<int[]> stations = new ArrayList<>();
      final boolean[] transceiver = {false};

      // Both questions are answered in the unit lookup rather than in the conductor test, because the lookup is
      // called for every tile the frontier reaches -- which includes the tiles the network merely touches. That is
      // the right set: "is there a transceiver on this network" means the same thing for a station as it does for a
      // terminal, namely that the network reaches a tile holding one.
      UnitNetwork.discover(this.tileX, this.tileY, (x, y) -> {
         ObjectEntity candidate = level.entityManager.getObjectEntity(x, y);
         if (candidate instanceof WirelessTransceiverObjectEntity) {
            transceiver[0] = true;
         } else if (candidate instanceof ArcaneBaseStationObjectEntity && (x != this.tileX || y != this.tileY)) {
            stations.add(new int[]{x, y});
         }

         return candidate instanceof NetworkStorage && ((NetworkStorage)candidate).isOnNetwork()
            ? (NetworkStorage)candidate
            : null;
      }, (x, y) -> level.getObject(x, y) instanceof NetworkConductor,
            StorageTerminalObjectEntity.MAX_UNITS, StorageTerminalObjectEntity.MAX_CONDUITS);
      BandState next;
      int otherX = 0;
      int otherY = 0;
      if (!stations.isEmpty()) {
         next = BandState.STATION_CONFLICT;
         // The lowest-ordered other station, so both stations in a pair name each other rather than naming whichever
         // one their own walk happened to reach first.
         int[] lowest = stations.get(0);
         for (int[] other : stations) {
            if (UnitNetwork.order(other[0], other[1]) < UnitNetwork.order(lowest[0], lowest[1])) {
               lowest = other;
            }
         }

         otherX = lowest[0];
         otherY = lowest[1];
      } else if (!transceiver[0]) {
         next = BandState.NO_TRANSCEIVER;
      } else {
         next = BandState.ACTIVE;
      }

      Band band = this.band();
      if (band != null) {
         BandIndex.of(level).setLive(band, next.isActive());
      }

      int count = band == null ? 0 : band.channelCount();
      int used = band == null ? 0 : count - band.freeChannels();
      int id = band == null ? 0 : band.id;
      List<ChannelRow> nextRows = band == null ? new ArrayList<>() : rowsOf(level, band);

      if (next != this.state || otherX != this.conflictX || otherY != this.conflictY
            || count != this.channelCount || used != this.channelsUsed || id != this.bandId
            || !sameRows(this.rows, nextRows)) {
         this.state = next;
         this.conflictX = otherX;
         this.conflictY = otherY;
         this.channelCount = count;
         this.channelsUsed = used;
         this.bandId = id;
         this.rows = nextRows;
         this.markDirty();
      }
   }

   /**
    * Forces the next tick to revalidate.
    *
    * <p>Needed because the two triggers are not the same thing: placing or breaking anything bumps the topology
    * version, but tuning an Access Point changes the channel counts this station reports without touching the
    * layout. {@link BandIndex} bumps the topology version on every change for the shared network index's sake, so
    * this is belt and braces -- and cheap, since it only clears a long.
    */
   public void invalidate() {
      this.watch.invalidate();
   }

   /**
    * The band's channels, in order, asking each Access Point for its own state.
    *
    * <p>An Access Point that has unloaded is reported by its last known name and as inactive rather than dropped from
    * the list: it is still holding the channel, and a row that vanished when nobody was standing next to a silo would
    * make the list look like it had lost something.
    */
   private static List<ChannelRow> rowsOf(Level level, Band band) {
      List<ChannelRow> rows = new ArrayList<>(band.channelCount());

      for (int channel = 1; channel <= band.channelCount(); channel++) {
         long occupant = band.occupant(channel);
         if (occupant == Band.FREE) {
            rows.add(ChannelRow.free(channel));
            continue;
         }

         int x = BandIndex.tileX(occupant);
         int y = BandIndex.tileY(occupant);
         ObjectEntity at = level.entityManager.getObjectEntity(x, y);
         BandState state = at instanceof ArcaneAccessPointObjectEntity
            ? ((ArcaneAccessPointObjectEntity)at).getState()
            : BandState.NOT_CLAIMED;
         String name = at instanceof ArcaneAccessPointObjectEntity
            ? ((ArcaneAccessPointObjectEntity)at).getCustomName()
            : "";

         rows.add(new ChannelRow(channel, x, y,
               name, BandIndex.distance(band.stationX, band.stationY, x, y), state));
      }

      return rows;
   }

   private static boolean sameRows(List<ChannelRow> a, List<ChannelRow> b) {
      if (a.size() != b.size()) {
         return false;
      }

      for (int i = 0; i < a.size(); i++) {
         if (!a.get(i).sameAs(b.get(i))) {
            return false;
         }
      }

      return true;
   }

   /** The channels as the client last heard them. Read by the panel. */
   public List<ChannelRow> getRows() {
      return this.rows;
   }

   public int getBandId() {
      return this.bandId;
   }

   public BandState getState() {
      return this.state;
   }

   public boolean isInactive() {
      return !this.state.isActive();
   }

   public int getConflictX() {
      return this.conflictX;
   }

   public int getConflictY() {
      return this.conflictY;
   }

   /** Channels the band offers, as the client last heard. */
   public int getChannelCount() {
      return this.channelCount;
   }

   public int getChannelsUsed() {
      return this.channelsUsed;
   }

   @Override
   public void setupContentPacket(PacketWriter writer) {
      super.setupContentPacket(writer);
      writer.putNextEnum(this.state);
      writer.putNextInt(this.conflictX);
      writer.putNextInt(this.conflictY);
      writer.putNextShortUnsigned(Math.max(0, Math.min(65535, this.channelCount)));
      writer.putNextShortUnsigned(Math.max(0, Math.min(65535, this.channelsUsed)));
      writer.putNextShortUnsigned(Math.max(0, Math.min(65535, this.bandId)));
      writer.putNextShortUnsigned(this.rows.size());
      for (ChannelRow row : this.rows) {
         row.writePacket(writer);
      }
   }

   @Override
   public void applyContentPacket(PacketReader reader) {
      super.applyContentPacket(reader);
      this.state = reader.getNextEnum(BandState.class);
      this.conflictX = reader.getNextInt();
      this.conflictY = reader.getNextInt();
      this.channelCount = reader.getNextShortUnsigned();
      this.channelsUsed = reader.getNextShortUnsigned();
      this.bandId = reader.getNextShortUnsigned();
      int count = reader.getNextShortUnsigned();
      List<ChannelRow> read = new ArrayList<>(count);
      for (int i = 0; i < count; i++) {
         read.add(ChannelRow.readPacket(reader));
      }

      this.rows = read;
   }

   /**
    * Asks the server for the state when a client first sees the tile, so the sprite is right before anything
    * changes. Same reason the buses do it.
    */
   @Override
   public boolean shouldRequestPacket() {
      return true;
   }
}
