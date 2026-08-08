package arcanestorage.container;

import java.util.ArrayList;
import java.util.List;

import arcanestorage.objectentity.StorageTerminalObjectEntity;
import arcanestorage.objectentity.StorageUnitObjectEntity;
import necesse.engine.network.NetworkClient;
import necesse.engine.network.Packet;
import necesse.engine.network.PacketWriter;
import necesse.engine.network.packet.PacketOpenContainer;
import necesse.engine.network.server.ServerClient;
import necesse.engine.registries.ContainerRegistry;
import necesse.inventory.InventoryItem;
import necesse.inventory.container.Container;
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
      if (this.NETWORK_START != -1) {
         this.addInventoryQuickTransfer(this.NETWORK_START, this.NETWORK_END);
      }
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
   public List<InventoryItem> getAggregatedItems() {
      List<InventoryItem> aggregated = new ArrayList<>();
      if (this.isNetworkEmpty()) {
         return aggregated;
      }

      Level level = this.terminal.getLevel();

      for (int index = this.NETWORK_START; index <= this.NETWORK_END; index++) {
         ContainerSlot slot = this.getSlot(index);
         InventoryItem item = slot == null ? null : slot.getItem();
         if (item == null) {
            continue;
         }

         boolean merged = false;

         for (InventoryItem existing : aggregated) {
            if (existing.equals(level, item, true, false, AGGREGATE_PURPOSE)) {
               existing.setAmount(existing.getAmount() + item.getAmount());
               merged = true;
               break;
            }
         }

         if (!merged) {
            aggregated.add(item.copy());
         }
      }

      return aggregated;
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
    * Server-side revalidation, run by the engine while the container is open. Closes the
    * container if the terminal was destroyed or the player walked out of range, which is
    * what stops a client holding a stale container open and reaching into it remotely.
    */
   @Override
   public boolean isValid(ServerClient client) {
      if (!super.isValid(client)) {
         return false;
      }

      Level level = client.getLevel();
      return !this.terminal.removed()
         && level.getObject(this.terminal.tileX, this.terminal.tileY)
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
}
