package arcanestorage.objectentity;

import necesse.engine.localization.Localization;
import necesse.gfx.forms.components.FormLabel;
import necesse.gfx.gameFont.FontOptions;
import necesse.engine.network.PacketReader;
import necesse.engine.network.PacketWriter;

/**
 * What the terminal knows about one bus on its network.
 *
 * <p>The terminal's logistics tab lists devices it cannot reach: they are object entities somewhere else on
 * the level, and a client's copy of a distant one holds nothing useful. So a summary of each travels in the
 * terminal's content packet.
 *
 * <p><b>The reason is assembled on the client, not sent as text.</b> Only the parts a message is made of
 * cross the wire -- a state, an item's string ID, a pair of coordinates. Localizing on the server would send
 * the server's language to every client, which on a dedicated server is the wrong one, and it would put a
 * sentence of English in a packet where four numbers do. This is also now the single place a stopped device's
 * reason is worded: {@link BusObjectEntity#stateMessage()} asks this class, so the bus's own panel, its hover
 * tip and the terminal cannot word the same fact differently.
 */
public final class BusSummary {

   public final int tileX;

   public final int tileY;

   /** Which way items move. Decides the name shown, and which side of a conflict this device is on. */
   public final boolean importing;

   public final DeviceState state;

   /** The item a rule conflict is about, or null. A string ID, because those are the locale keys under [item]. */
   public final String conflictItemID;

   public final int conflictX;

   public final int conflictY;

   /** This bus's number among its own kind on the network, or 0 if it has not been given one yet. */
   public final int ordinal;

   /** What the player called it, or empty to use the assigned name. */
   public final String customName;

   /** The same two, for the bus on the other side of a rule conflict, so the reason can name it. */
   public final int conflictOrdinal;

   public final String conflictCustomName;

   public BusSummary(int tileX, int tileY, boolean importing, DeviceState state, String conflictItemID,
         int conflictX, int conflictY, int ordinal, String customName, int conflictOrdinal,
         String conflictCustomName) {
      this.tileX = tileX;
      this.tileY = tileY;
      this.importing = importing;
      this.state = state;
      this.conflictItemID = conflictItemID;
      this.conflictX = conflictX;
      this.conflictY = conflictY;
      this.ordinal = ordinal;
      this.customName = customName == null ? "" : customName;
      this.conflictOrdinal = conflictOrdinal;
      this.conflictCustomName = conflictCustomName == null ? "" : conflictCustomName;
   }

   public void writePacket(PacketWriter writer) {
      writer.putNextInt(this.tileX);
      writer.putNextInt(this.tileY);
      writer.putNextBoolean(this.importing);
      writer.putNextEnum(this.state);
      writer.putNextString(this.conflictItemID == null ? "" : this.conflictItemID);
      writer.putNextInt(this.conflictX);
      writer.putNextInt(this.conflictY);
      writer.putNextInt(this.ordinal);
      writer.putNextString(this.customName);
      writer.putNextInt(this.conflictOrdinal);
      writer.putNextString(this.conflictCustomName);
   }

   public static BusSummary readPacket(PacketReader reader) {
      int x = reader.getNextInt();
      int y = reader.getNextInt();
      boolean importing = reader.getNextBoolean();
      DeviceState state = reader.getNextEnum(DeviceState.class);
      String itemID = reader.getNextString();
      return new BusSummary(x, y, importing, state, itemID.isEmpty() ? null : itemID,
            reader.getNextInt(), reader.getNextInt(), reader.getNextInt(), reader.getNextString(),
            reader.getNextInt(), reader.getNextString());
   }

   /** What to call this device: what the player named it, or the number it was given. */
   public String name() {
      return BusObjectEntity.busName(this.importing, this.ordinal, this.customName);
   }

   /** Where it is, for a player who has to go and look at it. */
   public String where() {
      return this.tileX + "," + this.tileY;
   }

   /**
    * How tall the longest reason this class can produce would be, wrapped to a given width.
    *
    * <p>Here rather than in either panel because this is where a reason is worded, so this is what knows how
    * long one can get. Both surfaces reserve a fixed block for it -- fixed because a label's height is a
    * property of the text it currently holds, and reserving space by measuring an empty label reserves
    * nothing, which is a fault this project has already shipped once.
    *
    * <p>The substitutions are the worst realistic case: the longest item name in the game, a player-chosen
    * device name at its length limit, and five-digit negative coordinates.
    */
   public static int worstCaseReasonHeight(int font, int wrapWidth) {
      String widestName = new String(new char[BusObjectEntity.MAX_NAME_LENGTH]).replace('\0', 'W');
      int worst = 0;
      for (DeviceState state : DeviceState.values()) {
         if (state.isActive()) {
            continue;
         }

         worst = Math.max(worst, new FormLabel(Localization.translate("ui", state.localeKey,
               "item", "Pearlescent Diamond Broadsword", "other", widestName,
               "x", "-12345", "y", "-12345"), new FontOptions(font), -1, 0, 0, wrapWidth).getHeight());
      }

      return worst;
   }

   /** Why this bus has stopped, or empty when it has not. */
   public String message() {
      if (this.state.isActive()) {
         return "";
      }

      if (this.state != DeviceState.RULE_CONFLICT) {
         return Localization.translate("ui", this.state.localeKey);
      }

      // The locale keys under [item] are the registry string IDs, so this needs no registry lookup and works
      // for a modded item as well as a vanilla one.
      return Localization.translate("ui", DeviceState.RULE_CONFLICT.localeKey,
         "item", this.conflictItemID == null ? "?" : Localization.translate("item", this.conflictItemID),
         "other", BusObjectEntity.busName(!this.importing, this.conflictOrdinal, this.conflictCustomName),
         "x", String.valueOf(this.conflictX),
         "y", String.valueOf(this.conflictY));
   }

   /**
    * Value equality, so a mirror can tell a changed list from an identical one.
    *
    * <p>Every field counts, including the conflict partner's name: a conflict whose other side was renamed reads
    * differently to a player, so it is a different summary.
    */
   @Override
   public boolean equals(Object other) {
      if (this == other) {
         return true;
      }

      if (!(other instanceof BusSummary)) {
         return false;
      }

      BusSummary that = (BusSummary)other;
      return this.tileX == that.tileX
         && this.tileY == that.tileY
         && this.importing == that.importing
         && this.state == that.state
         && this.conflictX == that.conflictX
         && this.conflictY == that.conflictY
         && this.ordinal == that.ordinal
         && this.conflictOrdinal == that.conflictOrdinal
         && java.util.Objects.equals(this.conflictItemID, that.conflictItemID)
         && this.customName.equals(that.customName)
         && this.conflictCustomName.equals(that.conflictCustomName);
   }

   @Override
   public int hashCode() {
      return java.util.Objects.hash(this.tileX, this.tileY, this.importing, this.state, this.conflictItemID,
            this.conflictX, this.conflictY, this.ordinal, this.customName, this.conflictOrdinal,
            this.conflictCustomName);
   }
}
