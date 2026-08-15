package arcanestorage.remote;

import java.util.ArrayList;
import java.util.List;
import necesse.engine.localization.message.GameMessage;
import necesse.engine.localization.message.StaticMessage;
import necesse.engine.network.Packet;
import necesse.engine.network.PacketReader;
import necesse.engine.network.PacketWriter;
import necesse.entity.objectEntity.ObjectEntity;
import necesse.inventory.Inventory;
import necesse.inventory.InventoryItem;
import arcanestorage.network.NetworkStations;
import arcanestorage.objectentity.BusSummary;
import arcanestorage.network.NetworkStorage;

/**
 * What a remote client is told about a network it cannot see, and the stand-ins it builds from that.
 *
 * <h2>Why the client needs telling at all</h2>
 *
 * <p>A local terminal's client walks the network itself: the object entities are on its level, the engine has
 * synced their inventories because the player is standing next to them, and both sides independently arrive at
 * the same slot list. None of that is available from another level. A client holds exactly the level it is
 * standing on — {@code ContainerRegistry.registerLevelContainer} bakes that in, resolving the level as
 * {@code client.getLevel()} — so a remote client cannot discover the network, cannot count its slots, and
 * cannot read a single item out of it.
 *
 * <p><b>And the engine has no remote-inventory sync to borrow.</b> That was the decisive finding for this whole
 * feature, so it is worth recording where it was checked rather than left as an assumption: there is no
 * container-slot packet in {@code necesse/engine/network/packet/} at all — the container ones are
 * {@code PacketOpenContainer}, {@code PacketCloseContainer}, {@code PacketContainerAction},
 * {@code PacketContainerCustomAction}, {@code PacketContainerEvent} and {@code PacketShopContainerUpdate}, none
 * of which carries slot contents. Container slots are never synced as slots; the client simply reads the same
 * inventories, which reach it by other means. For an object entity that means
 * {@code OEInventory.serverTickInventorySync}, which sends {@code PacketOEInventoryUpdate} through
 * {@code sendToClientsWithEntity} — proximity, and so never a player on another level.
 *
 * <p>So the mirror below is genuinely new machinery rather than a wrapper over something existing. It is also
 * the reason this class exists instead of a second, thinner UI: mirroring lets the 1884-line terminal form,
 * every tab, the crafting preview and the aggregate grid all keep working with no idea they are looking at
 * something a thousand tiles away. A forked read-only remote UI would have been less code here and far more
 * everywhere else, and it would have drifted.
 *
 * <h2>Slot indices are the whole trick</h2>
 *
 * <p>The mirror never mentions units, tiles or inventories. It sends {@code (container slot index, item)}, and
 * the client writes that item into the slot with the same index. Indices already have to agree between the two
 * sides — the engine sends them when a player drags an item — and the terminal container already goes to some
 * trouble to make them agree (station sockets are registered before storage, in tile order, for exactly this
 * reason). Reusing that means the mirror cannot disagree with the container about what a slot is without the
 * container being broken for the local case too.
 */
public final class RemoteNetworkShape {

   /** The paired terminal. */
   public final RemoteBinding binding;

   /** The terminal's display name, which the player may have renamed. */
   public final GameMessage name;

   /** Socket counts, one per Station Unit, in the tile order that fixes their slot indices. */
   public final int[] socketCounts;

   /** Slot counts, one per Storage Unit, in the same tile order. */
   public final int[] unitSizes;

   /** Non-empty slots at the moment of opening, as {@code (container slot index, item)}. */
   public final List<SlotItem> contents;

   /**
    * The buses on the network at the moment it was opened.
    *
    * <p>Carried here as well as in {@link BusMirrorEvent} so the Logistics tab is right on the first frame
    * rather than one tick later, which is the same reason the upgrade panel's first state travels in its open
    * packet.
    */
   public final List<BusSummary> buses;

   /** One mirrored slot. */
   public static final class SlotItem {

      public final int index;

      public final InventoryItem item;

      public SlotItem(int index, InventoryItem item) {
         this.index = index;
         this.item = item;
      }
   }

   public RemoteNetworkShape(RemoteBinding binding, GameMessage name, int[] socketCounts, int[] unitSizes,
         List<SlotItem> contents, List<BusSummary> buses) {
      this.binding = binding;
      this.name = name;
      this.socketCounts = socketCounts;
      this.unitSizes = unitSizes;
      this.contents = contents;
      this.buses = buses;
   }

   public void writePacket(PacketWriter writer) {
      this.binding.writePacket(writer);

      // Sent as translated text rather than as a GameMessage. A terminal's name is either the object's own
      // localised name or a string the player typed, and the second cannot be a translation key -- so a
      // LocalMessage would be wrong half the time. The remote client shows what the owner sees.
      writer.putNextString(this.name == null ? "" : this.name.translate());

      writer.putNextShortUnsigned(this.socketCounts.length);
      for (int count : this.socketCounts) {
         writer.putNextShortUnsigned(count);
      }

      writer.putNextShortUnsigned(this.unitSizes.length);
      for (int size : this.unitSizes) {
         writer.putNextShortUnsigned(size);
      }

      writer.putNextShortUnsigned(this.contents.size());
      for (SlotItem entry : this.contents) {
         writer.putNextShortUnsigned(entry.index);
         writer.putNextContentPacket(InventoryItem.getContentPacket(entry.item));
      }

      writer.putNextShortUnsigned(this.buses.size());
      for (BusSummary summary : this.buses) {
         summary.writePacket(writer);
      }
   }

   public static RemoteNetworkShape fromPacket(PacketReader reader) {
      RemoteBinding binding = RemoteBinding.fromPacket(reader);
      String name = reader.getNextString();

      int[] socketCounts = new int[reader.getNextShortUnsigned()];
      for (int i = 0; i < socketCounts.length; i++) {
         socketCounts[i] = reader.getNextShortUnsigned();
      }

      int[] unitSizes = new int[reader.getNextShortUnsigned()];
      for (int i = 0; i < unitSizes.length; i++) {
         unitSizes[i] = reader.getNextShortUnsigned();
      }

      int entries = reader.getNextShortUnsigned();
      List<SlotItem> contents = new ArrayList<>(entries);
      for (int i = 0; i < entries; i++) {
         int index = reader.getNextShortUnsigned();
         contents.add(new SlotItem(index, InventoryItem.fromContentPacket(reader.getNextContentPacket())));
      }

      int busCount = reader.getNextShortUnsigned();
      List<BusSummary> buses = new ArrayList<>(busCount);
      for (int i = 0; i < busCount; i++) {
         buses.add(BusSummary.readPacket(reader));
      }

      return new RemoteNetworkShape(binding, new StaticMessage(name), socketCounts, unitSizes, contents, buses);
   }

   public Packet toPacket() {
      Packet packet = new Packet();
      this.writePacket(new PacketWriter(packet));
      return packet;
   }

   /** Client-side stand-ins for the Station Units, in the order their sockets are registered. */
   public List<NetworkStations> stationUnits() {
      List<NetworkStations> units = new ArrayList<>(this.socketCounts.length);
      for (int i = 0; i < this.socketCounts.length; i++) {
         units.add(new Mirrored(this.socketCounts[i], this.name, i));
      }

      return units;
   }

   /** Client-side stand-ins for the Storage Units. */
   public List<NetworkStorage> units() {
      List<NetworkStorage> units = new ArrayList<>(this.unitSizes.length);
      for (int i = 0; i < this.unitSizes.length; i++) {
         units.add(new Mirrored(this.unitSizes[i], this.name, i));
      }

      return units;
   }

   /**
    * A network member that exists only in a remote client's memory.
    *
    * <p>An {@link Inventory} of the right size and nothing else. It deliberately implements both member
    * interfaces, because the two differ only in which list they belong to and a second near-identical class
    * would be two places to keep a decision.
    *
    * <p>The two overridden defaults matter. {@code isOnNetwork()} derives from an object entity in the real
    * implementations, and here there is none — left inherited it would report every mirrored unit as gone, and
    * the container closes itself when a unit drops off the network, so the remote terminal would shut the
    * instant it opened. {@code tileOrder()} is likewise synthesised from the position in the sent list, which
    * is already the tile order the server enumerated.
    */
   private static final class Mirrored implements NetworkStorage, NetworkStations {

      private final Inventory inventory;

      private final GameMessage name;

      private final int order;

      private Mirrored(int size, GameMessage name, int order) {
         this.inventory = new Inventory(Math.max(size, 0));
         this.name = name;
         this.order = order;
      }

      @Override
      public Inventory getInventory() {
         return this.inventory;
      }

      @Override
      public GameMessage getInventoryName() {
         return this.name;
      }

      @Override
      public ObjectEntity getObjectEntity() {
         return null;
      }

      @Override
      public boolean isOnNetwork() {
         return true;
      }

      @Override
      public long tileOrder() {
         return this.order;
      }
   }
}
