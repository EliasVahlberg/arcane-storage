package arcanestorage.object;

import java.awt.Color;

import arcanestorage.ArcaneStorage;
import arcanestorage.band.Band;
import arcanestorage.band.BandIndex;
import arcanestorage.band.BandState;
import arcanestorage.container.BaseStationContainer;
import arcanestorage.objectentity.ArcaneBaseStationObjectEntity;
import necesse.engine.localization.Localization;
import necesse.engine.network.server.ServerClient;
import necesse.entity.mobs.PlayerMob;
import necesse.entity.objectEntity.ObjectEntity;
import necesse.gfx.gameTooltips.ListGameTooltips;
import necesse.inventory.InventoryItem;
import necesse.level.maps.Level;

/**
 * The Arcane Base Station: one per network, and the thing an Access Point tunes to.
 *
 * <p>Three rungs, and what the tier buys is <b>channels</b> -- how many silos the band carries -- rather than
 * distance. A band is meant to span a base from the moment it is built; what grows is how many outbuildings it can
 * take, which is the number that actually limits a player as their base sprawls.
 *
 * <p>Its panel is a view rather than an editor, deliberately. Every choice about a link -- which band, which channel,
 * what to call it -- belongs to the far end, because that is the device standing next to the storage it is speaking
 * for. Configuring silos from the station would put the decisions somewhere the player cannot see what they affect,
 * which is the mistake the buses avoid by being configurable at either end but named at both.
 */
public class ArcaneBaseStationObject extends BandDeviceObject {

   /** Frame count in the shipped art. Every tier's sheet has exactly this many, indices 0..7. */
   private static final int FRAME_COUNT = 8;

   public final UnitTier tier;

   public ArcaneBaseStationObject(UnitTier tier) {
      super(tier.baseStationId(), new Color(126, 88, 176));
      this.tier = tier;
   }

   @Override
   protected int animatedFrameCount() {
      return FRAME_COUNT;
   }

   @Override
   public ObjectEntity getNewObjectEntity(Level level, int x, int y) {
      return new ArcaneBaseStationObjectEntity(level, x, y);
   }

   @Override
   protected String tipKey() {
      return "arcanestorage_basestationtip";
   }

   @Override
   protected BandState stateAt(Level level, int tileX, int tileY) {
      ObjectEntity entity = level.entityManager.getObjectEntity(tileX, tileY);
      return entity instanceof ArcaneBaseStationObjectEntity
         ? ((ArcaneBaseStationObjectEntity)entity).getState()
         : BandState.NO_TRANSCEIVER;
   }

   /** The band and how much of it is in use, so a player can tell a full band from a quiet one without opening it. */
   @Override
   protected String extraTip(Level level, int tileX, int tileY) {
      ObjectEntity entity = level.entityManager.getObjectEntity(tileX, tileY);
      if (!(entity instanceof ArcaneBaseStationObjectEntity)) {
         return null;
      }

      ArcaneBaseStationObjectEntity station = (ArcaneBaseStationObjectEntity)entity;
      if (station.getChannelCount() <= 0) {
         return null;
      }

      return Localization.translate("ui", "arcanestorage_band_channelsused",
            "used", String.valueOf(station.getChannelsUsed()),
            "total", String.valueOf(station.getChannelCount()));
   }

   @Override
   public ListGameTooltips getItemTooltips(InventoryItem item, PlayerMob perspective) {
      ListGameTooltips tooltips = super.getItemTooltips(item, perspective);
      tooltips.add(Localization.translate("ui", "arcanestorage_basestationtip"));
      tooltips.add(Localization.translate("ui", "arcanestorage_band_channels",
            "n", String.valueOf(Band.channelsFor(this.tier))));
      tooltips.add(Localization.translate("ui", "arcanestorage_band_rangetip",
            "range", String.valueOf(ArcaneStorage.SETTINGS.bandRange)));
      return tooltips;
   }

   @Override
   protected void open(Level level, ServerClient client, ObjectEntity entity) {
      if (entity instanceof ArcaneBaseStationObjectEntity) {
         BaseStationContainer.openAndSendContainer(ArcaneStorage.BASE_STATION_CONTAINER, client, level,
               (ArcaneBaseStationObjectEntity)entity);
      }
   }

   /**
    * Takes the band with the station.
    *
    * <p>The Access Points keep their setting and report the band as gone, rather than being silently cleared. A
    * player who breaks a station to upgrade it and puts the new one back on the same tile gets the same band number
    * and can retune; a player who cleared every silo's setting on their behalf would have to walk to all of them.
    */
   @Override
   protected void beforeDestroyed(Level level, int x, int y) {
      if (level.isServer()) {
         BandIndex.of(level).unregister(x, y);
      }
   }
}
