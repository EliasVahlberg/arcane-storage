package arcanestorage.object;

import necesse.engine.network.server.ServerClient;
import necesse.entity.pickup.ItemPickupEntity;
import necesse.entity.mobs.Attacker;
import arcanestorage.network.NetworkIndexes;
import java.util.ArrayList;
import java.awt.Color;
import java.awt.Rectangle;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import arcanestorage.network.NetworkConductor;
import arcanestorage.network.NetworkNode;
import arcanestorage.ArcaneStorage;
import necesse.engine.localization.Localization;
import necesse.engine.registries.ObjectRegistry;
import necesse.engine.gameLoop.tickManager.TickManager;
import necesse.entity.mobs.PlayerMob;
import necesse.gfx.camera.GameCamera;
import necesse.gfx.drawOptions.texture.TextureDrawOptions;
import necesse.gfx.drawables.LevelSortedDrawable;
import necesse.gfx.drawables.OrderableDrawables;
import necesse.gfx.gameTexture.GameTexture;
import necesse.inventory.item.toolItem.ToolType;
import necesse.level.gameObject.furniture.FurnitureObject;
import necesse.level.maps.Level;
import necesse.level.maps.light.GameLight;

/**
 * A conduit: carries a storage network onward without holding anything itself.
 *
 * <p>Exists so a network is not forced to be one solid block of units. Units conduct, so
 * without this the only way to reach across a base would be to fill the gap with storage the
 * player does not want, which makes layout fight capacity.
 *
 * <p>Deliberately has no object entity and no inventory. It has no state at all, so the
 * traversal recognises it by object ID at a tile rather than by an entity, and there is
 * nothing to persist, break cleanly, or keep in sync.
 *
 * <p>No collision box, matching a torch or a flower patch rather than a chest: a player walks
 * over it. Requested by players laying out a base, since a solid conduit fights the very
 * layout it exists to make possible. Collision lives on the registered object instance, not in
 * a save file, so this took effect for every conduit already placed by an earlier version the
 * moment the updated mod loaded -- nothing needed migrating.
 *
 * <p>Extends {@link FurnitureObject} rather than anything in the container hierarchy: an
 * inventory object would give it a container the player could open, which is exactly what a
 * conduit must not have.
 */
public class StorageConduitObject extends FurnitureObject implements NetworkConductor {

   /**
    * Which neighbours a conduit is joined to, as a 4-bit mask.
    *
    * <p>Bit order is north, east, south, west — the same order as
    * {@link arcanestorage.network.UnitNetwork#NEIGHBOURS}, deliberately, so the picture a player
    * sees and the walk the network performs cannot disagree about what "adjacent" means.
    *
    * <p>Used directly as a frame index, which is why the sheet needs 16 frames: every
    * combination is a distinct shape — stubs, straights, four elbows, four tees and a cross.
    */
   public static int connectionMask(Level level, int tileX, int tileY) {
      int mask = 0;

      for (int i = 0; i < NEIGHBOUR_ORDER.length; i++) {
         int[] offset = NEIGHBOUR_ORDER[i];
         if (carriesNetwork(level, tileX + offset[0], tileY + offset[1])) {
            mask |= 1 << i;
         }
      }

      return mask;
   }

   /**
    * Whether a tile is something the network flows through: a conduit, a unit, or a terminal.
    *
    * <p>Asked as a role rather than by object ID, so a conduit joins visibly to another mod's silo or
    * pipe for the same reason it joins to ours — and the drawn shape cannot disagree with the walk,
    * which reads the same interfaces.
    *
    * <p>Joining to units and terminals as well as to other conduits means the drawn shape
    * reports actual connectivity rather than merely "another pipe is next to me". A pipe that
    * visibly fails to meet a unit is then a real signal that the unit is not on the network,
    * which is the sort of mistake that is otherwise invisible until the terminal comes up short.
    */
   private static boolean carriesNetwork(Level level, int tileX, int tileY) {
      return level.getObject(tileX, tileY) instanceof NetworkNode;
   }

   /**
    * Object IDs are assigned at registration, so they cannot be constants — but they never
    * change afterwards, and this runs for four neighbours of every visible conduit every frame,
    * so it is worth not going through the registry each time.
    */
   private static int objectID(String stringID) {
      return OBJECT_IDS.computeIfAbsent(stringID, ObjectRegistry::getObjectID);
   }

   private static final Map<String, Integer> OBJECT_IDS = new HashMap<>();

   /** North, east, south, west. Matches the network walk's order, and the frame numbering. */
   private static final int[][] NEIGHBOUR_ORDER = {{0, -1}, {1, 0}, {0, 1}, {-1, 0}};

   /** Frames needed before auto-connecting shapes can be drawn: one per neighbour combination. */
   private static final int AUTO_CONNECT_FRAMES = 16;

   private final String textureName;

   public GameTexture texture;

   public StorageConduitObject() {
      super(new Rectangle());
      this.textureName = ArcaneStorage.CONDUIT_STRING_ID;
      this.toolType = ToolType.ALL;
      this.mapColor = new Color(96, 74, 140);
      this.objectHealth = 50;
      this.isLightTransparent = true;
      this.setItemCategory("objects", "furniture");
      this.setCraftingCategory("objects", "furniture");
   }

   @Override
   public void loadTextures() {
      super.loadTextures();
      this.texture = GameTexture.fromFile("objects/" + this.textureName);
   }

   /** Says what it is for, since a conduit does nothing when interacted with. */
   @Override
   public String getInteractTip(Level level, int x, int y, PlayerMob perspective, boolean debug) {
      return Localization.translate("ui", "arcanestorage_conduittip");
   }

   @Override
   public void addDrawables(
      List<LevelSortedDrawable> list,
      OrderableDrawables tileList,
      Level level,
      int tileX,
      int tileY,
      TickManager tickManager,
      GameCamera camera,
      PlayerMob perspective
   ) {
      GameLight light = level.getLightLevel(tileX, tileY);
      int drawX = camera.getTileDrawX(tileX);
      int drawY = camera.getTileDrawY(tileY);
      final TextureDrawOptions options = this.texture
         .initDraw()
         .sprite(this.spriteFrame(level, tileX, tileY), 0, 32, this.texture.getHeight())
         .addObjectDamageOverlay(this, level, tileX, tileY)
         .light(light)
         .pos(drawX, drawY - this.texture.getHeight() + 32);

      list.add(new LevelSortedDrawable(this, tileX, tileY) {
         @Override
         public int getSortY() {
            return 16;
         }

         @Override
         public void draw(TickManager tickManager) {
            options.draw();
         }
      });
   }

   @Override
   public void drawPreview(Level level, int tileX, int tileY, int rotation, float alpha, PlayerMob player, GameCamera camera) {
      int drawX = camera.getTileDrawX(tileX);
      int drawY = camera.getTileDrawY(tileY);
      // Preview the shape it will actually take once placed, rather than a fixed frame, so a
      // player laying a run can see it turn a corner before committing to the tile.
      int frames = this.frameCount();
      int frame = frames >= AUTO_CONNECT_FRAMES ? connectionMask(level, tileX, tileY) : rotation % frames;
      this.texture
         .initDraw()
         .sprite(frame, 0, 32, this.texture.getHeight())
         .alpha(alpha)
         .draw(drawX, drawY - this.texture.getHeight() + 32);
   }

   /**
    * Frames are laid out horizontally at 32px each, so the texture's width decides how many
    * there are. Deriving the count rather than hardcoding it — vanilla furniture assumes
    * {@code rotation % 4}, and {@code InventoryObject} derives it exactly this way — means the
    * art decides the behaviour with no code change.
    */
   private int frameCount() {
      return Math.max(1, this.texture.getWidth() / 32);
   }

   /**
    * Picks the frame for a placed conduit.
    *
    * <p>With a full 16-frame sheet the frame is the neighbour mask, so a conduit draws the shape
    * its surroundings call for: elbows where a run turns, tees where one branches, a cross where
    * two runs meet. Placement rotation is then irrelevant, which is the right outcome — a player
    * laying pipe should not have to face the correct way, and a run should not break when
    * something is added beside it later.
    *
    * <p>With fewer frames it falls back to rotation, so the earlier 4-frame vertical/horizontal
    * sheet still renders correctly rather than showing frame 0 everywhere.
    *
    * <p>Purely cosmetic either way: the network walk is unchanged and does not consult this.
    */
   private int spriteFrame(Level level, int tileX, int tileY) {
      int frames = this.frameCount();
      if (frames >= AUTO_CONNECT_FRAMES) {
         return connectionMask(level, tileX, tileY);
      }

      return level.getObjectRotation(tileX, tileY) % frames;
   }

   /**
    * Any placement of a network object may have joined two networks or extended one.
    *
    * <p>The shared index is refused rather than repaired: it is cheaper to rebuild a network's counts than to
    * work out what a new tile did to them, and rebuilding is the operation that is already known to be correct.
    * Placing something is rare, so a coarse invalidation costs nothing measurable.
    */
   @Override
   public void placeObject(Level level, int layerID, int x, int y, int rotation, boolean byPlayer) {
      super.placeObject(level, layerID, x, y, rotation, byPlayer);
      NetworkIndexes.topologyChanged();
   }

   /** And any break may have split one, or removed a member. */
   @Override
   public void onDestroyed(
      Level level, int layerID, int x, int y, Attacker attacker, ServerClient client,
      ArrayList<ItemPickupEntity> itemsDropped
   ) {
      super.onDestroyed(level, layerID, x, y, attacker, client, itemsDropped);
      NetworkIndexes.topologyChanged();
   }
}
