package arcanestorage.object;

import necesse.entity.pickup.ItemPickupEntity;
import necesse.entity.mobs.Attacker;
import arcanestorage.network.NetworkIndexes;
import java.util.ArrayList;
import java.awt.Color;
import java.awt.Rectangle;

import arcanestorage.network.NetworkConductor;
import arcanestorage.ArcaneStorage;
import arcanestorage.container.BusContainer;
import arcanestorage.objectentity.BusObjectEntity;
import necesse.engine.localization.Localization;
import necesse.gfx.GameColor;
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
import arcanestorage.network.UnitNetwork;
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

   /**
    * The same sprite, desaturated and dimmed, drawn when the bus is not working.
    *
    * <p>A second texture rather than a colour multiplier: {@code color(float)} scales the channels, so a
    * coloured sprite comes out as a darker version of its own hue rather than gray, and "dim" reads as
    * shadow. Generated from the sprite it shadows, so it cannot drift out of step.
    */
   public GameTexture inactiveTexture;

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

   /**
    * The five sprites for each state, indexed by {@link UnitNetwork#NEIGHBOURS} with the unattached one last.
    *
    * <p>One texture per state rather than one sheet with offsets, because the game loads
    * {@code objects/<stringID>} for us and any extra file costs a single {@code fromFile} at load. A sheet would
    * buy nothing here and would put the sprite's layout in two places.
    */
   private GameTexture[] directed;

   private GameTexture[] directedInactive;

   /** Where the unattached sprite sits in the two arrays above. */
   private static final int UNATTACHED = UnitNetwork.NEIGHBOURS.length;

   private static final String[] SUFFIXES = {"_n", "_e", "_s", "_w", ""};

   @Override
   public void loadTextures() {
      super.loadTextures();
      this.texture = GameTexture.fromFile("objects/" + this.textureName);
      this.inactiveTexture = GameTexture.fromFile("objects/" + this.textureName + "_inactive");

      this.directed = new GameTexture[SUFFIXES.length];
      this.directedInactive = new GameTexture[SUFFIXES.length];
      for (int i = 0; i < SUFFIXES.length; i++) {
         // Null on a missing file rather than the pink error sprite, which is what the no-argument fromFile
         // substitutes. The distinction is deliberate: the base texture above is required, so its absence should be
         // loudly visible, while a directional variant is an improvement on a sprite that already worked -- losing
         // one should quietly fall back to that sprite, not paint [ER] on somebody's base.
         this.directed[i] = GameTexture.fromFile("objects/" + this.textureName + SUFFIXES[i], null);
         this.directedInactive[i] =
            GameTexture.fromFile("objects/" + this.textureName + SUFFIXES[i] + "_inactive", null);
      }
   }

   /** Whether the bus standing here has stopped, read from the object entity's synced state. */
   private static boolean isInactive(Level level, int tileX, int tileY) {
      ObjectEntity entity = level.entityManager.getObjectEntity(tileX, tileY);
      return entity instanceof BusObjectEntity && ((BusObjectEntity)entity).isInactive();
   }

   /**
    * Which of the five sprites this tile should draw.
    *
    * <p>Falls back to the unattached sprite whenever the entity is missing, which is what a client sees for a tile
    * whose region it has but whose entity has not arrived. Drawing the plain sprite is right for that moment: it says
    * "no container", the state the bus is in as far as this client can tell.
    */
   private int spriteIndex(Level level, int tileX, int tileY) {
      ObjectEntity entity = level.entityManager.getObjectEntity(tileX, tileY);
      if (!(entity instanceof BusObjectEntity)) {
         return UNATTACHED;
      }

      int direction = ((BusObjectEntity)entity).attachedDirection();
      return direction < 0 || direction >= UNATTACHED ? UNATTACHED : direction;
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
      GameTexture sprite = this.spriteFor(level, tileX, tileY);
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

   /**
    * The sprite for a tile: pointing at what it serves, and gray when it has stopped.
    *
    * <p>Every lookup falls back rather than failing, because this runs per visible bus per frame: a variant that did
    * not load leaves a null in the array, and the bus then draws the plain sprite it always drew.
    */
   private GameTexture spriteFor(Level level, int tileX, int tileY) {
      int index = this.spriteIndex(level, tileX, tileY);
      boolean inactive = isInactive(level, tileX, tileY);
      GameTexture[] set = inactive ? this.directedInactive : this.directed;
      if (set != null && index < set.length && set[index] != null) {
         return set[index];
      }

      return inactive && this.inactiveTexture != null ? this.inactiveTexture : this.texture;
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
    * What this bus is for, and why it has stopped when it has.
    *
    * <p>The reason is on the hover tip because that is the one surface a player reaches without clicking:
    * a gray sprite says something is wrong, and this says what. Read from the object entity's synced state,
    * so it works on a client.
    */
   @Override
   public String getInteractTip(Level level, int x, int y, PlayerMob perspective, boolean debug) {
      String tip = Localization.translate("ui", this.tipKey());
      ObjectEntity entity = level.entityManager.getObjectEntity(x, y);
      if (entity instanceof BusObjectEntity && ((BusObjectEntity)entity).isInactive()) {
         return tip + "\n" + GameColor.RED.getColorCode() + ((BusObjectEntity)entity).stateMessage();
      }

      return tip;
   }

   /**
    * Reports what this bus can currently see, rather than opening anything.
    *
    * <p>Server-side only: it reads the network and the neighbouring container, and neither is authoritative
    * on a client.
    */
   /**
    * Right-clickable.
    *
    * <p>{@code GameObject.canInteract} is false by default and only {@code InventoryObject} overrides it,
    * so a {@code FurnitureObject} that means to be opened must say so — without this, the panel below can
    * never be reached and {@link #getInteractTip} never appears. A headless test found this, not a play
    * session.
    */
   @Override
   public boolean canInteract(Level level, int x, int y, PlayerMob player) {
      return true;
   }

   /**
    * Opens the rule panel, and says why nothing is happening when that is the case.
    *
    * <p>A missing container is the likeliest mistake when placing a bus and is invisible otherwise, so it
    * is worth a line of chat even though the panel still opens: a player who mis-set a rule wants the
    * panel, and a player who forgot the chest wants to be told.
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
      if (bus.attachedContainer() == null) {
         client.sendChatMessage(Localization.translate("ui", "arcanestorage_bus_nocontainer"));
      } else if (bus.network().isEmpty()) {
         client.sendChatMessage(Localization.translate("ui", "arcanestorage_bus_nonetwork"));
      }

      BusContainer.openAndSendContainer(ArcaneStorage.BUS_CONTAINER, client, level, bus);
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
