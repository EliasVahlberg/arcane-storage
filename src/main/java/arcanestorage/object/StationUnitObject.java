package arcanestorage.object;

import java.awt.Color;
import java.util.ArrayList;

import arcanestorage.network.NetworkConductor;
import arcanestorage.network.NetworkIndexes;
import arcanestorage.objectentity.StationUnitObjectEntity;
import necesse.engine.localization.Localization;
import necesse.engine.network.server.ServerClient;
import necesse.entity.mobs.Attacker;
import necesse.entity.mobs.PlayerMob;
import necesse.entity.objectEntity.ObjectEntity;
import necesse.entity.pickup.ItemPickupEntity;
import necesse.level.gameObject.container.StorageBoxInventoryObject;
import necesse.level.maps.Level;

/**
 * A Station Unit — sockets for crafting benches, and the network's station capacity.
 *
 * <p>Built on the same base as a Storage Unit and conducting the same way, so it joins a network by
 * touching one orthogonally or by being reached through a conduit. There is nothing new to learn: if a
 * player understands where a Storage Unit has to go, they already understand where this goes.
 *
 * <p>Like a Storage Unit it refuses to open. Benches are installed from the terminal's Stations tab,
 * which keeps one place to look at a network rather than making the player walk to a block to change
 * what they can craft. Interacting reports how many sockets are filled, which is the question worth
 * answering while standing in front of one.
 *
 * <p><b>Why this exists rather than the terminal simply having slots.</b> The mod already says capacity
 * comes from units and reach comes from conduits; station access was free, which broke that grammar and
 * fixed the number at ten -- defensible as "eight station families plus headroom", honestly just what
 * fitted in a row. It also composes with tiering at no cost, because an upgraded bench reports the
 * lower techs too, so upgrading a bench frees a socket: progression that makes an existing setup
 * neater rather than merely bigger.
 */
public class StationUnitObject extends StorageBoxInventoryObject implements NetworkConductor {

   /**
    * Sockets per unit at the base tier.
    *
    * <p>One, and the whole ladder is 1 → 2 → 4 → 8 across vanilla's four station tiers (base → Demonic
    * → Tungsten → Fallen). Starting at one is the point rather than a placeholder: it makes the first
    * bench a decision, and doubling reads as a real upgrade. All four rungs are registered; the numbers
    * live in {@link UnitTier}.
    */
   public static final int SLOTS = 1;

   /** Which rung this one is. */
   public final UnitTier tier;

   public StationUnitObject(UnitTier tier) {
      super(tier.stationId(), tier.stationSockets, new Color(96, 74, 140), "objects", "furniture");
      this.tier = tier;
   }

   @Override
   public ObjectEntity getNewObjectEntity(Level level, int x, int y) {
      return new StationUnitObjectEntity(level, x, y, this.slots);
   }

   /**
    * {@link necesse.level.gameObject.container.InventoryObject} would promise {@code controls.opentip}
    * here, and this object opens nothing.
    */
   @Override
   public String getInteractTip(Level level, int x, int y, PlayerMob perspective, boolean debug) {
      return Localization.translate("ui", "arcanestorage_stationunittip");
   }

   /**
    * Reports its sockets instead of opening anything.
    *
    * <p>Deliberately does not call {@code super.interact}, which would open the generic inventory
    * container and make this a chest that happens to be fussy about its contents.
    */
   @Override
   public void interact(Level level, int x, int y, PlayerMob player) {
      if (!level.isServer()) {
         return;
      }

      ServerClient client = player.getServerClient();
      if (client == null) {
         return;
      }

      ObjectEntity entity = level.entityManager.getObjectEntity(x, y);
      if (entity instanceof StationUnitObjectEntity) {
         StationUnitObjectEntity unit = (StationUnitObjectEntity)entity;
         client.sendChatMessage(
               Localization.translate("ui", "arcanestorage_stationunitstatus",
                     "used", String.valueOf(unit.getUsedSlots()),
                     "total", String.valueOf(unit.slots)));
      }
   }

   /** Placing any network object may have joined two networks or extended one. */
   @Override
   public void placeObject(Level level, int layerID, int x, int y, int rotation, boolean byPlayer) {
      super.placeObject(level, layerID, x, y, rotation, byPlayer);
      NetworkIndexes.topologyChanged();
   }

   /**
    * And any break may have split one, or removed a member.
    *
    * <p>The installed bench drops with it, because {@code StorageBoxInventoryObject} already drops an
    * inventory's contents on break. That is the behaviour to want: a socket's bench is the player's
    * property and vanishing it would be worse than making them pick it up.
    */
   @Override
   public void onDestroyed(
         Level level, int layerID, int x, int y, Attacker attacker, ServerClient client,
         ArrayList<ItemPickupEntity> itemsDropped) {
      super.onDestroyed(level, layerID, x, y, attacker, client, itemsDropped);
      NetworkIndexes.topologyChanged();
   }
}
