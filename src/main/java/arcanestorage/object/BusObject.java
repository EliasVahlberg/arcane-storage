package arcanestorage.object;

import java.awt.Color;
import java.awt.Rectangle;

import arcanestorage.network.NetworkConductor;
import arcanestorage.network.TransferRule;
import arcanestorage.objectentity.BusObjectEntity;
import necesse.engine.localization.Localization;
import necesse.engine.network.server.ServerClient;
import necesse.entity.mobs.PlayerMob;
import java.util.List;

import necesse.engine.gameLoop.tickManager.TickManager;
import necesse.entity.objectEntity.ObjectEntity;
import necesse.gfx.camera.GameCamera;
import necesse.gfx.drawOptions.texture.TextureDrawOptions;
import necesse.gfx.drawables.LevelSortedDrawable;
import necesse.gfx.drawables.OrderableDrawables;
import necesse.gfx.gameTexture.GameTexture;
import necesse.level.maps.light.GameLight;
import necesse.inventory.item.toolItem.ToolType;
import necesse.level.gameObject.furniture.FurnitureObject;
import necesse.level.maps.Level;

/**
 * Shared shape of the two buses: a small placed object between a container and the network.
 *
 * <p><b>A {@link NetworkConductor}, so the network passes through it.</b> The first version made a bus a
 * plain node like the terminal, reasoning that it should not let a chest bridge two networks — which was
 * empty reasoning, since a chest is not a conductor either way and never could bridge anything. What it
 * actually did was sever a run: a bus placed between two units silently split the network, and the units
 * beyond it vanished from the terminal. A test caught it.
 *
 * <p>So everything this mod places conducts except the terminal, which is a window rather than a piece of
 * infrastructure. A bus is infrastructure.
 *
 * <p>Extends {@link FurnitureObject} rather than anything in the container hierarchy, because an
 * inventory object would give the bus a container of its own that the player could open and store things
 * in — which would make it a chest that also moves items, and put items somewhere a network cannot see.
 *
 * <p>Interacting reports what the bus can see: its network, its container, and its rules. Until the rule
 * interface exists that message is the only window into a bus, so it is written to be diagnostic — "no
 * container attached" is the single most likely mistake when placing one.
 */
public abstract class BusObject extends FurnitureObject implements NetworkConductor {

   /**
    * Drawn by hand, because {@code FurnitureObject} draws nothing on its own: {@code GameObject.loadTextures}
    * is empty and only the container-backed objects in the game load a texture for you. The conduit already
    * had to do this, and this is the same shape minus the connection frames -- a bus is one sprite.
    */
   public GameTexture texture;

   private final String textureName;

   protected BusObject(String stringID, Color mapColor) {
      super(new Rectangle(32, 32));
      this.textureName = stringID;
      this.toolType = ToolType.ALL;
      this.mapColor = mapColor;
      this.objectHealth = 50;
      this.isLightTransparent = true;
      this.setItemCategory("objects", "furniture");
      this.setCraftingCategory("objects", "furniture");
   }

   /** The locale key under {@code ui} describing this bus's purpose, shown on hover. */
   protected abstract String tipKey();

   @Override
   public void loadTextures() {
      super.loadTextures();
      this.texture = GameTexture.fromFile("objects/" + this.textureName);
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
   public void drawPreview(
      Level level, int tileX, int tileY, int rotation, float alpha, PlayerMob player, GameCamera camera
   ) {
      this.texture
         .initDraw()
         .alpha(alpha)
         .draw(camera.getTileDrawX(tileX), camera.getTileDrawY(tileY) - this.texture.getHeight() + 32);
   }

   @Override
   public String getInteractTip(Level level, int x, int y, PlayerMob perspective, boolean debug) {
      return Localization.translate("ui", this.tipKey());
   }

   /**
    * Reports what this bus can currently see, rather than opening anything.
    *
    * <p>Server-side only: it reads the network and the neighbouring container, and neither is authoritative
    * on a client.
    */
   @Override
   public void interact(Level level, int x, int y, PlayerMob player) {
      if (!level.isServer()) {
         return;
      }

      ServerClient client = player.getServerClient();
      ObjectEntity entity = level.entityManager.getObjectEntity(x, y);
      if (client == null || !(entity instanceof BusObjectEntity)) {
         return;
      }

      BusObjectEntity bus = (BusObjectEntity)entity;
      client.sendChatMessage(Localization.translate("ui", "arcanestorage_busstatus",
         "units", String.valueOf(bus.network().size()),
         "container", bus.attachedContainer() == null
            ? Localization.translate("ui", "arcanestorage_bus_nocontainer")
            : Localization.translate("ui", "arcanestorage_bus_container"),
         "rules", bus.rules.isEmpty()
            ? Localization.translate("ui", "arcanestorage_bus_norules")
            : describe(bus)));
   }

   /** The rules as one line, since there is no interface to show them properly yet. */
   private static String describe(BusObjectEntity bus) {
      StringBuilder out = new StringBuilder();
      for (TransferRule rule : bus.rules.all()) {
         if (out.length() > 0) {
            out.append(", ");
         }

         out.append(rule);
      }

      return out.toString();
   }
}
