package arcanestorage.container;

import arcanestorage.objectentity.StorageTerminalObjectEntity;
import necesse.engine.network.NetworkClient;
import necesse.engine.network.Packet;
import necesse.engine.network.PacketWriter;
import necesse.engine.network.packet.PacketOpenContainer;
import necesse.engine.network.server.ServerClient;
import necesse.engine.registries.ContainerRegistry;
import necesse.inventory.container.Container;
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
 * <p>Step 1 of Phase 1: this behaves exactly like a chest over its own inventory.
 * Aggregation across linked containers comes in step 3.
 */
public class StorageTerminalContainer extends Container {

   /** Container index of the terminal's first own slot. */
   public int TERMINAL_START = -1;
   /** Container index of the terminal's last own slot. */
   public int TERMINAL_END = -1;

   public final StorageTerminalObjectEntity terminal;

   public StorageTerminalContainer(NetworkClient client, int uniqueSeed, StorageTerminalObjectEntity terminal) {
      super(client, uniqueSeed);
      this.terminal = terminal;
      terminal.triggerInteracted();
      if (client.isServer()) {
         terminal.startUser(client.playerMob);
      }

      for (int i = 0; i < terminal.inventory.getSize(); i++) {
         int index = this.addSlot(new OEInventoryContainerSlot(terminal, i));
         if (this.TERMINAL_START == -1) {
            this.TERMINAL_START = index;
         }

         this.TERMINAL_END = index;
      }

      this.addInventoryQuickTransfer(this.TERMINAL_START, this.TERMINAL_END);
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
