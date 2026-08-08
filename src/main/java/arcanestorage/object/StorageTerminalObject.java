package arcanestorage.object;

import java.awt.Color;

import arcanestorage.ArcaneStorage;
import arcanestorage.container.StorageTerminalContainer;
import arcanestorage.objectentity.StorageTerminalObjectEntity;
import necesse.entity.mobs.PlayerMob;
import necesse.entity.objectEntity.ObjectEntity;
import necesse.level.gameObject.container.StorageBoxInventoryObject;
import necesse.level.maps.Level;

/**
 * The placeable Storage Terminal. Extends the chest base class so the sprite handling,
 * open/close sounds, collision, damage and drop-contents-on-break behaviour come for
 * free.
 */
public class StorageTerminalObject extends StorageBoxInventoryObject {

   /** Matches the vanilla storagebox, which is 40. */
   public static final int SLOTS = 40;

   public StorageTerminalObject() {
      super(ArcaneStorage.TERMINAL_STRING_ID, SLOTS, new Color(126, 88, 176), "objects", "furniture");
   }

   @Override
   public ObjectEntity getNewObjectEntity(Level level, int x, int y) {
      return new StorageTerminalObjectEntity(level, x, y, this.slots);
   }

   /**
    * Deliberately does not call {@code super.interact}: {@code InventoryObject.interact}
    * opens the vanilla {@code OE_INVENTORY_CONTAINER}, which would open a second window
    * on top of ours. The only other behaviour in that chain is
    * {@code GameObject.interact}, a client-side open sound that is already suppressed for
    * chests by {@code interactSoundIsFirstAndLastOnly()} — the real sound is driven by
    * the {@code OEUsers} in-use tracking that the container starts and stops.
    */
   @Override
   public void interact(Level level, int x, int y, PlayerMob player) {
      if (level.isServer()) {
         StorageTerminalContainer.openAndSendContainer(ArcaneStorage.TERMINAL_CONTAINER, player.getServerClient(), level, x, y);
      }
   }
}
