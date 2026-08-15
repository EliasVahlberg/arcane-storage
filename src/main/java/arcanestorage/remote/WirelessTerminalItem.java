package arcanestorage.remote;

import arcanestorage.object.StorageTerminalObject;
import arcanestorage.object.UnitTier;
import arcanestorage.object.WirelessTransceiverObject;
import necesse.engine.localization.Localization;
import necesse.engine.network.server.ServerClient;
import necesse.engine.util.GameMath;
import necesse.entity.mobs.PlayerMob;
import necesse.entity.mobs.itemAttacker.ItemAttackSlot;
import necesse.entity.mobs.itemAttacker.ItemAttackerMob;
import necesse.gfx.gameTooltips.ListGameTooltips;
import necesse.inventory.InventoryItem;
import necesse.inventory.item.Item;
import necesse.inventory.item.ItemInteractAction;
import necesse.engine.registries.ItemRegistry;
import necesse.engine.network.gameNetworkData.GNDItemMap;
import necesse.level.maps.Level;

/**
 * The Wireless Terminal, at one rung of its ladder: a carried item paired to one Wireless Transceiver.
 *
 * <h2>What the tier buys</h2>
 *
 * <p>Nothing about the window. Every tier opens the same network with the same slots, because a storage terminal
 * that showed less at a lower tier would be a worse interface rather than a smaller reach. What the ladder gates is
 * <b>where the player may be standing</b>, and that rule lives in {@link Reach} because the container has to apply
 * it every tick as well.
 *
 * <p>Both ends carry a tier and the lower one governs, so a player who upgrades one end and not the other gets told
 * which. See {@link Reach#effective}.
 */
public class WirelessTerminalItem extends Item implements ItemInteractAction {

   public final UnitTier tier;

   public WirelessTerminalItem(UnitTier tier) {
      super(1);
      this.tier = tier;
      this.rarity = tier == UnitTier.FALLEN ? Item.Rarity.EPIC : Item.Rarity.UNCOMMON;
   }

   @Override
   public ListGameTooltips getTooltips(InventoryItem item, PlayerMob perspective, necesse.engine.util.GameBlackboard blackboard) {
      ListGameTooltips tooltips = super.getTooltips(item, perspective, blackboard);
      RemoteBinding binding = RemoteBinding.read(item);
      if (binding == null) {
         tooltips.add(Localization.translate("ui", "arcanestorage_wireless_unpaired"));
      } else {
         tooltips.add(Localization.translate("ui", "arcanestorage_wireless_paired",
               "x", String.valueOf(binding.tileX), "y", String.valueOf(binding.tileY)));
      }

      // The reach this end allows. Stated on the item as well as on the transceiver because the lower of the two
      // decides, so a player comparing them needs both numbers visible.
      int range = Reach.sameLevelRange(this.tier);
      if (Reach.crossesLevels(this.tier)) {
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
    * True for any tile, so a click always uses the item rather than swinging it.
    *
    * <p>Deliberately not restricted to transceivers. Restricting it would make the item inert everywhere except in
    * front of the thing it is meant to replace visiting.
    */
   @Override
   public boolean canLevelInteract(Level level, int x, int y, ItemAttackerMob attackerMob, InventoryItem item) {
      return attackerMob != null && attackerMob.isPlayer;
   }

   @Override
   public boolean overridesObjectInteract(Level level, PlayerMob player, InventoryItem item) {
      return true;
   }

   /**
    * One click does both jobs: on a transceiver it pairs, anywhere else it opens what it is paired to.
    *
    * <p><b>The engine runs this on both sides</b> -- {@code PlayerMob.runClientItemLevelInteract} calls it on the
    * clicking client so the swing and any change to the item are immediate, and the server then runs it for real.
    * The side is therefore decided once, here, and the client returns before any server-only API exists to be
    * misused. An earlier version instead carried a possibly-null {@code ServerClient} into its helpers, which
    * null-checked it in two places and dereferenced it in a third, and threw on the first right-click in game.
    * Deciding once is worth more than checking three times.
    */
   @Override
   public InventoryItem onLevelInteract(Level level, int x, int y, ItemAttackerMob attackerMob, int attackHeight,
         InventoryItem item, ItemAttackSlot slot, int seed, GNDItemMap mapContent) {
      if (!attackerMob.isPlayer) {
         return item;
      }

      int tileX = GameMath.getTileCoordinate(x);
      int tileY = GameMath.getTileCoordinate(y);
      boolean onTransceiver = level.getObject(tileX, tileY) instanceof WirelessTransceiverObject;

      if (!level.isServer()) {
         // The client predicts the pairing only, so the tooltip is right before the server's reply arrives.
         // It says nothing and opens nothing; both of those are the server's to decide, and a container in
         // particular arrives as PacketOpenContainer rather than being opened locally.
         if (onTransceiver) {
            new RemoteBinding(level, tileX, tileY).write(item);
         }

         return item;
      }

      ServerClient client = ((PlayerMob)attackerMob).getServerClient();
      if (client == null) {
         return item;
      }

      if (onTransceiver) {
         return this.pair(level, tileX, tileY, client, item);
      }

      // Clicking the placed Storage Terminal used to be how pairing worked, so saying nothing here would read as
      // the item being broken to anyone who learned the old way -- including a save where a terminal was the
      // pairing target.
      if (level.getObject(tileX, tileY) instanceof StorageTerminalObject) {
         client.sendChatMessage(Localization.translate("ui", "arcanestorage_wireless_pairtotransceiver"));
         return item;
      }

      this.open(client, item);
      return item;
   }

   /** Binds the item to the transceiver that was clicked, replacing any previous pairing. */
   private InventoryItem pair(Level level, int tileX, int tileY, ServerClient client, InventoryItem item) {
      RemoteBinding binding = new RemoteBinding(level, tileX, tileY);
      RemoteBinding existing = RemoteBinding.read(item);

      if (binding.equals(existing)) {
         // Re-pairing to the same transceiver is not an error, and saying nothing would read as the click
         // having missed. It is also the natural way a player checks that pairing worked.
         client.sendChatMessage(Localization.translate("ui", "arcanestorage_wireless_alreadypaired"));
         return item;
      }

      binding.write(item);

      // Pairing to a transceiver of a different tier is allowed, and the mismatch is worth saying at the moment it
      // is created rather than the first time the reach falls short of what the better end promised.
      UnitTier transceiver = ((WirelessTransceiverObject)level.getObject(tileX, tileY)).tier;
      if (transceiver != this.tier) {
         UnitTier governing = Reach.effective(this.tier, transceiver);
         client.sendChatMessage(Localization.translate("ui", "arcanestorage_wireless_tiermismatch",
               "tier", Localization.translate("ui", "arcanestorage_tier_" + governing.name().toLowerCase())));
      }

      // Nothing else needs doing to make the change stick or reach the client: ItemAttackerMob writes this
      // method's return value back with slot.setItem(resultItem), and that is the engine's own path for an
      // item that changes as it is used.
      client.sendChatMessage(Localization.translate("ui", "arcanestorage_wireless_pairedto",
            "x", String.valueOf(tileX), "y", String.valueOf(tileY)));
      return item;
   }

   /** Opens the paired network, loading its level if need be, or explains why it cannot. */
   private void open(ServerClient client, InventoryItem item) {
      RemoteBinding binding = RemoteBinding.read(item);
      RemoteTerminal.Resolved resolved = RemoteTerminal.resolve(client, binding);
      switch (resolved.result) {
         case UNPAIRED:
            client.sendChatMessage(Localization.translate("ui", "arcanestorage_wireless_unpaired"));
            return;
         case BAD_LEVEL:
            // Distinguished on the console but not to the player, who has one thing to do about either. Worth
            // separating at all because they read identically in chat, and that ambiguity sent an hour of
            // diagnosis at the level unload when the real cause was the region under it.
            System.out.println("Arcane Storage: a wireless terminal's level could not be resolved: " + binding);
            client.sendChatMessage(Localization.translate("ui", "arcanestorage_wireless_gone"));
            return;
         case GONE:
            client.sendChatMessage(Localization.translate("ui", "arcanestorage_wireless_gone"));
            return;
         default:
            Reach.Decision decision = Reach.check(client.playerMob, this.tier, resolved.tier(),
                  binding.levelID, binding.tileX, binding.tileY);
            if (!decision.ok()) {
               refuse(client, decision);
               return;
            }

            RemoteTerminalContainer.openAndSend(client, binding, resolved);
      }
   }

   /**
    * Says why, and what would fix it.
    *
    * <p>Two messages rather than one sentence: the reason is about the world and the hint is about equipment, and a
    * player who already knows they are out of range should not have to read the distance again to find the part
    * they did not know.
    */
   static void refuse(ServerClient client, Reach.Decision decision) {
      if (decision.verdict == Reach.Verdict.WRONG_LEVEL) {
         client.sendChatMessage(Localization.translate("ui", "arcanestorage_wireless_wronglevel"));
      } else {
         client.sendChatMessage(Localization.translate("ui", "arcanestorage_wireless_toofar",
               "distance", String.valueOf(decision.distance), "limit", String.valueOf(decision.limit)));
      }

      String what = upgradeTarget(decision);
      if (what != null) {
         client.sendChatMessage(Localization.translate("ui", "arcanestorage_wireless_upgrade", "what", what));
      }
   }

   /** Which end to upgrade, or null when no upgrade would help. */
   private static String upgradeTarget(Reach.Decision decision) {
      if (decision.upgradeTerminal && decision.upgradeTransceiver) {
         return Localization.translate("ui", "arcanestorage_wireless_upgradeboth");
      }

      if (decision.upgradeTerminal) {
         return Localization.translate("ui", "arcanestorage_wireless_terminalname");
      }

      return decision.upgradeTransceiver
            ? Localization.translate("ui", "arcanestorage_wireless_transceivername")
            : null;
   }

   /**
    * The best tier of wireless terminal in a player's inventory that is paired to a given transceiver, or null when
    * they are carrying none.
    *
    * <p>Read live rather than captured when the container opened, because the item is the link: putting it in a
    * chest, or swapping it for a lower rung, changes what the player is entitled to see. The alternative -- trusting
    * the tier that opened the window -- would let a player open at Fallen range and then bank the terminal.
    *
    * <p>Scans the bag only, which is the same inventory the tooltip and the crafting UI count. A terminal in a chest
    * is not in the player's hand by any reading.
    */
   static UnitTier heldTier(PlayerMob player, RemoteBinding binding) {
      if (player == null || player.getInv() == null || binding == null) {
         return null;
      }

      UnitTier best = null;

      // The bag and the hotbar, and nothing else: the same four flags UnitUpgrade counts with, for the same reason.
      // A terminal in cloud storage or in an inactive armour set is not in the player's hand by any reading.
      java.util.Iterator<necesse.inventory.InventorySlot> slots =
            player.getInv().streamInventorySlots(false, false, false, false).iterator();

      while (slots.hasNext()) {
         InventoryItem held = slots.next().getItem();
         if (held == null || !(held.item instanceof WirelessTerminalItem)) {
            continue;
         }

         if (!binding.equals(RemoteBinding.read(held))) {
            continue;
         }

         UnitTier tier = ((WirelessTerminalItem)held.item).tier;
         if (best == null || tier.ordinal() > best.ordinal()) {
            best = tier;
         }
      }

      return best;
   }

   /** The registered item for a tier, for recipes and for tests that need one without a registry lookup by hand. */
   public static Item of(UnitTier tier) {
      return ItemRegistry.getItem(tier.wirelessTerminalId());
   }
}
