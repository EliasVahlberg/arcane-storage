package arcanestorage.objectentity;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import arcanestorage.network.NetworkConductor;
import arcanestorage.network.NetworkStorage;
import arcanestorage.network.TransferRules;
import arcanestorage.network.UnitNetwork;
import necesse.engine.save.LoadData;
import necesse.engine.save.SaveData;
import necesse.entity.objectEntity.ObjectEntity;
import necesse.entity.objectEntity.interfaces.OEInventory;
import necesse.inventory.Inventory;
import necesse.inventory.InventoryItem;
import necesse.level.maps.Level;

/**
 * Shared behaviour of the import and export buses: find a network, find a container, move items.
 *
 * <p><b>A bus is an entry point, exactly like a terminal.</b> It walks the network from its own tile
 * with {@link UnitNetwork}, which means it is a {@code NetworkNode} and not a {@link NetworkConductor}:
 * the network meets a bus and does not pass through it. That is not a limitation to work around — it is
 * what stops a chest becoming a bridge between two networks that a player believes are separate.
 *
 * <p><b>The container on the other side is found by capability, not by type.</b> Any neighbouring object
 * entity that is an {@link OEInventory} and is <i>not</i> a {@link NetworkStorage} qualifies, so every
 * chest, barrel, crate and cabinet in the game works, along with modded ones this mod has never heard
 * of, and network members are excluded so a bus cannot shuffle items inside the network it belongs to.
 *
 * <p>This is the indirection that answers "can ordinary chests join the network". They do not join:
 * settlers keep using a chest they already understand, the bus carries its contents across, and nothing
 * in the network is ever exposed to settler access.
 *
 * <p><b>Conservation is the property that matters.</b> Items are added first and removed by exactly what
 * the destination accepted, so a full destination leaves the source untouched and no path through this
 * code can duplicate or destroy a stack. That ordering is deliberate and should not be reversed for
 * tidiness.
 */
public abstract class BusObjectEntity extends ObjectEntity {

   /**
    * Ticks between transfers. 20 is one second at the server's tick rate.
    *
    * <p>Not every tick, for two reasons: moving one stack per second is legible to a player watching a
    * chest empty, and a bus that scanned a network of 64 units twenty times a second would cost more
    * than the interface it feeds.
    */
   public static final int TRANSFER_INTERVAL = 20;

   /** Most items moved per transfer. One vanilla stack, so a chest empties at a visible pace. */
   public static final int MAX_PER_TRANSFER = 40;

   /** Named in item-move logs, which is how the game attributes changes for settlement bookkeeping. */
   protected static final String PURPOSE = "arcanestoragebus";

   public final TransferRules rules;

   private int ticksUntilTransfer = TRANSFER_INTERVAL;

   protected BusObjectEntity(Level level, String stringID, int x, int y, boolean emptyMovesEverything) {
      super(level, stringID, x, y);
      this.rules = new TransferRules(emptyMovesEverything);
   }

   /**
    * Where items come from, given the network and the attached container.
    *
    * <p>Both sides are lists because one of them always is: the network is many inventories. Treating
    * the single container as a one-element list keeps the transfer written once instead of twice, and
    * avoids the obvious-looking alternative of faking an {@code Inventory} that spans the network --
    * {@code Inventory} is a concrete class with dirty tracking, filters and locked slots, and a view
    * that reimplemented some of that would be a subtle liar.
    */
   protected abstract List<Inventory> sources(List<NetworkStorage> network, Inventory container);

   /** Where they go. The other side. */
   protected abstract List<Inventory> destinations(List<NetworkStorage> network, Inventory container);

   @Override
   public void serverTick() {
      super.serverTick();
      if (!this.isServer()) {
         return;
      }

      if (--this.ticksUntilTransfer > 0) {
         return;
      }

      this.ticksUntilTransfer = TRANSFER_INTERVAL;
      this.transferOnce();
   }

   /**
    * Moves at most {@link #MAX_PER_TRANSFER} of one item.
    *
    * <p>One item per interval rather than a full sweep: it bounds the work per tick regardless of how
    * many distinct items a chest holds, and it makes the pace independent of the layout.
    *
    * @return how many items moved, for tests and for the status message
    */
   public int transferOnce() {
      Level level = this.getLevel();
      if (level == null) {
         return 0;
      }

      Inventory container = this.attachedContainer();
      if (container == null) {
         return 0;
      }

      List<NetworkStorage> network = this.network();
      if (network.isEmpty()) {
         return 0;
      }

      List<Inventory> from = this.sources(network, container);
      List<Inventory> to = this.destinations(network, container);

      for (Inventory fromInventory : from) {
         for (int slot = 0; slot < fromInventory.getSize(); slot++) {
            InventoryItem item = fromInventory.getItem(slot);
            if (item == null) {
               continue;
            }

            String itemID = item.item.getStringID();
            int allowed = this.rules.allowed(itemID, countIn(from, itemID), countIn(to, itemID));
            if (allowed <= 0) {
               continue;
            }

            int wanted = Math.min(Math.min(allowed, MAX_PER_TRANSFER), item.getAmount());
            int moved = move(level, fromInventory, to, item, wanted);
            if (moved > 0) {
               return moved;
            }
         }
      }

      return 0;
   }

   /**
    * Adds up to {@code amount}, then removes exactly what was accepted.
    *
    * <p>{@code Inventory.addItem} decrements the item it is handed by what it took, so the remainder is
    * what the destination refused. Removing that difference rather than the requested amount is what
    * makes a full destination a no-op instead of a hole.
    */
   private static int move(Level level, Inventory from, List<Inventory> to, InventoryItem item, int amount) {
      InventoryItem moving = item.copy();
      moving.setAmount(amount);

      for (Inventory destination : to) {
         if (moving.getAmount() <= 0) {
            break;
         }

         destination.addItem(level, null, moving, PURPOSE, null);
      }

      int accepted = amount - moving.getAmount();
      if (accepted <= 0) {
         return 0;
      }

      return from.removeItems(level, null, item.item, accepted, PURPOSE);
   }

   /** How many of one item a side holds, across every inventory and every slot. */
   protected static int countIn(List<Inventory> side, String itemStringID) {
      int total = 0;

      for (Inventory inventory : side) {
         total += countIn(inventory, itemStringID);
      }

      return total;
   }

   /** How many of one item an inventory holds, across every slot. Public: the harness reads chests with it. */
   public static int countIn(Inventory inventory, String itemStringID) {
      int total = 0;

      for (int slot = 0; slot < inventory.getSize(); slot++) {
         InventoryItem item = inventory.getItem(slot);
         if (item != null && item.item.getStringID().equals(itemStringID)) {
            total += item.getAmount();
         }
      }

      return total;
   }

   /**
    * The network this bus belongs to, walked from its own tile.
    *
    * <p>Recomputed rather than stored, for the same reason the terminal recomputes it: membership is a
    * pure function of the layout, so breaking a unit needs no cleanup anywhere.
    */
   public List<NetworkStorage> network() {
      final Level level = this.getLevel();
      return UnitNetwork.discover(this.tileX, this.tileY, (x, y) -> {
         ObjectEntity candidate = level.entityManager.getObjectEntity(x, y);
         if (candidate instanceof NetworkStorage) {
            NetworkStorage member = (NetworkStorage)candidate;
            return member.isOnNetwork() ? member : null;
         }

         return null;
      }, (x, y) -> level.getObject(x, y) instanceof NetworkConductor,
         StorageTerminalObjectEntity.MAX_UNITS, StorageTerminalObjectEntity.MAX_CONDUITS);
   }

   /**
    * The inventory of the neighbouring container this bus serves, or null when there is none.
    *
    * <p>Orthogonal neighbours only, in {@link UnitNetwork#NEIGHBOURS} order, and the first match wins —
    * so a bus between two chests is not ambiguous in behaviour, only in appearance, and the fix for that
    * is a facing sprite rather than a rule.
    */
   public Inventory attachedContainer() {
      Level level = this.getLevel();
      if (level == null) {
         return null;
      }

      for (int[] offset : UnitNetwork.NEIGHBOURS) {
         ObjectEntity neighbour =
            level.entityManager.getObjectEntity(this.tileX + offset[0], this.tileY + offset[1]);
         if (neighbour instanceof OEInventory && !(neighbour instanceof NetworkStorage)
               && !neighbour.removed()) {
            Inventory inventory = ((OEInventory)neighbour).getInventory();
            if (inventory != null) {
               return inventory;
            }
         }
      }

      return null;
   }

   /** Every member's inventory, in network order, so transfers fill the network the way it is walked. */
   protected static List<Inventory> inventoriesOf(List<NetworkStorage> network) {
      List<Inventory> inventories = new ArrayList<>(network.size());
      for (NetworkStorage member : network) {
         inventories.add(member.getInventory());
      }

      return inventories;
   }

   @Override
   public void addSaveData(SaveData save) {
      super.addSaveData(save);
      this.rules.addSaveData(save);
   }

   @Override
   public void applyLoadData(LoadData save) {
      super.applyLoadData(save);
      this.rules.applyLoadData(save);
   }
}
