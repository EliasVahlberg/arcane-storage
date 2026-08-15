package arcanestorage.object;

import java.awt.Color;
import java.util.ArrayList;

import arcanestorage.ArcaneStorage;
import arcanestorage.network.NetworkIndexes;
import arcanestorage.network.NetworkNode;
import arcanestorage.objectentity.WirelessTransceiverObjectEntity;
import arcanestorage.upgrade.UnitUpgradeContainer;
import necesse.engine.localization.Localization;
import necesse.engine.network.server.ServerClient;
import necesse.entity.mobs.Attacker;
import necesse.entity.mobs.PlayerMob;
import necesse.entity.objectEntity.ObjectEntity;
import necesse.entity.pickup.ItemPickupEntity;
import necesse.gfx.gameTooltips.ListGameTooltips;
import necesse.level.gameObject.container.StorageBoxInventoryObject;
import necesse.level.maps.Level;

/**
 * The placeable Wireless Transceiver: the tile a wireless terminal is paired to, and the only part of a network
 * that a terminal can reach from outside.
 *
 * <h2>Why pairing moved here from the Storage Terminal</h2>
 *
 * <p>A wireless terminal used to pair to a placed Storage Terminal, which made remote access free with the mod's
 * entry-level object and gave the feature nothing of its own to upgrade. Pairing to a transceiver instead puts the
 * reach on a device the player builds, tiers and positions deliberately -- and it means <b>one network reaches as
 * far as its transceiver</b>, rather than as far as whichever terminal happened to be clicked.
 *
 * <p>Only the paired transceiver counts. A network may hold several without them combining or competing: each is
 * an independent door into the same storage, and the terminal in the player's hand names which one it uses. That
 * is deliberately unlike the unit ladder, where more units mean more capacity -- a second door does not make a
 * building bigger.
 *
 * <h2>Extending the chest base class</h2>
 *
 * <p>Same reason the Storage Terminal does: sprite handling, collision, damage, and dropping itself when broken all
 * come free. Its inventory is empty by construction ({@link WirelessTransceiverObjectEntity#SLOTS}), so the
 * drop-contents behaviour has nothing to drop.
 */
public class WirelessTransceiverObject extends StorageBoxInventoryObject implements NetworkNode {

   public final UnitTier tier;

   public WirelessTransceiverObject(UnitTier tier) {
      super(tier.transceiverId(), WirelessTransceiverObjectEntity.SLOTS,
            new Color(126, 88, 176), "objects", "furniture");
      this.tier = tier;
   }

   @Override
   public ObjectEntity getNewObjectEntity(Level level, int x, int y) {
      return new WirelessTransceiverObjectEntity(level, x, y);
   }

   @Override
   public ListGameTooltips getItemTooltips(necesse.inventory.InventoryItem item, PlayerMob perspective) {
      ListGameTooltips tooltips = super.getItemTooltips(item, perspective);
      tooltips.add(Localization.translate("ui", "arcanestorage_transceivertip"));

      int range = arcanestorage.remote.Reach.sameLevelRange(this.tier);
      if (arcanestorage.remote.Reach.crossesLevels(this.tier)) {
         tooltips.add(Localization.translate("ui", "arcanestorage_transceiver_anylevel"));
      } else if (range < 0) {
         tooltips.add(Localization.translate("ui", "arcanestorage_transceiver_samelevel"));
      } else {
         tooltips.add(Localization.translate("ui", "arcanestorage_transceiver_range",
               "range", String.valueOf(range)));
      }

      return tooltips;
   }

   /**
    * Right-clicking opens the upgrade panel, exactly as a tiered unit does.
    *
    * <p>Deliberately not the chest container the base class would open: a transceiver holds nothing, so that window
    * would be an empty grid. Deliberately not the network either -- a transceiver standing in front of the player is
    * a worse terminal than a terminal, and making it one would blur what the two objects are for.
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

   /** Placing a transceiver may have joined two networks, the same as any other network object. */
   @Override
   public void placeObject(Level level, int layerID, int x, int y, int rotation, boolean byPlayer) {
      super.placeObject(level, layerID, x, y, rotation, byPlayer);
      NetworkIndexes.topologyChanged();
   }

   /**
    * And breaking one may have split a network.
    *
    * <p>Nothing is done here about wireless terminals paired to this tile. They keep the binding and report the
    * transceiver as gone when used, which is the same answer they already gave for a broken terminal, and it is
    * better than clearing bindings the player may want back the moment they rebuild.
    */
   @Override
   public void onDestroyed(Level level, int layerID, int x, int y, Attacker attacker, ServerClient client,
         ArrayList<ItemPickupEntity> itemsDropped) {
      super.onDestroyed(level, layerID, x, y, attacker, client, itemsDropped);
      NetworkIndexes.topologyChanged();
   }
}
