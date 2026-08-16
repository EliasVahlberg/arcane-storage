package arcanestorage.container;

import java.util.HashMap;
import java.util.ArrayList;
import java.util.List;

import arcanestorage.objectentity.BusObjectEntity;
import arcanestorage.network.NetworkContents;
import arcanestorage.network.NetworkIndexes;
import arcanestorage.objectentity.BusSummary;
import arcanestorage.objectentity.StorageTerminalObjectEntity;
import arcanestorage.network.NetworkStorage;
import necesse.engine.localization.Localization;
import necesse.engine.network.NetworkClient;
import necesse.engine.network.Packet;
import necesse.engine.network.PacketReader;
import arcanestorage.remote.SlotMirrorEvent;
import necesse.engine.network.PacketWriter;
import necesse.engine.network.packet.PacketOpenContainer;
import necesse.engine.network.server.ServerClient;
import necesse.engine.registries.ContainerRegistry;
import necesse.entity.mobs.PlayerMob;
import necesse.inventory.InventoryRange;
import necesse.inventory.Inventory;
import necesse.inventory.Inventory;
import necesse.inventory.InventoryItem;
import necesse.inventory.item.ItemCategory;
import necesse.inventory.itemFilter.ItemCategoriesFilter;
import necesse.inventory.recipe.Recipe;
import necesse.inventory.recipe.Recipes;
import necesse.inventory.recipe.Tech;
import arcanestorage.network.NetworkStations;
import necesse.engine.registries.RecipeTechRegistry;
import java.util.Collection;
import java.util.LinkedHashSet;
import necesse.engine.localization.message.GameMessage;
import necesse.level.gameObject.container.CraftingStationObject;
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
   /**
    * First and last slot holding an installed crafting station, or -1 when the network has no Station
    * Unit.
    *
    * <p>No longer "always present, always ten wide": sockets are placed by the player now, so a network
    * can legitimately offer none. Anything reading these must check for -1 rather than assuming a range,
    * which is the same discipline {@code NETWORK_START} already needed.
    */
   public final int STATION_START;

   public final int STATION_END;

   /** The Station Units these slots belong to, in the tile order that fixes their indices. */
   public final java.util.List<NetworkStations> stationUnits;

   public int NETWORK_START = -1;
   /** Last container index belonging to a linked unit, or -1 when nothing is linked. */
   public int NETWORK_END = -1;

   public final StorageTerminalObjectEntity terminal;

   /**
    * The paired terminal's name as the server sent it, or null for a local terminal.
    *
    * <p>Only ever set on the client half of a remote container. The server half has the real object entity and
    * asks it, so there is exactly one source of this string per side and no chance of the two disagreeing.
    */
   private final GameMessage remoteName;

   /** Client-requested withdrawal, validated and executed server-side. */
   /** Purpose recorded on deposits, kept distinct from aggregation reads. */
   public static final String DEPOSIT_PURPOSE = "arcanestoragedeposit";

   public final StorageTerminalContainer.WithdrawAction withdrawAction;
   public final StorageTerminalContainer.DepositAllAction depositAllAction;
   public final StorageTerminalContainer.DepositCursorAction depositCursorAction;

   public final StorageTerminalContainer.RequestRulesAction requestRulesAction;

   public final StorageTerminalContainer.SendRulesAction sendRulesAction;

   public final StorageTerminalContainer.SetRulesAction setRulesAction;

   public final StorageTerminalContainer.RejectRulesAction rejectRulesAction;

   public final StorageTerminalContainer.SetNameAction setNameAction;

   /**
    * Rules fetched for the buses the player has looked at, keyed by tile. Client-side only.
    *
    * <p>Kept for the session the terminal is open. A player comparing two buses should not re-fetch each time
    * they switch between them, and a filter they have edited but not applied must survive looking away.
    */
   public final HashMap<Long, ItemCategoriesFilter> rules = new HashMap<>();

   /** Why the last attempt to write a bus's rules was refused, or null. Transient, per attempt, per player. */
   public String refusal;

   /**
    * The units backing {@link #NETWORK_START}..{@link #NETWORK_END}, in the same order the
    * slots were registered. Captured once here so the server resolves a slot index to the
    * same unit for as long as the container is open, even if a unit is broken meanwhile.
    */
   public final List<NetworkStorage> linkedUnits;

   private final LinkedHashSet<Inventory> craftPool;

   /** Every open terminal container, so an inventory change anywhere can find the ones that care. */
   private static final java.util.Set<StorageTerminalContainer> OPEN = new java.util.HashSet<>();

   /**
    * Slots the client asked to be told about, because it could not resolve the member holding them.
    *
    * <p>Empty for a network that does not cross a wireless link, which is the common case and pays nothing.
    */
   private final java.util.Set<Integer> mirrorSlots = new java.util.HashSet<>();

   /** Mirrored slots whose contents the client has not been told about yet. */
   private final java.util.Set<Integer> pending = new java.util.HashSet<>();

   /** What the client was last told each mirrored slot holds, indexed by container slot. Server-side only. */
   private final InventoryItem[] mirrored;

   /**
    * How many slots this container registered.
    *
    * <p>Counted by probing, because {@code Container} keeps its slot list private and exposes only
    * {@code getSlot(index)}, which returns null past the end. Probing once at construction is cheaper than tracking
    * every {@code addSlot} the constructor makes, and it cannot fall out of step with it.
    */
   protected final int slotCount;

   /** Asks the server to mirror the slots this client stood in for. */
   public final MirrorRequestAction mirrorRequestAction;

   public StorageTerminalContainer(NetworkClient client, int uniqueSeed, StorageTerminalObjectEntity terminal) {
      this(client, uniqueSeed, java.util.Objects.requireNonNull(terminal, "the local path always has a terminal"),
            terminal.getLinkedStationUnits(), terminal.getLinkedUnits(), null);
   }

   /**
    * The client's path for a locally-opened terminal: membership from the packet, not from its own walk.
    *
    * <p>The client still has the terminal object entity -- it is standing next to it -- so everything that reads the
    * terminal itself is unchanged. Only <i>who is on the network</i> now comes from the server, which is the one thing
    * a client cannot determine correctly once a Base Station is involved. Members it can resolve are used for real, so
    * a base with no wireless links behaves exactly as before; see {@link NetworkShape}.
    *
    * <p>A packet with nothing in it is tolerated rather than rejected: the container is also opened by the harness and
    * by any path that predates the shape, and falling back to the old behaviour is strictly better than refusing to
    * open a terminal.
    */
   public StorageTerminalContainer(NetworkClient client, int uniqueSeed, StorageTerminalObjectEntity terminal,
         Packet content) {
      this(client, uniqueSeed, terminal, shapeFrom(content));
   }

   /**
    * Membership from the shape when there is one, from the terminal's own walk when there is not.
    *
    * <p>{@code requireNonNull} rather than a side check: this path is only reached for a locally-opened terminal,
    * where both sides hold the entity, and the fallback below dereferences it. A remote client's half arrives through
    * the list-taking constructor instead, with no terminal at all.
    */
   private StorageTerminalContainer(NetworkClient client, int uniqueSeed, StorageTerminalObjectEntity terminal,
         NetworkShape shape) {
      this(client, uniqueSeed, java.util.Objects.requireNonNull(terminal, "the local path always has a terminal"),
            shape == null ? terminal.getLinkedStationUnits()
                  : shape.stationUnits(terminal.getLevel(), terminal.getInventoryName()),
            shape == null ? terminal.getLinkedUnits()
                  : shape.units(terminal.getLevel(), terminal.getInventoryName()),
            null);
   }

   /** The shape a local open packet carries, or null when it carries none. */
   private static NetworkShape shapeFrom(Packet content) {
      if (content == null || content.getSize() == 0) {
         return null;
      }

      try {
         return NetworkShape.fromPacket(new PacketReader(content));
      } catch (RuntimeException e) {
         // A malformed shape must not stop a terminal opening: the fallback is the walk this replaced, which is
         // correct for every network that does not cross a link.
         return null;
      }
   }

   /**
    * The membership-taking constructor, which is what lets a wireless terminal reuse all of this.
    *
    * <p>The lists are passed in rather than walked from the terminal, because a remote client cannot walk
    * anything: it holds only the level it stands on. On that path {@code terminal} is null and the lists are
    * the stand-ins from {@link arcanestorage.remote.RemoteNetworkShape}, sized from the open packet so that
    * every slot index means the same thing on both sides. Everything after this point -- slot registration,
    * the craft pool, the recipe list -- is then literally the same code for both cases, which is the point.
    *
    * <p>Note the terminal is still the real object entity on the <i>server</i> side of a remote container. Only
    * the client half is a stand-in, so nothing that moves items ever works on a mirror.
    */
   protected StorageTerminalContainer(NetworkClient client, int uniqueSeed, StorageTerminalObjectEntity terminal,
         List<NetworkStations> stationUnits, List<NetworkStorage> units, GameMessage remoteName) {
      super(client, uniqueSeed);
      this.terminal = terminal;
      this.remoteName = remoteName;
      if (terminal != null) {
         terminal.triggerInteracted();
      }

      if (client.isServer() && terminal != null) {
         terminal.startUser(client.playerMob);

         // Opening the terminal is both the moment a wrong count would be noticed and the moment somebody is
         // about to act on one, so every network here is asked to check itself. Vanilla shipped the same bug
         // in reverse -- its version history records fixing crafting lists that did not update when a nearby
         // inventory changed -- which is reason enough not to assume a cache of other people's containers is
         // always right.
         NetworkIndexes.reconcileSoon(terminal.getLevel());
      }

      // Station slots come first, and deliberately before the network, so their indices are the same on
      // both sides no matter what either side thinks the network contains. Slot indices *are* sent by
      // the client when it moves an item, and membership is discovered independently per side, so a
      // station slot placed after the network could resolve to a unit slot on the server if the two
      // disagreed about unit count.
      //
      // The sockets now live on Station Units rather than on the terminal, so their count is no longer
      // fixed -- a network with no Station Unit has none at all, which is a normal state. Both sides
      // enumerate the units in tile order, which is what makes an index mean the same thing to each of
      // them; see StorageTerminalObjectEntity.getLinkedStationUnits.
      this.stationUnits = stationUnits;

      int stationStart = -1;
      int stationEnd = -1;
      for (NetworkStations unit : this.stationUnits) {
         Inventory sockets = unit.getInventory();
         for (int i = 0; i < sockets.getSize(); i++) {
            int index = this.addSlot(new OEInventoryContainerSlot(unit, i));
            if (stationStart == -1) {
               stationStart = index;
            }

            stationEnd = index;
         }
      }

      this.STATION_START = stationStart;
      this.STATION_END = stationEnd;

      this.linkedUnits = units;

      for (NetworkStorage unit : this.linkedUnits) {
         for (int i = 0; i < unit.getInventory().getSize(); i++) {
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

      // Every recipe in the game is registered, not just the installed stations' recipes, because a
      // recipe ID is an index into this list and applyCraftingAction resolves it by index on both
      // sides. Registering the installed subset would move every index whenever a bench was
      // installed, and vanilla's own answer to that problem is to *close* the container
      // (CraftingStationContainer does exactly that after a station upgrade) -- which would throw
      // the player out of the interface for the crime of installing a bench.
      //
      // With the list fixed for the container's lifetime, installing a bench cannot desync
      // anything: what changes is which recipes the tab *shows*, and the server refuses the rest in
      // applyCraftingAction below. The list is never shown in full, so its size is not a UI concern.
      Recipes.streamRecipes().filter(recipe -> !recipe.matchTech(RecipeTechRegistry.NONE)).forEach(this::addRecipe);

      // Excludes the terminal's own inventory, so an installed bench is not an ingredient. Several
      // upgrade recipes take the base station as a material, and without this, crafting a Demonic
      // Workstation would quietly eat the Workstation installed in the terminal. Computed once:
      // getCraftInventories is called per recipe per craftability check, so it must not allocate.
      this.craftPool = new LinkedHashSet<>(this.craftInventories);
      if (terminal != null) {
         this.craftPool.remove(terminal.inventory);
      }

      this.withdrawAction = this.registerAction(new StorageTerminalContainer.WithdrawAction());
      this.depositAllAction = this.registerAction(new StorageTerminalContainer.DepositAllAction());
      this.depositCursorAction = this.registerAction(new StorageTerminalContainer.DepositCursorAction());
      this.requestRulesAction = this.registerAction(new StorageTerminalContainer.RequestRulesAction());
      this.sendRulesAction = this.registerAction(new StorageTerminalContainer.SendRulesAction());
      this.setRulesAction = this.registerAction(new StorageTerminalContainer.SetRulesAction());
      this.rejectRulesAction = this.registerAction(new StorageTerminalContainer.RejectRulesAction());
      this.setNameAction = this.registerAction(new StorageTerminalContainer.SetNameAction());
      this.mirrorRequestAction = this.registerAction(new StorageTerminalContainer.MirrorRequestAction());

      int count = 0;
      while (this.getSlot(count) != null) {
         count++;
      }

      this.slotCount = count;
      this.mirrored = new InventoryItem[count];

      if (this.client.isServer()) {
         OPEN.add(this);
      } else {
         this.subscribeEvent(SlotMirrorEvent.class, event -> true, () -> true);
         this.onEvent(SlotMirrorEvent.class, this::applyMirror);
         this.requestMirroring();
      }
   }

   /**
    * Tells the server which slots this client cannot fill for itself.
    *
    * <p>Identified by inventory identity rather than by recomputing index arithmetic from the sizes: the slots were
    * registered from these very inventories a few lines ago, so identity is exact, while a second derivation of
    * "station sockets, then units, in order" would be a copy of the rule that could drift from it.
    */
   private void requestMirroring() {
      java.util.Set<Inventory> standIns = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
      for (NetworkStations unit : this.stationUnits) {
         if (unit instanceof MirroredMember) {
            standIns.add(unit.getInventory());
         }
      }

      for (NetworkStorage unit : this.linkedUnits) {
         if (unit instanceof MirroredMember) {
            standIns.add(unit.getInventory());
         }
      }

      if (standIns.isEmpty()) {
         return;
      }

      List<Integer> indices = new ArrayList<>();
      for (int i = 0; i < this.slotCount; i++) {
         ContainerSlot slot = this.getSlot(i);
         if (slot != null && standIns.contains(slot.getInventory())) {
            indices.add(i);
         }
      }

      if (!indices.isEmpty()) {
         this.mirrorRequestAction.runAndSend(indices);
      }
   }

   /** Writes mirrored slots the server pushed. */
   private void applyMirror(SlotMirrorEvent event) {
      for (int i = 0; i < event.indices.length; i++) {
         int index = event.indices[i];
         if (index < 0 || index >= this.slotCount) {
            continue;
         }

         ContainerSlot slot = this.getSlot(index);
         if (slot != null) {
            slot.setItem(event.items[i]);
         }
      }
   }

   /**
    * The bus at these coordinates, if it is on this terminal's network. Null otherwise, which is a refusal.
    *
    * <p>Every rule action checks this. The tab addresses buses by coordinate, so without the check a crafted
    * packet could rewrite a bus anywhere on the level.
    */
   private BusObjectEntity busFor(int x, int y) {
      return this.terminal == null ? null : this.terminal.busOnNetwork(x, y);
   }

   /** Asks the server for one bus's rules, which are not in the terminal's own sync. */
   public class RequestRulesAction extends ContainerCustomAction {

      public void runAndSend(int x, int y) {
         Packet content = new Packet();
         PacketWriter writer = new PacketWriter(content);
         writer.putNextInt(x);
         writer.putNextInt(y);
         this.runAndSendAction(content);
      }

      @Override
      public void executePacket(PacketReader reader) {
         if (!StorageTerminalContainer.this.client.isServer()) {
            return;
         }

         int x = reader.getNextInt();
         int y = reader.getNextInt();
         BusObjectEntity bus = StorageTerminalContainer.this.busFor(x, y);
         if (bus != null) {
            StorageTerminalContainer.this.sendRulesAction.runAndSend(x, y, bus.filter);
         }
      }
   }

   /**
    * The server's answer: one bus's rules, addressed by tile.
    *
    * <p>Fetched on request rather than sent with the terminal's summary of the network. A filter is a category
    * tree with per-item entries and is not small, bus counts are not bounded, and a player opening the terminal
    * to look at storage would pay for every one of them. This way they pay for the bus they clicked.
    */
   public class SendRulesAction extends ContainerCustomAction {

      public void runAndSend(int x, int y, ItemCategoriesFilter filter) {
         Packet content = new Packet();
         PacketWriter writer = new PacketWriter(content);
         writer.putNextInt(x);
         writer.putNextInt(y);
         filter.writePacket(writer);
         this.runAndSendAction(content);
      }

      @Override
      public void executePacket(PacketReader reader) {
         if (StorageTerminalContainer.this.client.isServer()) {
            return;
         }

         int x = reader.getNextInt();
         int y = reader.getNextInt();
         ItemCategoriesFilter filter = new ItemCategoriesFilter(ItemCategory.masterCategory, false);
         filter.readPacket(reader);
         StorageTerminalContainer.this.rules.put(key(x, y), filter);
      }
   }

   /**
    * Writes one bus's rules, through the same validation the bus's own panel obeys.
    *
    * <p>The point of routing this through {@link BusObjectEntity#whyRefused} rather than writing the filter
    * directly is that there must be no way to reach a contradictory configuration by choosing the more
    * convenient of two interfaces. A rule set is adopted or refused as one thing here too, and a refusal
    * applies none of it.
    */
   /**
    * Renames a bus from the terminal.
    *
    * <p>Goes through the same membership check as a rule change: the tab addresses devices by coordinate, so
    * without it a crafted packet could rename a bus anywhere on the level. A name is only a label, so this is
    * the cheapest possible thing to abuse -- which is exactly why it should not be the one route that skips
    * the check.
    */
   public class SetNameAction extends ContainerCustomAction {

      public void runAndSend(int x, int y, String name) {
         Packet content = new Packet();
         PacketWriter writer = new PacketWriter(content);
         writer.putNextInt(x);
         writer.putNextInt(y);
         writer.putNextString(name);
         this.runAndSendAction(content);
      }

      @Override
      public void executePacket(PacketReader reader) {
         if (!StorageTerminalContainer.this.client.isServer()) {
            return;
         }

         int x = reader.getNextInt();
         int y = reader.getNextInt();
         String name = reader.getNextString();
         BusObjectEntity bus = StorageTerminalContainer.this.busFor(x, y);
         if (bus != null) {
            bus.setCustomName(name);
         }
      }
   }

   public class SetRulesAction extends ContainerCustomAction {

      public void runAndSend(int x, int y, ItemCategoriesFilter edited) {
         Packet content = new Packet();
         PacketWriter writer = new PacketWriter(content);
         writer.putNextInt(x);
         writer.putNextInt(y);
         edited.writePacket(writer);
         this.runAndSendAction(content);
      }

      @Override
      public void executePacket(PacketReader reader) {
         if (!StorageTerminalContainer.this.client.isServer()) {
            return;
         }

         int x = reader.getNextInt();
         int y = reader.getNextInt();
         ItemCategoriesFilter proposed = new ItemCategoriesFilter(ItemCategory.masterCategory, false);
         proposed.readPacket(reader);

         BusObjectEntity bus = StorageTerminalContainer.this.busFor(x, y);
         if (bus == null) {
            StorageTerminalContainer.this.rejectRulesAction.runAndSend(
                  Localization.translate("ui", "arcanestorage_rules_gone"));
            return;
         }

         String refusal = bus.whyRefused(proposed);
         if (refusal != null) {
            StorageTerminalContainer.this.rejectRulesAction.runAndSend(refusal);
            return;
         }

         Packet accepted = new Packet();
         proposed.writePacket(new PacketWriter(accepted));
         bus.filter.readPacket(new PacketReader(accepted));

         // Or a rule the player just set would wait for some unrelated change to disturb the same item before
         // anything happened. Nothing polls any more, so nothing would notice.
         bus.rulesChanged();
      }
   }

   /** Why the last write was refused, back to the client that tried. See BusContainer.RejectFilterAction. */
   public class RejectRulesAction extends ContainerCustomAction {

      public void runAndSend(String reason) {
         Packet content = new Packet();
         new PacketWriter(content).putNextString(reason);
         this.runAndSendAction(content);
      }

      @Override
      public void executePacket(PacketReader reader) {
         String reason = reader.getNextString();
         if (!StorageTerminalContainer.this.client.isServer()) {
            StorageTerminalContainer.this.refusal = reason;
         }
      }
   }

   /** One key for a tile, so fetched rules can be held in a map without allocating a point per lookup. */
   public static long key(int x, int y) {
      return (long)x << 32 | (long)y & 4294967295L;
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
      return NetworkContents.canFit(this.level(), this.linkedUnits, item, AGGREGATE_PURPOSE);
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

      for (NetworkStorage unit : this.linkedUnits) {
         targets.add(new InventoryRange(unit.getInventory()));
      }

      return targets;
   }

   /**
    * The network and the player, never the station slots.
    *
    * <p>{@code Container.addSlot} adds every slot's inventory to the crafting pool, which is how the
    * network became a crafting source for free -- but it would also make an installed bench an
    * ingredient.
    */
   @Override
   public Collection<Inventory> getCraftInventories() {
      return this.craftPool;
   }

   /**
    * Refuses recipes whose station is not installed.
    *
    * <p>This is the gate that makes registering every recipe safe. The client only ever shows
    * recipes it believes are available, so a request for anything else is either a stale view or a
    * crafted packet; both are refused the same way, and the check runs against the server's own
    * copy of the station slots rather than anything the client sent.
    */
   @Override
   public int applyCraftingAction(int recipeID, int recipeHash, int craftAmount, boolean transferToInventory) {
      Recipe recipe = this.getRecipe(recipeID);
      if (recipe != null && !this.isRecipeAvailable(recipe)) {
         return 0;
      }

      return super.applyCraftingAction(recipeID, recipeHash, craftAmount, transferToInventory);
   }

   /**
    * Whether the terminal can currently build this recipe's kind at all -- a question about
    * stations, not about materials.
    *
    * <p>Hand recipes always qualify: a recipe needing no station needs no permission, and letting
    * them through is also what keeps the crafting tab from being empty before the first bench is
    * installed.
    */
   public boolean isRecipeAvailable(Recipe recipe) {
      if (recipe.matchTech(RecipeTechRegistry.NONE)) {
         return true;
      }

      for (Tech tech : this.getInstalledTechs()) {
         if (recipe.matchTech(tech)) {
            return true;
         }
      }

      return false;
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
         this.quickStackToInventories(this.networkTargets(), this.playerSource());
         return new ContainerActionResult(1);
      }

      if (slotIndex == RESTOCK_SLOT) {
         // Deliberately the whole inventory, hotbar included, even when the hotbar is locked. Restock cannot start a
         // new stack anywhere -- Inventory.restockFrom only combines with items already present -- so the most it can
         // do to a locked hotbar is top up a stack the player put there themselves, which is precisely what vanilla
         // permits: PlayerInventoryManager.addItem falls back to addItemOnlyCombine over slots 0-9 rather than
         // refusing outright. Narrowing this would break refilling arrows and potions, the reason the button exists.
         this.restockFromInventories(this.networkTargets(), this.client.playerMob.getInv().main);
         return new ContainerActionResult(1);
      }

      return super.applyContainerAction(slotIndex, action);
   }

   /** Slots 0-9, the hotbar, as {@code PlayerInventoryManager} itself hardcodes them. */
   private static final int HOTBAR_END = 9;

   /**
    * The player slots a terminal button may take items <i>out of</i>.
    *
    * <p>A locked hotbar is off limits. That is a decision rather than a rule inherited from the engine, and it is
    * worth being clear about which: vanilla enforces {@code hotbarLocked} only on the way in, and its own chest
    * quick-stack will empty a locked hotbar without hesitating, consulting only the per-slot pins.
    *
    * <p>The reason to diverge is what the flag means to the player. Locking the hotbar is how someone says "this
    * arrangement is deliberate, stop moving things into it" -- and a button that empties all ten slots in one click
    * is a far larger violation of that than the pickup it does block. Deposit-all is this mod's own button, so no
    * habit learned from vanilla chests is being broken here.
    *
    * <p>Per-slot pins are handled separately, by the callers, because vanilla's helpers already check them and doing
    * it twice would be the kind of duplicate rule that drifts.
    */
   private InventoryRange playerSource() {
      Inventory main = this.client.playerMob.getInv().main;
      return this.client.playerMob.hotbarLocked
         ? new InventoryRange(main, HOTBAR_END + 1, main.getSize() - 1)
         : new InventoryRange(main);
   }

   /**
    * Moves everything the player is carrying into the network, and reports how many items moved.
    *
    * <p>Distinct from quick-stack, which only tops up items the network already holds:
    * {@code quickStackToInventories} skips a target unless it already contains that item. That
    * is the right behaviour for a button called quick-stack and the wrong behaviour for
    * "empty my bag after a mining trip", which is the actual returning-player action.
    *
    * <p>Locked slots are left alone, in both senses the game gives that word: a slot the player pinned
    * individually, and the whole hotbar when they locked it. So the loadout someone deliberately arranged
    * survives the click. Anything that will not fit stays with the player rather than being destroyed.
    */
   public int depositAll() {
      Level level = this.level();
      PlayerMob player = this.client.playerMob;
      Inventory inventory = player.getInv().main;
      InventoryRange source = this.playerSource();
      ArrayList<InventoryRange> targets = this.networkTargets();
      int moved = 0;

      for (int slot = source.startSlot; slot <= source.endSlot; slot++) {
         if (inventory.isSlotClear(slot) || inventory.isItemLocked(slot)) {
            continue;
         }

         if (!this.depositable(inventory.getItem(slot))) {
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

   /**
    * Whether deposit-all may take an item. Everything, here; a wireless terminal overrides it.
    *
    * <p>The case it exists for: deposit-all through a wireless terminal used to file <b>the terminal itself</b>
    * away, and the container closes the moment the player stops holding it -- so one click stored the only way back
    * to the network, in the network, on a level the player was not on. A test caught it, but only because the test
    * happened to deposit before withdrawing.
    *
    * <p>Deliberately not applied to a deliberate drag into a slot. Losing a key by clicking "deposit all" is an
    * accident worth preventing; dragging it in is a decision, and refusing decisions silently is its own confusion.
    */
   protected boolean depositable(InventoryItem item) {
      return true;
   }

   /**
    * The level whose items these are, or the viewer's own level when this is a remote client.
    *
    * <p>Reached from the both-sides bodies of {@code depositAll}, {@code WithdrawAction} and
    * {@code DepositCursorAction} as well, which is why it is not merely a getter. Those three run on the clicking
    * client too, by design, so the player sees the result before the server's reply -- and on a remote client
    * {@code terminal} is null, so {@code terminal.getLevel()} threw the moment an item was withdrawn through a
    * wireless terminal. That was the third time a body running on both sides was written as though it ran on one.
    *
    * <p>Used only where a level is a parameter to an item comparison. {@code InventoryItem.equals} takes one to
    * let items answer questions about the world they are in, and no item in the game answers differently on one
    * level than another -- so the viewer's level is a safe stand-in on the one path where the real one is out of
    * reach. Anything that <i>moves</i> items runs server-side, where the real level is always present.
    */
   protected Level level() {
      if (this.terminal != null) {
         return this.terminal.getLevel();
      }

      return this.client.playerMob == null ? null : this.client.playerMob.getLevel();
   }

   /** The paired terminal's name, whichever side is asking. */
   public GameMessage getTerminalName() {
      return this.terminal != null ? this.terminal.getInventoryName() : this.remoteName;
   }

   /**
    * The crafting stations installed on this network.
    *
    * <p>Computed from the Station Units held by this container rather than asked of the terminal, which is what
    * makes it work remotely for free: the mirrored sockets hold the same bench items, and which recipes a bench
    * unlocks is a property of the item. The terminal's own version of this method does the same walk.
    */
   public LinkedHashSet<Tech> getInstalledTechs() {
      LinkedHashSet<Tech> techs = new LinkedHashSet<>();
      for (NetworkStations unit : this.stationUnits) {
         Inventory sockets = unit.getInventory();
         for (int slot = 0; slot < sockets.getSize(); slot++) {
            CraftingStationObject station = StorageTerminalObjectEntity.getCraftingStation(sockets.getItem(slot));
            if (station != null) {
               techs.addAll(java.util.Arrays.asList(station.getCraftingTechs()));
            }
         }
      }

      return techs;
   }

   /** The network's buses, for the Logistics tab. Empty on a remote client until the first push arrives. */
   public List<BusSummary> getBuses() {
      return this.terminal == null ? new ArrayList<>() : this.terminal.getBuses();
   }

   public List<InventoryItem> getAggregatedItems() {
      if (this.isNetworkEmpty()) {
         return new ArrayList<>();
      }

      return NetworkContents.aggregate(this.level(), this.linkedUnits, AGGREGATE_PURPOSE);
   }

   @Override
   public void tick() {
      super.tick();
      if (this.client.isServer() && this.terminal != null) {
         this.terminal.startUser(this.client.playerMob);
      }

      if (this.client.isServer()) {
         this.sendPending();
      }
   }

   @Override
   public void onClose() {
      super.onClose();
      OPEN.remove(this);
      if (this.client.isServer() && this.terminal != null) {
         this.terminal.stopUser(this.client.playerMob);
      }
   }

   /**
    * Pushes the slots this client cannot see for itself, and only those.
    *
    * <p>Compared against what was last sent rather than sent blindly, because the change hook is deliberately
    * imprecise -- it fires for any inventory the network touches -- and an unchanged slot costs a packet for nothing.
    * That is also what makes a re-marked slot idempotent.
    */
   private void sendPending() {
      if (this.pending.isEmpty()) {
         return;
      }

      SlotMirrorEvent.Batch batch = new SlotMirrorEvent.Batch();
      for (int index : this.pending) {
         if (index < 0 || index >= this.slotCount) {
            continue;
         }

         ContainerSlot slot = this.getSlot(index);
         InventoryItem current = slot == null ? null : slot.getItem();
         if (!sameItem(current, this.mirrored[index])) {
            this.mirrored[index] = current == null ? null : current.copy();
            batch.add(index, current);
         }
      }

      this.pending.clear();
      if (!batch.isEmpty()) {
         batch.toEvent().applyAndSendToClient(this.client.getServerClient());
      }
   }

   /**
    * Whether a slot still looks the way the client was told.
    *
    * <p>Amount is compared as well as identity, which {@code InventoryItem.equals} deliberately does not do -- it
    * exists to answer "are these the same kind of thing", and a stack growing from 3 to 4 is exactly the change a
    * storage UI must show.
    */
   private static boolean sameItem(InventoryItem a, InventoryItem b) {
      if (a == null || b == null) {
         return a == b;
      }

      return a.item.getID() == b.item.getID() && a.getAmount() == b.getAmount()
            && a.getGndData().equals(b.getGndData());
   }

   /**
    * Marks a slot for resending. Called from the mod's inventory-change hook; must stay cheap.
    *
    * <p>Walks only the slots somebody is actually mirroring rather than every slot of every open container, so a
    * network with nothing mirrored -- every base without a wireless link -- costs one empty-set check per change.
    */
   public static void inventoryChanged(Inventory inventory) {
      if (OPEN.isEmpty()) {
         return;
      }

      for (StorageTerminalContainer container : OPEN) {
         for (int index : container.mirrorSlots) {
            ContainerSlot slot = container.getSlot(index);
            if (slot != null && slot.getInventory() == inventory) {
               container.pending.add(index);
            }
         }
      }
   }

   /** Forgets every open container, for the harness between scenarios. */
   public static void forgetOpen() {
      OPEN.clear();
   }

   /**
    * What the client asks for once, on open: the slots it had to stand in for.
    *
    * <p><b>Asked rather than inferred, because inference here cannot be made safe.</b> The server could try to work
    * out what the client can see -- it knows which regions that client has loaded -- but the two answers are derived
    * from different mutable state on different machines, and every way they can disagree is a slot that either nobody
    * updates (items invisible, the bug this fixes) or everybody updates twice. The client is the only party that knows
    * what it actually built, so it says, and the server believes it. The set is empty for any network that does not
    * cross a link, which is why this costs nothing in the common case.
    */
   public class MirrorRequestAction extends ContainerCustomAction {

      @Override
      public void executePacket(PacketReader reader) {
         if (!StorageTerminalContainer.this.client.isServer()) {
            return;
         }

         int count = reader.getNextShortUnsigned();
         for (int i = 0; i < count; i++) {
            int index = reader.getNextShortUnsigned();
            if (index >= 0 && index < StorageTerminalContainer.this.slotCount) {
               StorageTerminalContainer.this.mirrorSlots.add(index);
               StorageTerminalContainer.this.pending.add(index);
            }
         }
      }

      public void runAndSend(List<Integer> indices) {
         Packet content = new Packet();
         PacketWriter writer = new PacketWriter(content);
         writer.putNextShortUnsigned(indices.size());
         for (int index : indices) {
            writer.putNextShortUnsigned(index);
         }

         this.runAndSendAction(content);
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

      for (NetworkStorage unit : this.linkedUnits) {
         if (!unit.isOnNetwork()) {
            return false;
         }
      }

      return this.isWithinReach(client);
   }

   /**
    * Whether the player is close enough, which is the one validity rule a wireless terminal replaces.
    *
    * <p>Split out rather than branched inside {@link #isValid} so that the checks above it -- the terminal still
    * existing, every unit still on the network -- cannot be skipped by accident on the remote path. Those are
    * the ones that prevent writing into a detached inventory, and they are not negotiable for either case.
    */
   protected boolean isWithinReach(ServerClient client) {
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
   /**
    * The terminal at a tile, or null.
    *
    * <p>Resolved through the object entity rather than trusting the caller, because the open path is reached from an
    * object's {@code interact} and the tile is the only thing that path is sure of.
    */
   private static StorageTerminalObjectEntity terminalAt(Level level, int tileX, int tileY) {
      necesse.entity.objectEntity.ObjectEntity entity = level.entityManager.getObjectEntity(tileX, tileY);
      return entity instanceof StorageTerminalObjectEntity ? (StorageTerminalObjectEntity)entity : null;
   }

   public static void openAndSendContainer(int containerID, ServerClient client, Level level, int tileX, int tileY, Packet extraContent) {
      if (!level.isServer()) {
         throw new IllegalStateException("Level must be a server level");
      }

      Packet packet = new Packet();
      PacketWriter writer = new PacketWriter(packet);

      // The membership the client is to use, rather than leaving it to work the same thing out. See NetworkShape for
      // why it cannot: a network reaching through a Base Station extends past the regions the client has been sent,
      // so its own walk produced a shorter list, and every slot index after the first missing unit meant something
      // different on each side.
      StorageTerminalObjectEntity terminal = terminalAt(level, tileX, tileY);
      NetworkShape shape = terminal == null
            ? new NetworkShape(new long[0], new int[0], new long[0], new int[0])
            : NetworkShape.of(terminal.getLinkedStationUnits(), terminal.getLinkedUnits());
      shape.writePacket(writer);

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

         Level level = StorageTerminalContainer.this.level();
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
         Level level = StorageTerminalContainer.this.level();
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
