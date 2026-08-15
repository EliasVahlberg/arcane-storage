package arcanestorage.band;

import java.util.ArrayList;
import java.util.List;

import necesse.engine.network.PacketReader;
import necesse.engine.network.PacketWriter;
import necesse.level.maps.Level;

/**
 * A band as an Access Point's panel offers it: enough to choose from, and nothing a client could act on.
 *
 * <p>Sent in the panel's open packet because {@link BandIndex} is server-side and a client's level has no copy of it.
 * Deliberately a snapshot: the picker is a menu, and a menu that changed under the player's cursor would be worse
 * than one that is a few seconds old. A stale choice is refused by the server and the reason says why.
 */
public final class BandOption {

   public final int id;

   public final int stationX;

   public final int stationY;

   /** Tiles from the Access Point doing the asking, so the panel can show reach without recomputing it. */
   public final int distance;

   /** True where that channel is already held by another Access Point. Indexed from channel 1. */
   public final boolean[] taken;

   public BandOption(int id, int stationX, int stationY, int distance, boolean[] taken) {
      this.id = id;
      this.stationX = stationX;
      this.stationY = stationY;
      this.distance = distance;
      this.taken = taken;
   }

   public int channelCount() {
      return this.taken.length;
   }

   public boolean isTaken(int channel) {
      return channel >= 1 && channel <= this.taken.length && this.taken[channel - 1];
   }

   /** Whether this band reaches the Access Point that was offered it. */
   public boolean inRange(int range) {
      return this.distance <= range;
   }

   /**
    * Every band on the level, seen from a tile.
    *
    * <p>Out-of-range bands are included rather than filtered out, and that is a deliberate choice about how a player
    * learns the rule: a list that silently omitted the band they built would read as the mod having lost it, while a
    * row showing 340 tiles against a limit of 200 explains itself and tells them what to do about it.
    */
   public static List<BandOption> visibleFrom(Level level, int tileX, int tileY) {
      List<BandOption> options = new ArrayList<>();
      BandIndex index = BandIndex.existing(level);
      if (index == null) {
         return options;
      }

      // Pruned here as well as from a station's heartbeat, because a band whose station is gone has nothing left to
      // tick and would otherwise sit in this list forever, offering channels on a band that cannot transmit.
      index.prune(level);

      long self = BandIndex.tile(tileX, tileY);
      for (Band band : index.bands()) {
         boolean[] taken = new boolean[band.channelCount()];
         for (int channel = 1; channel <= taken.length; channel++) {
            long occupant = band.occupant(channel);
            taken[channel - 1] = occupant != Band.FREE && occupant != self;
         }

         options.add(new BandOption(band.id, band.stationX, band.stationY,
               BandIndex.distance(band.stationX, band.stationY, tileX, tileY), taken));
      }

      return options;
   }

   public void writePacket(PacketWriter writer) {
      writer.putNextShortUnsigned(this.id);
      writer.putNextInt(this.stationX);
      writer.putNextInt(this.stationY);
      writer.putNextShortUnsigned(Math.max(0, Math.min(65535, this.distance)));
      writer.putNextShortUnsigned(this.taken.length);
      for (boolean channel : this.taken) {
         writer.putNextBoolean(channel);
      }
   }

   public static BandOption readPacket(PacketReader reader) {
      int id = reader.getNextShortUnsigned();
      int x = reader.getNextInt();
      int y = reader.getNextInt();
      int distance = reader.getNextShortUnsigned();
      boolean[] taken = new boolean[reader.getNextShortUnsigned()];
      for (int i = 0; i < taken.length; i++) {
         taken[i] = reader.getNextBoolean();
      }

      return new BandOption(id, x, y, distance, taken);
   }

   public static void writeAll(PacketWriter writer, List<BandOption> options) {
      writer.putNextShortUnsigned(options.size());
      for (BandOption option : options) {
         option.writePacket(writer);
      }
   }

   public static List<BandOption> readAll(PacketReader reader) {
      int count = reader.getNextShortUnsigned();
      List<BandOption> options = new ArrayList<>(count);
      for (int i = 0; i < count; i++) {
         options.add(readPacket(reader));
      }

      return options;
   }
}
