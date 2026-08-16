package arcanestorage.container;

import java.util.ArrayList;
import java.util.List;
import necesse.engine.localization.message.GameMessage;
import necesse.engine.network.Packet;
import necesse.engine.network.PacketReader;
import necesse.engine.network.PacketWriter;
import necesse.entity.objectEntity.ObjectEntity;
import necesse.level.maps.Level;
import arcanestorage.network.NetworkStations;
import arcanestorage.network.NetworkStorage;

/**
 * The network's membership as the server sees it, sent to the client that is opening a terminal.
 *
 * <h2>Why the server has to say, rather than both sides working it out</h2>
 *
 * <p>Membership used to be discovered independently per side: the client walked the network itself, the object
 * entities were on its level because the player was standing next to them, and the two sides arrived at the same slot
 * list. That holds only while the whole network is inside the client's loaded regions, and <b>a wireless link violates
 * it by construction</b> -- being far away is the entire point of the feature.
 *
 * <p>Two independent reasons a client cannot do it, both checked in the engine's source rather than assumed:
 *
 * <ol>
 *   <li>{@code BandIndex} is {@code LevelData}, and {@code LevelDataManager} is populated only from
 *       {@code loadSaveData}. There is no packet path for level data at all, so a client's band index does not exist,
 *       {@code BandIndex.linksOn} returns null there, and a client-side walk cannot cross a link.
 *   <li>Syncing the links would not be enough. The server sends object entities per region, through
 *       {@code ServerClient.addLoadedRegion}, so storage a few hundred tiles away is not on the client at all. A walk
 *       that crossed the link would arrive and find nothing.
 * </ol>
 *
 * <p>The symptom was storage that accepted items and showed none: every write is resolved on the server, which was
 * always right, while every read -- the storage view, the capacity readout, the Stations tab -- is built from the slots
 * the client registered from its own shorter walk. Note that a server-side test suite cannot see this class of fault;
 * these were found in play with 258 scenarios passing.
 *
 * <h2>Tiles as well as sizes</h2>
 *
 * <p>Sizes alone would be enough to register matching slots, and the remote path sends only sizes because a client on
 * another level could not use a tile for anything. Here the tiles earn their bytes: a locally-opened terminal can
 * resolve most of its network for real, and a real member keeps the engine's own inventory sync, the live name and
 * everything else an entity carries. So each entry is resolved where possible and stood in for only where it is not,
 * which leaves the common case -- a base with no wireless links at all -- behaving exactly as it did.
 *
 * <p>What is <i>not</i> conditional is the membership itself: the list, its order and therefore every slot index come
 * from the server in all cases. That is the invariant that was broken, and per-tile resolution is a detail underneath
 * it rather than an exception to it.
 */
public final class NetworkShape {

   /** Station Unit tiles, in the tile order the server enumerated, packed as {@code x << 32 | y}. */
   public final long[] stationTiles;

   /** Socket counts, one per Station Unit, in the same order. */
   public final int[] socketCounts;

   /** Storage Unit tiles, in tile order. */
   public final long[] unitTiles;

   /** Slot counts, one per Storage Unit, in the same order. */
   public final int[] unitSizes;

   public NetworkShape(long[] stationTiles, int[] socketCounts, long[] unitTiles, int[] unitSizes) {
      this.stationTiles = stationTiles;
      this.socketCounts = socketCounts;
      this.unitTiles = unitTiles;
      this.unitSizes = unitSizes;
   }

   /**
    * The shape of a network the server has already walked.
    *
    * <p>A member whose object entity is missing contributes a zero tile, which resolves to a stand-in on the far side
    * rather than to the wrong entity. That cannot happen for a locally-opened terminal, where every member came from a
    * walk over real entities a moment earlier, but it costs one branch to make the packet unable to lie.
    */
   public static NetworkShape of(List<NetworkStations> stationUnits, List<NetworkStorage> units) {
      long[] stationTiles = new long[stationUnits.size()];
      int[] socketCounts = new int[stationUnits.size()];
      for (int i = 0; i < stationTiles.length; i++) {
         NetworkStations unit = stationUnits.get(i);
         stationTiles[i] = tileOf(unit.getObjectEntity());
         socketCounts[i] = unit.getInventory() == null ? 0 : unit.getInventory().getSize();
      }

      long[] unitTiles = new long[units.size()];
      int[] unitSizes = new int[units.size()];
      for (int i = 0; i < unitTiles.length; i++) {
         NetworkStorage unit = units.get(i);
         unitTiles[i] = tileOf(unit.getObjectEntity());
         unitSizes[i] = unit.getInventory() == null ? 0 : unit.getInventory().getSize();
      }

      return new NetworkShape(stationTiles, socketCounts, unitTiles, unitSizes);
   }

   private static long tileOf(ObjectEntity entity) {
      return entity == null ? 0L : (long)entity.tileX << 32 | (long)entity.tileY & 0xFFFFFFFFL;
   }

   private static int tileX(long tile) {
      return (int)(tile >> 32);
   }

   private static int tileY(long tile) {
      return (int)tile;
   }

   public void writePacket(PacketWriter writer) {
      writer.putNextShortUnsigned(this.socketCounts.length);
      for (int i = 0; i < this.socketCounts.length; i++) {
         writer.putNextLong(this.stationTiles[i]);
         writer.putNextShortUnsigned(this.socketCounts[i]);
      }

      writer.putNextShortUnsigned(this.unitSizes.length);
      for (int i = 0; i < this.unitSizes.length; i++) {
         writer.putNextLong(this.unitTiles[i]);
         writer.putNextShortUnsigned(this.unitSizes[i]);
      }
   }

   public static NetworkShape fromPacket(PacketReader reader) {
      int stations = reader.getNextShortUnsigned();
      long[] stationTiles = new long[stations];
      int[] socketCounts = new int[stations];
      for (int i = 0; i < stations; i++) {
         stationTiles[i] = reader.getNextLong();
         socketCounts[i] = reader.getNextShortUnsigned();
      }

      int units = reader.getNextShortUnsigned();
      long[] unitTiles = new long[units];
      int[] unitSizes = new int[units];
      for (int i = 0; i < units; i++) {
         unitTiles[i] = reader.getNextLong();
         unitSizes[i] = reader.getNextShortUnsigned();
      }

      return new NetworkShape(stationTiles, socketCounts, unitTiles, unitSizes);
   }

   public Packet toPacket() {
      Packet packet = new Packet();
      this.writePacket(new PacketWriter(packet));
      return packet;
   }

   /** The Station Units, resolved on the given level where possible and stood in for where not. */
   public List<NetworkStations> stationUnits(Level level, GameMessage name) {
      List<NetworkStations> resolved = new ArrayList<>(this.socketCounts.length);
      for (int i = 0; i < this.socketCounts.length; i++) {
         NetworkStations real = resolve(level, this.stationTiles[i], NetworkStations.class);
         resolved.add(real != null && sized(real.getInventory(), this.socketCounts[i])
               ? real
               : new MirroredMember(this.socketCounts[i], name, i));
      }

      return resolved;
   }

   /** The Storage Units, resolved on the given level where possible and stood in for where not. */
   public List<NetworkStorage> units(Level level, GameMessage name) {
      List<NetworkStorage> resolved = new ArrayList<>(this.unitSizes.length);
      for (int i = 0; i < this.unitSizes.length; i++) {
         NetworkStorage real = resolve(level, this.unitTiles[i], NetworkStorage.class);
         resolved.add(real != null && sized(real.getInventory(), this.unitSizes[i])
               ? real
               : new MirroredMember(this.unitSizes[i], name, i));
      }

      return resolved;
   }

   /**
    * The entity at a tile when it is the kind of member the server said it was, otherwise null.
    *
    * <p>Type-checked rather than cast, because a client's copy of a region can be older than the server's: a unit
    * broken and replaced by something else while the packet was in flight would otherwise be read as the member the
    * server described.
    */
   private static <T> T resolve(Level level, long tile, Class<T> type) {
      if (level == null || tile == 0L) {
         return null;
      }

      ObjectEntity entity = level.entityManager.getObjectEntity(tileX(tile), tileY(tile));
      return type.isInstance(entity) ? type.cast(entity) : null;
   }

   /**
    * Whether a resolved member's inventory is the size the server described.
    *
    * <p>A mismatch means the client's copy is stale -- most likely a unit upgraded to a larger tier that this client
    * has not been told about yet. Standing in is then strictly better than using it: slot indices stay aligned with
    * the server's, and the contents arrive by mirror a tick later. Using the real one would shift every index after
    * it, and a shifted index is a player dragging an item into a slot that means something else.
    */
   private static boolean sized(necesse.inventory.Inventory inventory, int expected) {
      return inventory != null && inventory.getSize() == expected;
   }
}
