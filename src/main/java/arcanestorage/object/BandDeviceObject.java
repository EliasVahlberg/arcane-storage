package arcanestorage.object;

import java.awt.Color;
import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.List;

import arcanestorage.band.BandState;
import arcanestorage.network.NetworkConductor;
import arcanestorage.network.NetworkIndexes;
import necesse.engine.gameLoop.tickManager.TickManager;
import necesse.engine.localization.Localization;
import necesse.engine.network.server.ServerClient;
import necesse.entity.mobs.Attacker;
import necesse.entity.mobs.PlayerMob;
import necesse.entity.objectEntity.ObjectEntity;
import necesse.entity.pickup.ItemPickupEntity;
import necesse.gfx.GameColor;
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
 * Shared shape of the two band devices: a placed object that is dark until it is doing its job.
 *
 * <p>Everything here is drawing and lifecycle. What a band <i>is</i> lives in {@code arcanestorage.band}, and what
 * each device decides lives on its object entity -- an object in this engine is one shared instance for every tile of
 * its kind, so it can hold no per-tile state at all.
 *
 * <p>A {@link NetworkConductor}, like the buses and for the same reason: a device placed in a run of units must not
 * sever it. It is also what makes the band work at all, since links are only followed at conducting tiles.
 *
 * <p>Extends {@link FurnitureObject} rather than an inventory object so that neither device can be opened as a chest.
 * Neither holds anything: a band carries a network, it does not store one.
 *
 * <p>{@link #hasAnimatedActiveTexture} switches on the cycling status light both devices now have; a device
 * without one -- there is none left, but the hook stays optional rather than mandatory -- would just keep the
 * plain {@link #texture}. Everything else about drawing, including the inactive state, stays here so the two
 * devices cannot drift apart on anything but their frame art and count.</p>
 */
public abstract class BandDeviceObject extends FurnitureObject implements NetworkConductor {

   /** How many ticks each animation frame holds before advancing -- 2, a fast blink rather than a slow pulse. */
   private static final int TICKS_PER_FRAME = 2;

   /** Drawn by hand: {@code FurnitureObject} loads no texture of its own. Same as the buses and the conduit. */
   public GameTexture texture;

   /** The same sprite, dark, for when the device is not working. Shipped art rather than a colour multiplier. */
   public GameTexture inactiveTexture;

   /** The active-state animation, one entry per frame, or {@code null} for a device with no animated art. */
   private GameTexture[] activeFrames;

   private final String textureName;

   protected BandDeviceObject(String stringID, Color mapColor) {
      super(new Rectangle(32, 32));
      this.textureName = stringID;
      this.toolType = ToolType.ALL;
      this.mapColor = mapColor;
      this.objectHealth = 50;
      this.isLightTransparent = true;
      this.setItemCategory("objects", "furniture");
      this.setCraftingCategory("objects", "furniture");
   }

   /** The locale key under {@code ui} describing what this device is for, shown on hover. */
   protected abstract String tipKey();

   /** This device's state at a tile, read from the object entity's synced copy so it works on a client. */
   protected abstract BandState stateAt(Level level, int tileX, int tileY);

   /** What to say beyond the state, or null. The station lists its channel usage here. */
   protected String extraTip(Level level, int tileX, int tileY) {
      return null;
   }

   /**
    * How many status-light frames to load, named {@code <stringID>_anim0} through {@code _anim<n-1>}, or 0 for a
    * device with a single static active sprite. Both current devices override this; the default is 0 so a future
    * one need not opt in until it has the art for it.
    */
   protected int animatedFrameCount() {
      return 0;
   }

   @Override
   public void loadTextures() {
      super.loadTextures();
      this.texture = GameTexture.fromFile("objects/" + this.textureName);
      this.inactiveTexture = GameTexture.fromFile("objects/" + this.textureName + "_inactive");

      int frameCount = this.animatedFrameCount();
      if (frameCount > 0) {
         this.activeFrames = new GameTexture[frameCount];
         for (int i = 0; i < frameCount; i++) {
            this.activeFrames[i] = GameTexture.fromFile("objects/" + this.textureName + "_anim" + i);
         }
      }
   }

   /**
    * The sprite to draw while active, for this tile on this tick.
    *
    * <p>Cycles {@link #activeFrames} when {@link #animatedFrameCount} opted in, matching a real transceiver's
    * TX/RX light rather than a static "on" sprite; falls back to the plain {@link #texture} otherwise.
    *
    * <p>The frame is a pure function of {@code tickManager.getTotalTicks()}, not of anything server-decided, so it
    * needs no sync of its own and two clients looking at the same device see the same frame for free -- the same
    * reason the active/inactive swap already worked this way. There is no meaning attached to any one frame; the
    * art was drawn as an unordered set of small variations on the same idle light, so any starting offset and any
    * playback order looks the same to a player.
    */
   private GameTexture activeTexture(TickManager tickManager) {
      if (this.activeFrames == null) {
         return this.texture;
      }

      int frame = (int)(tickManager.getTotalTicks() / TICKS_PER_FRAME % this.activeFrames.length);
      return this.activeFrames[frame];
   }

   @Override
   public void addDrawables(
      List<LevelSortedDrawable> list, OrderableDrawables tileList, Level level, int tileX, int tileY,
      TickManager tickManager, GameCamera camera, PlayerMob perspective
   ) {
      GameLight light = level.getLightLevel(tileX, tileY);
      int drawX = camera.getTileDrawX(tileX);
      int drawY = camera.getTileDrawY(tileY);
      boolean active = this.stateAt(level, tileX, tileY).isActive();
      GameTexture sprite = !active && this.inactiveTexture != null
         ? this.inactiveTexture
         : active ? this.activeTexture(tickManager) : this.texture;
      final TextureDrawOptions options = sprite
         .initDraw()
         .addObjectDamageOverlay(this, level, tileX, tileY)
         .light(light)
         .pos(drawX, drawY - sprite.getHeight() + 32);

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

   /**
    * What this device is for, and why it is dark when it is.
    *
    * <p>The hover tip is the one surface reached without clicking, and a dark sprite otherwise says only that
    * something is wrong. Both devices can be dark for reasons that are true at the other end of the band, so the
    * message matters more here than it does for a bus.
    */
   @Override
   public String getInteractTip(Level level, int x, int y, PlayerMob perspective, boolean debug) {
      StringBuilder tip = new StringBuilder(Localization.translate("ui", this.tipKey()));

      String extra = this.extraTip(level, x, y);
      if (extra != null) {
         tip.append('\n').append(extra);
      }

      BandState state = this.stateAt(level, x, y);
      if (!state.isActive() && state.localeKey != null) {
         tip.append('\n').append(GameColor.RED.getColorCode()).append(Localization.translate("ui", state.localeKey));
      }

      return tip.toString();
   }

   /** Right-clickable. {@code FurnitureObject} is not by default, so a panel would be unreachable without this. */
   @Override
   public boolean canInteract(Level level, int x, int y, PlayerMob player) {
      return true;
   }

   /** Opens this device's panel. Server-side: everything it shows is server state. */
   @Override
   public void interact(Level level, int x, int y, PlayerMob player) {
      if (!level.isServer()) {
         return;
      }

      ServerClient client = player.getServerClient();
      ObjectEntity entity = level.entityManager.getObjectEntity(x, y);
      if (client != null && entity != null) {
         this.open(level, client, entity);
      }
   }

   protected abstract void open(Level level, ServerClient client, ObjectEntity entity);

   /** Placing either device changes what the network can reach, as any network object does. */
   @Override
   public void placeObject(Level level, int layerID, int x, int y, int rotation, boolean byPlayer) {
      super.placeObject(level, layerID, x, y, rotation, byPlayer);
      NetworkIndexes.topologyChanged();
   }

   @Override
   public void onDestroyed(
      Level level, int layerID, int x, int y, Attacker attacker, ServerClient client,
      ArrayList<ItemPickupEntity> itemsDropped
   ) {
      this.beforeDestroyed(level, x, y);
      super.onDestroyed(level, layerID, x, y, attacker, client, itemsDropped);
      NetworkIndexes.topologyChanged();
   }

   /**
    * A last chance to leave the band index tidy, while the object entity is still there to be asked.
    *
    * <p>Runs before {@code super.onDestroyed}, because that is what removes the entity, and both devices need to read
    * their own state to know what to withdraw from the index.
    */
   protected void beforeDestroyed(Level level, int x, int y) {
   }
}
