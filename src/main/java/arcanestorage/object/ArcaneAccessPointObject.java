package arcanestorage.object;

import java.awt.Color;

import arcanestorage.ArcaneStorage;
import arcanestorage.band.BandState;
import arcanestorage.container.AccessPointContainer;
import arcanestorage.objectentity.ArcaneAccessPointObjectEntity;
import necesse.engine.localization.Localization;
import necesse.engine.network.server.ServerClient;
import necesse.entity.mobs.PlayerMob;
import necesse.entity.objectEntity.ObjectEntity;
import necesse.gfx.gameTooltips.ListGameTooltips;
import necesse.inventory.InventoryItem;
import necesse.level.maps.Level;

/**
 * The Arcane Access Point: place one beside a cluster of units, tune it to a band, and the cluster is part of that
 * network.
 *
 * <p>One tier, on purpose. It is the cheap part of the pair -- the station is what the player upgrades -- and giving
 * it rungs would mean an upgrade with nothing to buy: it carries no items, has no range of its own, and the channel
 * it holds is the station's to offer.
 */
public class ArcaneAccessPointObject extends BandDeviceObject {

   public static final String STRING_ID = "arcanestorageaccesspoint";

   /** Frame count in the shipped art. The sheet has exactly this many, indices 0..7. */
   private static final int FRAME_COUNT = 8;

   public ArcaneAccessPointObject() {
      super(STRING_ID, new Color(126, 88, 176));
   }

   @Override
   protected int animatedFrameCount() {
      return FRAME_COUNT;
   }

   @Override
   public ObjectEntity getNewObjectEntity(Level level, int x, int y) {
      return new ArcaneAccessPointObjectEntity(level, x, y);
   }

   @Override
   protected String tipKey() {
      return "arcanestorage_accesspointtip";
   }

   @Override
   protected BandState stateAt(Level level, int tileX, int tileY) {
      ObjectEntity entity = level.entityManager.getObjectEntity(tileX, tileY);
      return entity instanceof ArcaneAccessPointObjectEntity
         ? ((ArcaneAccessPointObjectEntity)entity).getState()
         : BandState.NO_BAND;
   }

   /** Its name, which is its band and channel until the player gives it one. */
   @Override
   protected String extraTip(Level level, int tileX, int tileY) {
      ObjectEntity entity = level.entityManager.getObjectEntity(tileX, tileY);
      if (!(entity instanceof ArcaneAccessPointObjectEntity)) {
         return null;
      }

      ArcaneAccessPointObjectEntity point = (ArcaneAccessPointObjectEntity)entity;
      return point.getBandId() <= 0 ? null : point.name();
   }

   @Override
   public ListGameTooltips getItemTooltips(InventoryItem item, PlayerMob perspective) {
      ListGameTooltips tooltips = super.getItemTooltips(item, perspective);
      tooltips.add(Localization.translate("ui", "arcanestorage_accesspointtip"));
      tooltips.add(Localization.translate("ui", "arcanestorage_band_rangetip",
            "range", String.valueOf(ArcaneStorage.SETTINGS.bandRange)));
      return tooltips;
   }

   @Override
   protected void open(Level level, ServerClient client, ObjectEntity entity) {
      if (entity instanceof ArcaneAccessPointObjectEntity) {
         AccessPointContainer.openAndSendContainer(ArcaneStorage.ACCESS_POINT_CONTAINER, client, level,
               (ArcaneAccessPointObjectEntity)entity);
      }
   }

   /** Frees the channel it held, so the number is available again the moment the device is gone. */
   @Override
   protected void beforeDestroyed(Level level, int x, int y) {
      ObjectEntity entity = level.entityManager.getObjectEntity(x, y);
      if (level.isServer() && entity instanceof ArcaneAccessPointObjectEntity) {
         ((ArcaneAccessPointObjectEntity)entity).releaseClaim();
      }
   }
}
