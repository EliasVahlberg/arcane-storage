package arcanestorage.object;

import arcanestorage.ArcaneStorage;
import arcanestorage.upgrade.UnitUpgradeContainer;

import necesse.entity.pickup.ItemPickupEntity;
import necesse.entity.mobs.Attacker;
import arcanestorage.network.NetworkIndexes;
import java.util.ArrayList;
import java.awt.Color;

import arcanestorage.objectentity.StorageUnitObjectEntity;
import necesse.engine.localization.Localization;
import necesse.engine.network.server.ServerClient;
import necesse.entity.mobs.PlayerMob;
import necesse.entity.objectEntity.ObjectEntity;
import necesse.level.gameObject.container.StorageBoxInventoryObject;
import necesse.level.maps.Level;

/**
 * A Storage Unit — capacity for the storage network, and deliberately <b>not</b> a chest.
 *
 * <p>The player can never browse a unit directly; a Storage Terminal is the only way to
 * see or move its contents. This mirrors Magic Storage, where right-clicking a
 * {@code StorageUnit} never opens an inventory and only reports its active state and
 * fill level in chat.
 *
 * <p>It still extends {@link StorageBoxInventoryObject}, so it inherits the inventory,
 * the object entity plumbing, drop-on-break and the 32x32 collision. The two overrides
 * below are what make it not a chest.
 */
import arcanestorage.network.NetworkConductor;

public class StorageUnitObject extends StorageBoxInventoryObject implements NetworkConductor {

   /**
    * Base capacity, kept as a named constant because it is vanilla's container ceiling and the number the
    * whole ladder is measured against, not merely the first entry in it.
    */
   public static final int SLOTS = 40;

   /** Which rung this one is. Held so the object can describe itself without consulting a table. */
   public final UnitTier tier;

   public StorageUnitObject(UnitTier tier) {
      super(tier.storageId(), tier.storageSlots, new Color(96, 74, 140), "objects", "furniture");
      this.tier = tier;
   }

   @Override
   public ObjectEntity getNewObjectEntity(Level level, int x, int y) {
      return new StorageUnitObjectEntity(level, x, y, this.slots);
   }

   /**
    * {@link necesse.level.gameObject.container.InventoryObject} returns
    * {@code controls.opentip} here, which would promise an inventory this object does not
    * open.
    */
   @Override
   public String getInteractTip(Level level, int x, int y, PlayerMob perspective, boolean debug) {
      return Localization.translate("ui", "arcanestorage_unittip");
   }

   /**
    * Reports capacity instead of opening anything.
    *
    * <p>Deliberately does not call {@code super.interact}: {@code InventoryObject.interact}
    * opens {@code ContainerRegistry.OE_INVENTORY_CONTAINER}, which would make the unit an
    * ordinary chest and defeat the point. The only other link in that chain,
    * {@code GameObject.interact}, plays a client-side sound that chests already suppress
    * via {@code interactSoundIsFirstAndLastOnly()}, so nothing audible is lost.
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
      if (entity != null) {
         UnitUpgradeContainer.open(ArcaneStorage.UPGRADE_CONTAINER, client, entity);
      }
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
