package arcanestorage.remote;

import arcanestorage.object.StorageTerminalObject;
import necesse.engine.localization.Localization;
import necesse.engine.network.gameNetworkData.GNDItemMap;
import necesse.engine.network.server.ServerClient;
import necesse.engine.util.GameMath;
import necesse.entity.mobs.PlayerMob;
import necesse.entity.mobs.itemAttacker.ItemAttackSlot;
import necesse.entity.mobs.itemAttacker.ItemAttackerMob;
import necesse.gfx.gameTooltips.ListGameTooltips;
import necesse.inventory.InventoryItem;
import necesse.inventory.item.Item;
import necesse.inventory.item.ItemInteractAction;
import necesse.level.maps.Level;

/**
 * A Storage Terminal you carry: paired to one placed terminal, and opens its network from anywhere.
 *
 * <h2>One click does both jobs</h2>
 *
 * <p>Clicking a Storage Terminal with this in hand pairs it. Clicking anything else opens the network it
 * is already paired to. Both arrive through {@link ItemInteractAction#onLevelInteract}, which is the hook
 * {@code WrenchPlaceableItem} uses to reach the object at a clicked tile, and
 * {@link #overridesObjectInteract} makes the click reach this item before the terminal's own
 * right-click — otherwise pairing would be impossible, because clicking a terminal would just open it.
 *
 * <p>The alternative was a pairing mode, or a button inside the terminal UI. Both need the player to learn
 * something; "use the thing on the thing" needs no explanation and matches how the wrench, the settlement
 * flag and every placeable already behave.
 *
 * <h2>Stack size one, and why that is a correctness decision rather than flavour</h2>
 *
 * <p>Two of these paired to different networks must never merge into one stack, because merging keeps one
 * GND map and discards the other — a player would lose a pairing silently and blame the mod, correctly.
 * {@code GatewayTabletItem} solves the same problem for incursion data by overriding
 * {@code canCombineItem} and {@code isSameGNDData}. A stack size of one removes the case instead of
 * handling it, which is the smaller thing to be right about.
 */
public class WirelessTerminalItem extends Item implements ItemInteractAction {

   public WirelessTerminalItem() {
      super(1);
      this.rarity = Item.Rarity.UNCOMMON;
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

      return tooltips;
   }

   /**
    * True for any tile, so a click always uses the item rather than swinging it.
    *
    * <p>Deliberately not restricted to terminals. Restricting it would make the item inert everywhere
    * except in front of the thing it is meant to replace visiting.
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
    * One click does both jobs: on a placed terminal it pairs, anywhere else it opens what it is paired to.
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
      boolean onTerminal = level.getObject(tileX, tileY) instanceof StorageTerminalObject;

      if (!level.isServer()) {
         // The client predicts the pairing only, so the tooltip is right before the server's reply arrives.
         // It says nothing and opens nothing; both of those are the server's to decide, and a container in
         // particular arrives as PacketOpenContainer rather than being opened locally.
         if (onTerminal) {
            new RemoteBinding(level, tileX, tileY).write(item);
         }

         return item;
      }

      ServerClient client = ((PlayerMob)attackerMob).getServerClient();
      if (client == null) {
         return item;
      }

      if (onTerminal) {
         return this.pair(level, tileX, tileY, client, item);
      }

      this.open(client, item);
      return item;
   }

   /** Binds the item to the terminal that was clicked, replacing any previous pairing. */
   private InventoryItem pair(Level level, int tileX, int tileY, ServerClient client, InventoryItem item) {
      RemoteBinding binding = new RemoteBinding(level, tileX, tileY);
      RemoteBinding existing = RemoteBinding.read(item);

      if (binding.equals(existing)) {
         // Re-pairing to the same terminal is not an error, and saying nothing would read as the click
         // having missed. It is also the natural way a player checks that pairing worked.
         client.sendChatMessage(Localization.translate("ui", "arcanestorage_wireless_alreadypaired"));
         return item;
      }

      binding.write(item);

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
         case GONE:
            client.sendChatMessage(Localization.translate("ui", "arcanestorage_wireless_gone"));
            return;
         default:
            RemoteTerminalContainer.openAndSend(client, binding, resolved);
      }
   }
}
