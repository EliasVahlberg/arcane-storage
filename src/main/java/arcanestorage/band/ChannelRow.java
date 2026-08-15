package arcanestorage.band;

import necesse.engine.localization.Localization;
import necesse.engine.network.PacketReader;
import necesse.engine.network.PacketWriter;

/**
 * One row of a Base Station's channel list: a channel number and whatever is on it.
 *
 * <p>Assembled on the server and synced with the station's object entity, so the station's panel is a view of state
 * that arrives on its own rather than something the client has to ask for on a timer. The list is at most sixteen
 * rows, which is why it can travel this way at all.
 *
 * <p>Free channels are rows too. A player deciding where to put their next silo wants to see the numbers that are
 * available, and a list that showed only the taken ones would answer the less useful half of the question.
 */
public final class ChannelRow {

   public final int channel;

   /** Where the Access Point is, or 0,0 when the channel is free. */
   public final int tileX;

   public final int tileY;

   /** What the player called it, or empty for its default name. */
   public final String customName;

   /** Tiles from the station, so a player can see which silo is which without walking. */
   public final int distance;

   public final BandState state;

   /** A free channel: no device, and nothing to be wrong. */
   public static ChannelRow free(int channel) {
      return new ChannelRow(channel, 0, 0, "", 0, null);
   }

   public ChannelRow(int channel, int tileX, int tileY, String customName, int distance, BandState state) {
      this.channel = channel;
      this.tileX = tileX;
      this.tileY = tileY;
      this.customName = customName == null ? "" : customName;
      this.distance = distance;
      this.state = state;
   }

   public boolean isFree() {
      return this.state == null;
   }

   /** What to call the Access Point on this row: the player's name for it, or its band and channel. */
   public String name(int bandId) {
      if (!this.customName.isEmpty()) {
         return this.customName;
      }

      return Localization.translate("ui", "arcanestorage_band_apname",
            "band", String.valueOf(bandId), "channel", String.valueOf(this.channel));
   }

   public void writePacket(PacketWriter writer) {
      writer.putNextShortUnsigned(this.channel);
      writer.putNextBoolean(this.isFree());
      if (this.isFree()) {
         return;
      }

      writer.putNextInt(this.tileX);
      writer.putNextInt(this.tileY);
      writer.putNextString(this.customName);
      writer.putNextShortUnsigned(Math.max(0, Math.min(65535, this.distance)));
      writer.putNextEnum(this.state);
   }

   public static ChannelRow readPacket(PacketReader reader) {
      int channel = reader.getNextShortUnsigned();
      if (reader.getNextBoolean()) {
         return free(channel);
      }

      int x = reader.getNextInt();
      int y = reader.getNextInt();
      String name = reader.getNextString();
      int distance = reader.getNextShortUnsigned();
      return new ChannelRow(channel, x, y, name, distance, reader.getNextEnum(BandState.class));
   }

   public boolean sameAs(ChannelRow other) {
      return other != null && this.channel == other.channel && this.tileX == other.tileX
            && this.tileY == other.tileY && this.customName.equals(other.customName)
            && this.distance == other.distance && this.state == other.state;
   }
}
