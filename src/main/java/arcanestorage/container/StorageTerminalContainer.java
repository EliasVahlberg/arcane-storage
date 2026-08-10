package arcanestorage.container;

import java.util.ArrayList;
import java.util.List;

import arcanestorage.network.NetworkContents;
import arcanestorage.objectentity.StorageTerminalObjectEntity;
import arcanestorage.objectentity.StorageUnitObjectEntity;
import necesse.engine.network.NetworkClient;
import necesse.engine.network.Packet;
import necesse.engine.network.PacketReader;
import necesse.engine.network.PacketWriter;
import necesse.engine.network.packet.PacketOpenContainer;
import necesse.engine.network.server.ServerClient;
import necesse.engine.registries.ContainerRegistry;
import necesse.entity.mobs.PlayerMob;
import necesse.inventory.InventoryRange;
import necesse.inventory.Inventory;
import necesse.inventory.InventoryItem;
import necesse.inventory.container.Container;
import necesse.inventory.container.ContainerActionResult;
import necesse.inventory.container.ContainerAction;
import necesse.inventory.container.customAction.ContainerCustomAction;
import necesse.inventory.container.slots.ContainerSlot;
import necesse.inventory.container.slots.OEInventoryContainerSlot;
import necesse.level.maps.Level;

/**
 * Server-side state for the Storage Terminal.
 *
 * <p>Modelled on {@code SalvageStationContainer}, not on {@code OEInventoryContainer}.
 * The latter cannot be reused: its only constructor takes a {@code SettlementDataEvent}
 * and builds a {@code SettlementContainerObjectStatusManager} that consumes bytes from
 * the open packet, which only the {@code registerSettlementDependantOEContainer} wire
 * format supplies. Reusing it would also hand the player the "Add Inventory to
 * Settlement Storage" button, which this mod deliberately does not want.
 *
 * <p>The terminal itself stores nothing — {@code StorageTerminalObject.SLOTS} is 0. All
 * capacity lives in the linked Storage Units, and this container is a window onto them.
 * The slots registered here are <b>real</b> {@code OEInventoryContainerSlot}s over each
 * unit's inventory, so the engine syncs them, bounds-checks any index arriving from a
 * client, and adds each unit's inventory to {@code craftInventories} on its own. The
 * aggregated, deduplicated presentation is purely a matter for the form.
 */
public class StorageTerminalContainer extends Container {

   /** Purpose string passed to the engine's item comparisons, for its debug logging. */
   public static final String AGGREGATE_PURPOSE = "arcanestorageaggregate";

   /** First container index belonging to a linked unit, or -1 when nothing is linked. */
   public int NETWORK_START = -1;
   /** Last container index belonging to a linked unit, or -1 when nothing is linked. */
   public int NETWORK_END = -1;

   public final StorageTerminalObjectEntity terminal;

   /** Client-requested withdrawal, validated and executed server-side. */
   /** Purpose recorded on deposits, kept distinct from aggregation reads. */
   public static final String DEPOSIT_PURPOSE = "arcanestoragedeposit";

   public final StorageTerminalContainer.WithdrawAction withdrawAction;
   public final StorageTerminalContainer.DepositAllAction depositAllAction;
   public final StorageTerminalContainer.DepositCursorAction depositCursorAction;

   /**
    * The units backing {@link #NETWORK_START}..{@link #NETWORK_END}, in the same order the
    * slots were registered. Captured once here so the server resolves a slot index to the
    * same unit for as long as the container is open, even if a unit is broken meanwhile.
    */
   public final List<StorageUnitObjectEntity> linkedUnits;

   public StorageTerminalContainer(NetworkClient client, int uniqueSeed, StorageTerminalObjectEntity terminal) {
      super(client, uniqueSeed);
      this.terminal = terminal;
      terminal.triggerInteracted();
      if (client.isServer()) {
         terminal.startUser(client.playerMob);
      }

      this.linkedUnits = terminal.getLinkedUnits();

      for (StorageUnitObjectEntity unit : this.linkedUnits) {
         for (int i = 0; i < unit.inventory.getSize(); i++) {
            int index = this.addSlot(new OEInventoryContainerSlot(unit, i));
            if (this.NETWORK_START == -1) {
               this.NETWORK_START = index;
            }

            this.NETWORK_END = index;
         }
      }

      // Shift-click between the player's inventory and the network, handled by the engine.
      // Registers both directions, which is also what makes withdrawal below possible.
      if (this.NETWORK_START != -1) {
         this.addInventoryQuickTransfer(this.NETWORK_START, this.NETWORK_END);
      }

      this.withdrawAction = this.registerAction(new StorageTerminalContainer.WithdrawAction());
      this.depositAllAction = this.registerAction(new StorageTerminalContainer.DepositAllAction());
      this.depositCursorAction = this.registerAction(new StorageTerminalContainer.DepositCursorAction());
   }

   /** True when no units are linked. The grid is then simply empty. */
   public boolean isNetworkEmpty() {
      return this.NETWORK_START == -1;
   }

   /**
    * The network's contents as one deduplicated list, each entry carrying the summed
    * amount across every linked unit.
    *
    * <p>Identity is {@code InventoryItem.equals(level, other, ignoreMeta, ignoreGNDData,
    * purpose)} with {@code ignoreMeta = true} and {@code ignoreGNDData = false}: amounts
    * must be ignored because they are exactly what is being summed, while GND data must
    * <b>not</b> be, or an enchanted item would merge into a plain stack and lose its
    * enchantment the moment it was withdrawn.
    *
    * <p>Entries are copies. Summing into a slot's own {@code InventoryItem} would edit the
    * unit's real contents.
    */
   /**
    * The network's contents as one deduplicated list, each entry carrying the summed
    * amount across every linked unit.
    *
    * <p>Delegates to {@link NetworkContents#aggregate}, which works over units rather than
    * container slots. Keeping one implementation matters: the scenario harness asserts
    * through the same method with no player connected, and a second copy here would let
    * the tested path drift away from the one the UI actually shows.
    */
   /** Slots holding something, across the whole network. */
   public int getUsedSlots() {
      return NetworkContents.usedSlots(this.linkedUnits);
   }

   /** Slots in total, across the whole network. Zero when no units are linked. */
   public int getTotalSlots() {
      return NetworkContents.totalSlots(this.linkedUnits);
   }

   /** Whether the network could take this item, in a free slot or on top of a matching stack. */
   public boolean canFit(InventoryItem item) {
      return NetworkContents.canFit(this.terminal.getLevel(), this.linkedUnits, item, AGGREGATE_PURPOSE);
   }

   /**
    * The network's unit inventories, as transfer targets.
    *
    * <p>Handing these to the engine's own {@code quickStackToInventories} and
    * {@code restockFromInventories} is the whole implementation of network quick-stack and
    * restock. Those methods take arbitrary targets, so the only thing that was missing was
    * somebody to pass the network in — no transfer logic is reimplemented here, which matters
    * because hand-rolled item movement is where duplication bugs come from.
    */
   private ArrayList<InventoryRange> networkTargets() {
      ArrayList<InventoryRange> targets = new ArrayList<>();

      for (StorageUnitObjectEntity unit : this.linkedUnits) {
         targets.add(new InventoryRange(unit.inventory));
      }

      return targets;
   }

   /**
    * Redirects quick-stack and restock at the network instead of at nearby containers.
    *
    * <p>Vanilla resolves both by proximity — {@code getNearbyInventories} within 192 units —
    * which is wrong inside a terminal twice over: it would miss units the network reaches
    * beyond that radius, and it would scoop up ordinary chests that are not network members.
    * Inside a terminal these two buttons should mean "my network", however far it stretches.
    *
    * <p>Everything else, including the six click conventions, falls through to the engine.
    */
   @Override
   public ContainerActionResult applyContainerAction(int slotIndex, ContainerAction action) {
      if (slotIndex == QUICK_STACK_SLOT) {
         this.quickStackToInventories(this.networkTargets(), this.client.playerMob.getInv().main);
         return new ContainerActionResult(1);
      }

      if (slotIndex == RESTOCK_SLOT) {
         this.restockFromInventories(this.networkTargets(), this.client.playerMob.getInv().main);
         return new ContainerActionResult(1);
      }

      return super.applyContainerAction(slotIndex, action);
   }

   /**
    * Moves everything the player is carrying into the network, and reports how many items moved.
    *
    * <p>Distinct from quick-stack, which only tops up items the network already holds:
    * {@code quickStackToInventories} skips a target unless it already contains that item. That
    * is the right behaviour for a button called quick-stack and the wrong behaviour for
    * "empty my bag after a mining trip", which is the actual returning-player action.
    *
    * <p>Locked slots are left alone, so the hotbar a player has deliberately pinned survives
    * the click. Anything that will not fit stays with the player rather than being destroyed.
    */
   public int depositAll() {
      Level level = this.terminal.getLevel();
      PlayerMob player = this.client.playerMob;
      Inventory inventory = player.getInv().main;
      ArrayList<InventoryRange> targets = this.networkTargets();
      int moved = 0;

      for (int slot = 0; slot < inventory.getSize(); slot++) {
         if (inventory.isSlotClear(slot) || inventory.isItemLocked(slot)) {
            continue;
         }

         for (InventoryRange target : targets) {
            InventoryItem item = inventory.getItem(slot);
            if (item == null) {
               break;
            }

            int before = item.getAmount();
            target.inventory.addItem(level, player, item, target.startSlot, target.endSlot, DEPOSIT_PURPOSE, null);
            int after = inventory.getAmount(slot);
            if (after != before) {
               inventory.markDirty(slot);
               moved += before - after;
            }

            if (after <= 0) {
               inventory.clearSlot(slot);
               break;
            }
         }
      }

      return moved;
   }

   public List<InventoryItem> getAggregatedItems() {
      if (this.isNetworkEmpty()) {
         return new ArrayList<>();
      }

      return NetworkContents.aggregate(this.terminal.getLevel(), this.linkedUnits, AGGREGATE_PURPOSE);
   }

   @Override
   public void tick() {
      super.tick();
      if (this.client.isServer()) {
         this.terminal.startUser(this.client.playerMob);
      }
   }

   @Override
   public void onClose() {
      super.onClose();
      if (this.client.isServer()) {
         this.terminal.stopUser(this.client.playerMob);
      }
   }

   /**
    * Server-side revalidation, run by {@code ServerClient} every server tick while the
    * container is open; returning false closes it via {@code closeContainer(true)}.
    *
    * <p>Closes when the terminal is destroyed or the player walks out of range, which is
    * what stops a client holding a stale container open and reaching into it remotely.
    *
    * <p><b>Also closes when any linked unit is destroyed, which fixes a real duplication
    * and loss bug.</b> The slots registered in the constructor hold a live reference to each
    * unit's {@code Inventory} object, and breaking a unit removes its object entity from the
    * world without touching that object. The slots then read and write a <b>detached
    * inventory</b>: withdrawing from it produces items the world no longer contains, and
    * depositing into it writes somewhere that will never be saved. Both were observed in
    * testing — an item appearing to duplicate and then vanishing.
    *
    * <p>Closing rather than rebuilding, because {@code Container} assembles its slot list in
    * the constructor, so a membership change cannot be represented in an open container.
    * This is also what vanilla does: every object container, from {@code OEInventoryContainer}
    * to {@code SignContainer}, closes when its backing object entity goes away.
    *
    * <p>Only removals are checked, not additions. Removal is the only way connectivity can
    * break, since objects never move — breaking a unit mid-chain removes that unit, which is
    * caught here. A unit being <i>added</i> is harmless: it simply is not shown until the
    * terminal is reopened, a stale view rather than a correctness failure.
    */
   @Override
   public boolean isValid(ServerClient client) {
      if (!super.isValid(client)) {
         return false;
      }

      if (this.terminal.removed()) {
         return false;
      }

      for (StorageUnitObjectEntity unit : this.linkedUnits) {
         if (unit.removed()) {
            return false;
         }
      }

      Level level = client.getLevel();
      return level.getObject(this.terminal.tileX, this.terminal.tileY)
         .isInInteractRange(level, this.terminal.tileX, this.terminal.tileY, client.playerMob);
   }

   /**
    * Opens the terminal for a client. Writes {@code [tileX][tileY][content]}, which is
    * what {@code ContainerRegistry.registerOEContainer}'s reader expects —
    * {@code PacketOpenContainer.LevelObject} and {@code .ObjectEntity} produce the same
    * layout, so either works.
    */
   public static void openAndSendContainer(int containerID, ServerClient client, Level level, int tileX, int tileY, Packet extraContent) {
      if (!level.isServer()) {
         throw new IllegalStateException("Level must be a server level");
      }

      Packet packet = new Packet();
      PacketWriter writer = new PacketWriter(packet);
      if (extraContent != null) {
         writer.putNextContentPacket(extraContent);
      }

      ContainerRegistry.openAndSendContainer(client, PacketOpenContainer.LevelObject(containerID, tileX, tileY, packet));
   }

   public static void openAndSendContainer(int containerID, ServerClient client, Level level, int tileX, int tileY) {
      openAndSendContainer(containerID, client, level, tileX, tileY, null);
   }

   /**
    * Withdraws an item from the network into the player's inventory.
    *
    * <p>Modelled on {@code ShopContainer.BuyItemAction}. Two properties matter for
    * correctness:
    *
    * <ul>
    *   <li><b>The client sends an item, not a slot index.</b> An aggregated entry has no
    *       single slot — 60 iron may be spread over three units — so the server searches
    *       its own slots for matches instead of trusting a position. A bogus item simply
    *       matches nothing and the action is a no-op.
    *   <li><b>Every move goes through {@code transferFromAmount}</b>, the engine's own
    *       transfer primitive, which handles stacking and partial moves and reports how
    *       much actually moved. Nothing here adds or subtracts item amounts by hand, which
    *       is where duplication bugs come from.
    * </ul>
    *
    * <p>Note {@code runAndSendAction} also executes locally, so this runs on the client as
    * optimistic prediction and on the server as the authority, which is the engine's
    * intended pattern — the server's slot sync corrects any divergence.
    */
   /**
    * Deposit everything the player carries.
    *
    * <p>Carries no payload: the server decides what the player has and where it goes, so
    * there is nothing for a client to misreport. Contrast {@link WithdrawAction}, which must
    * name an item because the player is choosing one.
    */
   public class DepositAllAction extends ContainerCustomAction {

      public void runAndSend() {
         this.runAndSendAction(new Packet());
      }

      @Override
      public void executePacket(PacketReader reader) {
         StorageTerminalContainer.this.depositAll();
      }
   }

   /**
    * Puts what the player is holding into the network.
    *
    * <p>The cursor is reached through {@link #getClientDraggingSlot()} rather than through the
    * player's drag inventory, and the move runs here on the server, because a client that edited
    * its own inventory would be inventing state the server never agreed to. That is the mistake
    * this project's notes warn about specifically: singleplayer is a real server, so a shortcut
    * here would work locally and desync in multiplayer.
    *
    * <p>Insertion reuses {@link Inventory#addItem}, the same call {@link #depositAll()} uses, so a
    * deposited stack tops up partial stacks before it takes an empty slot without this having to
    * know how stacking works.
    */
   public class DepositCursorAction extends ContainerCustomAction {

      /**
       * @param amount how much of the held stack to insert, or a non-positive value for all of it.
       */
      public void runAndSend(int amount) {
         Packet content = new Packet();
         PacketWriter writer = new PacketWriter(content);
         writer.putNextInt(amount);
         this.runAndSendAction(content);
      }

      @Override
      public void executePacket(PacketReader reader) {
         int requestedAmount = reader.getNextInt();
         ContainerSlot cursor = StorageTerminalContainer.this.getClientDraggingSlot();
         InventoryItem held = cursor == null ? null : cursor.getItem();
         if (held == null) {
            return;
         }

         // The client asked for an amount; it does not get to ask for more than it holds.
         int requested = requestedAmount <= 0 ? held.getAmount() : Math.min(requestedAmount, held.getAmount());
         if (requested <= 0) {
            return;
         }

         Level level = StorageTerminalContainer.this.terminal.getLevel();
         PlayerMob player = StorageTerminalContainer.this.client.playerMob;
         InventoryItem moving = held.copy(requested);

         for (InventoryRange target : StorageTerminalContainer.this.networkTargets()) {
            target.inventory.addItem(level, player, moving, target.startSlot, target.endSlot, DEPOSIT_PURPOSE, null);
            if (moving.getAmount() <= 0) {
               break;
            }
         }

         // addItem decrements what it consumed from the item it was given, so what is left on the
         // copy is what the network refused -- a full network therefore leaves the cursor untouched
         // rather than eating the stack.
         int moved = requested - moving.getAmount();
         if (moved <= 0) {
            return;
         }

         int remaining = held.getAmount() - moved;
         if (remaining <= 0) {
            cursor.setItem(null);
         } else {
            cursor.setAmount(remaining);
         }

         cursor.markDirty();
      }
   }

   public class WithdrawAction extends ContainerCustomAction {

      /**
       * @param toCursor true to pick the items up onto the cursor, as a left click on any
       *                 inventory slot would; false to transfer them straight into the
       *                 player's inventory, as a shift-click would.
       */
      public void runAndSend(InventoryItem item, int amount, boolean toCursor) {
         Packet content = new Packet();
         PacketWriter writer = new PacketWriter(content);
         item.addPacketContent(writer);
         writer.putNextInt(amount);
         writer.putNextBoolean(toCursor);
         this.runAndSendAction(content);
      }

      @Override
      public void executePacket(PacketReader reader) {
         InventoryItem wanted = InventoryItem.fromContentPacket(reader);
         int requested = reader.getNextInt();
         boolean toCursor = reader.getNextBoolean();
         if (wanted == null || StorageTerminalContainer.this.isNetworkEmpty()) {
            return;
         }

         // Never trust the requested amount: one click yields at most one stack.
         int remaining = Math.min(Math.max(requested, 0), wanted.item.getStackSize());
         Level level = StorageTerminalContainer.this.terminal.getLevel();
         ContainerSlot cursor = StorageTerminalContainer.this.getClientDraggingSlot();

         for (int index = StorageTerminalContainer.this.NETWORK_START;
              index <= StorageTerminalContainer.this.NETWORK_END && remaining > 0;
              index++) {
            ContainerSlot slot = StorageTerminalContainer.this.getSlot(index);
            InventoryItem held = slot == null ? null : slot.getItem();
            if (held == null || !held.equals(level, wanted, true, false, AGGREGATE_PURPOSE)) {
               continue;
            }

            if (toCursor) {
               // combineSlots caps at the cursor's remaining stack space by itself, and
               // fails without moving anything if the cursor holds something else — so a
               // click with an unrelated item held is a no-op rather than a swap.
               int before = cursor.getItemAmount();
               if (!cursor.combineSlots(level, StorageTerminalContainer.this.client.playerMob, slot, remaining, true, false, AGGREGATE_PURPOSE)
                  .success) {
                  break;
               }

               remaining -= cursor.getItemAmount() - before;
            } else {
               ContainerActionResult result = StorageTerminalContainer.this.transferFromAmount(index, slot, remaining);
               if (result.value <= 0) {
                  // The player's inventory is full, or this slot refused. Either way, stop
                  // rather than spinning over the remaining slots.
                  break;
               }

               remaining -= result.value;
            }
         }
      }
   }
}
