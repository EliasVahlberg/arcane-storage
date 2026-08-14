package arcanestorage.remote;

import necesse.engine.network.Packet;
import necesse.engine.network.PacketReader;
import necesse.engine.network.PacketWriter;
import necesse.engine.util.LevelIdentifier;
import necesse.engine.network.gameNetworkData.GNDItemMap;
import necesse.inventory.InventoryItem;
import necesse.level.maps.Level;

/**
 * Which Storage Terminal a wireless terminal is paired to: a level and a tile on it.
 *
 * <p><b>A level identifier, not a Level.</b> The paired network's level is very often not loaded --
 * that is the whole point of the item -- and a {@link Level} reference to an unloaded level is worse
 * than no reference at all, because the engine unloads a level by dropping it from
 * {@code LevelManager.loadedLevels} and saving it. Anything still holding the object would go on
 * writing into a level that is never saved again, and those writes would be a player's items. So the
 * binding stores only what survives: {@link LevelIdentifier#stringID}, which is a validated string,
 * plus the tile.
 *
 * <p>Resolution is deliberately not done here; see {@code RemoteTerminal}. This class is the value,
 * knows how to persist itself on an item and how to cross the wire, and nothing else.
 *
 * <p>Tiles are stored rather than an object-entity reference for the same reason. Object entities are
 * recreated when a level loads from disk, so an identity captured before an unload means nothing
 * after one. A tile is stable for as long as the terminal stands there, and if it stops being a
 * terminal, that is exactly the case the open path has to detect anyway.
 */
public final class RemoteBinding {

   /** GND keys. Short because they are written into every wireless terminal in the world. */
   private static final String LEVEL_KEY = "asrlevel";

   private static final String X_KEY = "asrx";

   private static final String Y_KEY = "asry";

   public final String levelID;

   public final int tileX;

   public final int tileY;

   public RemoteBinding(String levelID, int tileX, int tileY) {
      this.levelID = levelID;
      this.tileX = tileX;
      this.tileY = tileY;
   }

   public RemoteBinding(Level level, int tileX, int tileY) {
      this(level.getIdentifier().stringID, tileX, tileY);
   }

   /**
    * The paired level, or null when the string on the item is not one the engine will accept.
    *
    * <p>{@link LevelIdentifier} validates in its constructor and throws
    * {@code InvalidLevelIdentifierException} on anything outside {@code [a-z0-9-+]{1,50}}, so a
    * hand-edited save or a binding written by an older version cannot be allowed to propagate an
    * exception out of an item's right-click. An unreadable binding reads as an unpaired item.
    */
   public LevelIdentifier identifier() {
      try {
         return new LevelIdentifier(this.levelID);
      } catch (RuntimeException invalid) {
         return null;
      }
   }

   /** Reads the binding off an item, or null when the item has never been paired. */
   public static RemoteBinding read(InventoryItem item) {
      if (item == null) {
         return null;
      }

      GNDItemMap data = item.getGndData();
      if (!data.hasKey(LEVEL_KEY)) {
         return null;
      }

      // getString(key, default) rather than getString(key): a map holding the key with the wrong type would
      // otherwise throw out of a right-click, and an item whose binding cannot be read is an unpaired item.
      String levelID = data.getString(LEVEL_KEY, null);
      if (levelID == null || levelID.isEmpty()) {
         return null;
      }

      return new RemoteBinding(levelID, data.getInt(X_KEY, 0), data.getInt(Y_KEY, 0));
   }

   /**
    * Writes the binding onto an item, replacing any previous pairing.
    *
    * <p>Mutates rather than returning a copy because the engine's interact hooks hand back the item
    * that continues to exist in the slot, and an item's GND map is already its mutable state.
    */
   public void write(InventoryItem item) {
      GNDItemMap data = item.getGndData();
      data.setString(LEVEL_KEY, this.levelID);
      data.setInt(X_KEY, this.tileX);
      data.setInt(Y_KEY, this.tileY);
   }

   public static boolean isPaired(InventoryItem item) {
      return read(item) != null;
   }

   public void writePacket(PacketWriter writer) {
      writer.putNextString(this.levelID);
      writer.putNextInt(this.tileX);
      writer.putNextInt(this.tileY);
   }

   public static RemoteBinding fromPacket(PacketReader reader) {
      return new RemoteBinding(reader.getNextString(), reader.getNextInt(), reader.getNextInt());
   }

   public Packet toPacket() {
      Packet packet = new Packet();
      this.writePacket(new PacketWriter(packet));
      return packet;
   }

   public boolean sameTile(Level level, int x, int y) {
      return this.tileX == x && this.tileY == y && level.getIdentifier().stringID.equals(this.levelID);
   }

   @Override
   public boolean equals(Object other) {
      if (!(other instanceof RemoteBinding)) {
         return false;
      }

      RemoteBinding o = (RemoteBinding)other;
      return this.tileX == o.tileX && this.tileY == o.tileY && this.levelID.equals(o.levelID);
   }

   @Override
   public int hashCode() {
      return this.levelID.hashCode() * 31 + this.tileX * 17 + this.tileY;
   }

   @Override
   public String toString() {
      return this.levelID + "@" + this.tileX + "," + this.tileY;
   }
}
