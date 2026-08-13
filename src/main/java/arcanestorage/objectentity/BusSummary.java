package arcanestorage.objectentity;

import necesse.engine.localization.Localization;
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

   public BusSummary(int tileX, int tileY, boolean importing, DeviceState state, String conflictItemID,
         int conflictX, int conflictY) {
      this.tileX = tileX;
      this.tileY = tileY;
      this.importing = importing;
      this.state = state;
      this.conflictItemID = conflictItemID;
      this.conflictX = conflictX;
      this.conflictY = conflictY;
   }

   public void writePacket(PacketWriter writer) {
      writer.putNextInt(this.tileX);
      writer.putNextInt(this.tileY);
      writer.putNextBoolean(this.importing);
      writer.putNextEnum(this.state);
      writer.putNextString(this.conflictItemID == null ? "" : this.conflictItemID);
      writer.putNextInt(this.conflictX);
      writer.putNextInt(this.conflictY);
   }

   public static BusSummary readPacket(PacketReader reader) {
      int x = reader.getNextInt();
      int y = reader.getNextInt();
      boolean importing = reader.getNextBoolean();
      DeviceState state = reader.getNextEnum(DeviceState.class);
      String itemID = reader.getNextString();
      return new BusSummary(x, y, importing, state, itemID.isEmpty() ? null : itemID,
            reader.getNextInt(), reader.getNextInt());
   }

   /** This device's name, as the player sees it on the object itself. */
   public String name() {
      return Localization.translate("object",
            this.importing ? "arcanestorageimportbus" : "arcanestorageexportbus");
   }

   /** Where it is, for a player who has to go and look at it. */
   public String where() {
      return this.tileX + "," + this.tileY;
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
         "other", Localization.translate("object",
               this.importing ? "arcanestorageexportbus" : "arcanestorageimportbus"),
         "x", String.valueOf(this.conflictX),
         "y", String.valueOf(this.conflictY));
   }
}
