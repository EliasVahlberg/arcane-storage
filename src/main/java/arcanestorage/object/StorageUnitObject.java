package arcanestorage.object;

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
public class StorageUnitObject extends StorageBoxInventoryObject {

   /** Capacity per unit. Tiered capacity is Phase 6. */
   public static final int SLOTS = 40;

   public StorageUnitObject() {
      super("arcanestorageunit", SLOTS, new Color(96, 74, 140), "objects", "furniture");
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
      if (entity instanceof StorageUnitObjectEntity) {
         StorageUnitObjectEntity unit = (StorageUnitObjectEntity)entity;
         client.sendChatMessage(
            Localization.translate(
               "ui",
               "arcanestorage_unitstatus",
               "used",
               String.valueOf(unit.getUsedSlots()),
               "total",
               String.valueOf(unit.slots)
            )
         );
      }
   }
}
