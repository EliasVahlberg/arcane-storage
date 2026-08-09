package arcanestorage.object;

import java.awt.Color;
import java.awt.Rectangle;
import java.util.List;

import arcanestorage.ArcaneStorage;
import necesse.engine.localization.Localization;
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
 * <p>Extends {@link FurnitureObject} rather than anything in the container hierarchy: an
 * inventory object would give it a container the player could open, which is exactly what a
 * conduit must not have.
 */
public class StorageConduitObject extends FurnitureObject {

   private final String textureName;

   public GameTexture texture;

   public StorageConduitObject() {
      super(new Rectangle(32, 32));
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
      this.texture
         .initDraw()
         .sprite(rotation % this.frameCount(), 0, 32, this.texture.getHeight())
         .alpha(alpha)
         .draw(drawX, drawY - this.texture.getHeight() + 32);
   }

   /**
    * Frames are laid out horizontally at 32px each, so the texture's width decides how many
    * facings exist. Deriving the count instead of hardcoding it — vanilla furniture assumes
    * {@code rotation % 4} — means a single-frame placeholder works today and adding facings
    * later is purely an art change.
    */
   private int frameCount() {
      return Math.max(1, this.texture.getWidth() / 32);
   }

   private int spriteFrame(Level level, int tileX, int tileY) {
      return level.getObjectRotation(tileX, tileY) % this.frameCount();
   }
}
