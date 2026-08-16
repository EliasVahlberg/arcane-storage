package arcanestorage.remote;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import arcanestorage.ArcaneStorage;
import arcanestorage.object.UnitTier;
import arcanestorage.container.StorageTerminalContainer;
import arcanestorage.network.NetworkStations;
import arcanestorage.network.NetworkStorage;
import necesse.entity.objectEntity.ObjectEntity;
import arcanestorage.objectentity.BusSummary;
import arcanestorage.objectentity.StorageTerminalObjectEntity;
import necesse.engine.localization.message.GameMessage;
import necesse.engine.network.NetworkClient;
import necesse.engine.network.Packet;
import necesse.engine.network.PacketReader;
import necesse.engine.network.packet.PacketOpenContainer;
import necesse.engine.network.server.ServerClient;
import necesse.engine.registries.ContainerRegistry;
import necesse.inventory.Inventory;
import necesse.inventory.InventoryItem;
import necesse.inventory.container.slots.ContainerSlot;
import necesse.level.maps.Level;

/**
 * The terminal container, opened from a wireless terminal instead of from a tile in front of you.
 *
 * <p>A subclass rather than a second container, and it overrides four things: where its membership comes from,
 * what "still valid" means, keeping the paired level loaded, and mirroring slots to a client that cannot see
 * them. Everything else -- withdrawing, depositing, crafting, bus rules, all of the tabs -- is the parent's,
 * unchanged and unaware.
 *
 * <h2>Why it is registered separately from the local terminal</h2>
 *
 * <p>{@code ContainerRegistry.registerOEContainer} funnels through {@code registerLevelContainer}, which
 * resolves the level as {@code client.getLevel()} on the client and {@code world.getLevel(client)} on the
 * server -- the <i>player's</i> level, both times. That is correct for every container in the game, which is
 * opened by touching something, and it is precisely what a wireless terminal cannot use. So this registers
 * through the plain {@code registerContainer} and resolves the level itself from the binding in the content
 * packet.
 *
 * <h2>What the server holds versus what the client holds</h2>
 *
 * <p>Server side, this is an ordinary terminal container: the real object entity, the real units, real slots on
 * real inventories. Nothing that moves an item ever touches a copy, which is the property that matters most --
 * a mirror that drifted would be a display bug, while a mirror that items were moved through would be a
 * duplication bug.
 *
 * <p>Client side, the units are stand-ins from {@link RemoteNetworkShape}: inventories of the right sizes, at
 * the right slot indices, filled by {@link SlotMirrorEvent}. The client can therefore compute the aggregate
 * grid, preview craftability and show installed benches exactly as it does locally, because as far as it knows
 * it is local.
 */
public class RemoteTerminalContainer extends StorageTerminalContainer {

   /** The paired terminal, on both sides. */
   public final RemoteBinding binding;

   /** The level the network is on. Server-side only; null on a client. */
   private final Level remoteLevel;

   /**
    * The Logistics tab's contents on a remote client, and on the server the copy the client was last sent.
    *
    * <p>Held rather than read through because the terminal entity's own bus list only reaches clients through
    * its content packet, which travels by proximity.
    */
   private List<BusSummary> buses = new ArrayList<>();

   /** Parsed open-content, so both halves can be built from one read of the packet. */
   private static final class Parsed {

      final RemoteBinding binding;

      final StorageTerminalObjectEntity terminal;

      final Level level;

      final List<NetworkStations> stationUnits;

      final List<NetworkStorage> units;

      final GameMessage name;

      final RemoteNetworkShape shape;

      private Parsed(RemoteBinding binding, StorageTerminalObjectEntity terminal, Level level,
            List<NetworkStations> stationUnits, List<NetworkStorage> units, GameMessage name,
            RemoteNetworkShape shape) {
         this.binding = binding;
         this.terminal = terminal;
         this.level = level;
         this.stationUnits = stationUnits;
         this.units = units;
         this.name = name;
         this.shape = shape;
      }

      static Parsed of(NetworkClient client, Packet content) {
         if (client.isServer()) {
            // The server re-resolves rather than being handed the terminal, because openAndSendContainer sends
            // the packet and then opens locally from the same bytes -- so this path runs from the packet either
            // way, and having one source of truth for it is worth more than saving a lookup.
            RemoteBinding binding = RemoteBinding.fromPacket(new PacketReader(content));
            RemoteTerminal.Resolved resolved = RemoteTerminal.resolve(client.getServerClient(), binding);
            if (!resolved.ok()) {
               throw new IllegalStateException("Remote terminal " + binding + " could not be resolved: "
                     + resolved.result);
            }

            StorageTerminalObjectEntity terminal = resolved.terminal;
            return new Parsed(binding, terminal, resolved.level, terminal.getLinkedStationUnits(),
                  terminal.getLinkedUnits(), terminal.getInventoryName(), null);
         }

         RemoteNetworkShape shape = RemoteNetworkShape.fromPacket(new PacketReader(content));
         return new Parsed(shape.binding, null, null, shape.stationUnits(), shape.units(), shape.name, shape);
      }
   }

   public RemoteTerminalContainer(NetworkClient client, int uniqueSeed, Packet content) {
      this(client, uniqueSeed, Parsed.of(client, content));
   }

   private RemoteTerminalContainer(NetworkClient client, int uniqueSeed, Parsed parsed) {
      super(client, uniqueSeed, parsed.terminal, parsed.stationUnits, parsed.units, parsed.name);
      this.binding = parsed.binding;
      this.remoteLevel = parsed.level;

      // Slot mirroring itself is the base's, and is driven by what the client says it stood in for. Every member here
      // is a stand-in, so that request covers the whole network and this class no longer keeps a second copy of the
      // machinery. What stays here is the bus list: a client standing next to a terminal receives it through the
      // entity's own content packet, and a client on another level never can.
      if (!client.isServer()) {
         this.applyShape(parsed.shape);
         this.subscribeEvent(BusMirrorEvent.class, event -> true, () -> true);
         this.onEvent(BusMirrorEvent.class, event -> this.buses = event.buses);
      }
   }

   /** Fills the stand-in inventories with what the open packet carried. */
   private void applyShape(RemoteNetworkShape shape) {
      if (shape == null) {
         return;
      }

      this.buses = new ArrayList<>(shape.buses);

      for (RemoteNetworkShape.SlotItem entry : shape.contents) {
         this.writeSlot(entry.index, entry.item);
      }
   }

   /**
    * Writes one mirrored slot on the client.
    *
    * <p>Through the container slot rather than the inventory, so a slot index the server thinks exists but this
    * client does not is dropped instead of throwing. The two sides derive their slot lists from the same numbers
    * and should never disagree, but "should never" in a packet handler is worth one bounds check.
    */
   private void writeSlot(int index, InventoryItem item) {
      if (index < 0 || index >= this.slotCount) {
         return;
      }

      ContainerSlot slot = this.getSlot(index);
      if (slot != null) {
         slot.setItem(item);
      }
   }

   /**
    * Sends whatever changed, and keeps the paired level from being unloaded underneath us.
    *
    * <p>The pin has to happen every tick: {@code Level.unloadLevelBuffer} counts up once per level tick and the
    * server unloads anything past the cooldown, saving it and dropping it from the manager. This container's
    * slots point straight at that level's inventories, so an unload while open would leave every deposit
    * writing into an object that is never saved again.
    */
   @Override
   public void tick() {
      super.tick();
      if (!this.client.isServer()) {
         return;
      }

      RemoteTerminal.pin(this.remoteLevel, this.pinnedTiles());
      this.sendBuses();
   }

   /**
    * Pushes the bus list when it differs from the copy the client holds.
    *
    * <p>Compared every tick, which is affordable because the terminal's {@code getBuses()} is a field read and a
    * network has a handful of buses. Comparison is on value -- {@code BusSummary} gained an {@code equals} for
    * this -- so a bus merely being re-derived does not cost a packet, while a warning appearing does.
    */
   private void sendBuses() {
      List<BusSummary> current = super.getBuses();
      if (this.buses.equals(current)) {
         return;
      }

      this.buses = new ArrayList<>(current);
      new BusMirrorEvent(this.buses).applyAndSendToClient(this.client.getServerClient());
   }

   /**
    * The buses to show. The real list server-side, the mirrored one on a remote client.
    *
    * <p>Overridden rather than left to the parent's null check, which returned an empty list and made the
    * Logistics tab read as a network with no buses at all.
    */
   @Override
   public List<BusSummary> getBuses() {
      return this.client.isServer() ? super.getBuses() : this.buses;
   }

   /**
    * Every tile whose region must stay loaded: the terminal, and each unit holding an inventory this container
    * can write into.
    *
    * <p>Rebuilt each tick rather than cached at open, because a network's membership changes while it is being
    * looked at -- a unit placed by somebody else, or one broken -- and a stale list would either pin a region
    * nothing needs or, worse, stop pinning one that is still being written to.
    */
   private List<long[]> pinnedTiles() {
      List<long[]> tiles = new ArrayList<>();
      if (this.terminal != null) {
         tiles.add(new long[] {this.terminal.tileX, this.terminal.tileY});
      }

      for (NetworkStations station : this.stationUnits) {
         ObjectEntity entity = station.getObjectEntity();
         if (entity != null) {
            tiles.add(new long[] {entity.tileX, entity.tileY});
         }
      }

      for (NetworkStorage unit : this.linkedUnits) {
         ObjectEntity entity = unit.getObjectEntity();
         if (entity != null) {
            tiles.add(new long[] {entity.tileX, entity.tileY});
         }
      }

      // Buses too, though nothing the container does touches them. The Logistics tab reports each bus's state
      // while it is being watched, and a bus whose region has unloaded is not moving anything -- so leaving them
      // out would mean a tab that reads "active" for devices that had quietly stopped. It also means importing
      // and exporting keep working while somebody is looking at them from elsewhere, which is the behaviour a
      // player would assume from being able to see them at all.
      for (BusSummary bus : super.getBuses()) {
         tiles.add(new long[] {bus.tileX, bus.tileY});
      }

      return tiles;
   }

   /** Forgets everything, for the harness between scenarios. */
   public static void forget() {
      StorageTerminalContainer.forgetOpen();
   }

   /**
    * Distance is not a reason to close a wireless terminal -- that is the entire feature.
    *
    * <p>What is still checked, in {@link StorageTerminalContainer#isValid}, is everything that prevents writing
    * into something detached: the terminal still exists, and every unit is still on the network. Added here is
    * the one failure this container can have and a local one cannot: the level being unloaded despite the pin,
    * which would leave these slots pointing at inventories nothing saves. Identity comparison catches an unload
    * followed by a reload too, since that produces different objects.
    */
   /**
    * Never the terminal this container was opened with.
    *
    * <p>See the base method. Matched by binding rather than by type, so a spare terminal paired somewhere else still
    * deposits -- a player tidying up their bag through a network they are looking at should not have that refused
    * because of what the item is.
    */
   @Override
   protected boolean depositable(InventoryItem item) {
      return item == null || !(item.item instanceof WirelessTerminalItem)
            || !this.binding.equals(RemoteBinding.read(item));
   }

   @Override
   protected boolean isWithinReach(ServerClient client) {
      if (this.remoteLevel == null) {
         return false;
      }

      Level loaded = client.getServer().world.levelManager.getLevel(this.remoteLevel.getIdentifier());
      if (loaded != this.remoteLevel) {
         return false;
      }

      // The tier is re-read from the player's bag every tick rather than remembered from the open, because the item
      // is the link. Banking the terminal after opening at Fallen range, or swapping down a rung, has to end the
      // session it entitled -- otherwise the ladder is a one-time toll rather than a range.
      UnitTier held = WirelessTerminalItem.heldTier(client.playerMob, this.binding);
      if (held == null) {
         return false;
      }

      // And the range itself, every tick, so walking away closes what walking closer opened. Checking only at open
      // time would put the number in the tooltip and nowhere else.
      RemoteTerminal.Resolved resolved = RemoteTerminal.resolve(client, this.binding);
      if (!resolved.ok()) {
         return false;
      }

      return Reach.check(client.playerMob, held, resolved.tier(),
            this.binding.levelID, this.binding.tileX, this.binding.tileY).ok();
   }

   /** Opens a wireless terminal for a client. The content is the binding server-side, the shape client-side. */
   public static void openAndSend(ServerClient client, RemoteBinding binding, RemoteTerminal.Resolved resolved) {
      RemoteNetworkShape shape = shapeOf(binding, resolved);
      PacketOpenContainer packet = new PacketOpenContainer(ArcaneStorage.REMOTE_TERMINAL_CONTAINER,
            shape.toPacket());

      // Sent to the client first, then opened locally -- ContainerRegistry.openAndSendContainer does both, in
      // that order. The two halves read different things out of the same packet: the client reads the shape, the
      // server reads only the binding at the front of it and resolves the rest itself.
      ContainerRegistry.openAndSendContainer(client, packet);
   }

   /**
    * What to tell the client about a network it cannot see: the sizes, in the order the container will register
    * them, and nothing about contents.
    *
    * <p>The order here is the contract. It must match the parent constructor's registration exactly -- station
    * sockets first, in the terminal's own enumeration order, then storage units -- because that is what makes a
    * slot index mean one thing to both sides.
    */
   private static RemoteNetworkShape shapeOf(RemoteBinding binding, RemoteTerminal.Resolved resolved) {
      List<NetworkStations> stationUnits = resolved.terminal.getLinkedStationUnits();
      List<NetworkStorage> units = resolved.terminal.getLinkedUnits();

      int[] socketCounts = new int[stationUnits.size()];
      for (int i = 0; i < socketCounts.length; i++) {
         Inventory sockets = stationUnits.get(i).getInventory();
         socketCounts[i] = sockets == null ? 0 : sockets.getSize();
      }

      int[] unitSizes = new int[units.size()];
      for (int i = 0; i < unitSizes.length; i++) {
         Inventory storage = units.get(i).getInventory();
         unitSizes[i] = storage == null ? 0 : storage.getSize();
      }

      return new RemoteNetworkShape(binding, resolved.terminal.getInventoryName(), socketCounts, unitSizes,
            new ArrayList<>(), new ArrayList<>(resolved.terminal.getBuses()));
   }
}
