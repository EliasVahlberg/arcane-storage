package arcanestorage.objectentity;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import arcanestorage.network.DeviceOnNetwork;
import arcanestorage.network.IndexedInventories;
import arcanestorage.network.NetworkConductor;
import arcanestorage.network.NetworkIndex;
import arcanestorage.network.NetworkIndexes;
import arcanestorage.network.NetworkScheduler;
import arcanestorage.network.NetworkStorage;
import arcanestorage.network.UnitNetwork;
import necesse.engine.localization.Localization;
import necesse.engine.network.PacketReader;
import necesse.engine.network.PacketWriter;
import necesse.engine.network.server.ServerClient;
import necesse.engine.registries.ItemRegistry;
import necesse.engine.save.LoadData;
import necesse.engine.save.SaveData;
import necesse.entity.objectEntity.ObjectEntity;
import necesse.entity.objectEntity.interfaces.OEInventory;
import necesse.inventory.Inventory;
import necesse.inventory.InventoryItem;
import necesse.inventory.item.Item;
import necesse.inventory.item.ItemCategory;
import necesse.inventory.itemFilter.ItemCategoriesFilter;
import necesse.level.maps.Level;

/**
 * Shared behaviour of the import and export buses: find a network, find a container, move items.
 *
 * <p><b>A bus is an entry point, exactly like a terminal.</b> It walks the network from its own tile
 * with {@link UnitNetwork}, so it needed no new idea of membership. It is also a {@link NetworkConductor},
 * because the first version was not and that silently severed a run of units wherever a bus was placed.
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
public abstract class BusObjectEntity extends ObjectEntity implements DeviceOnNetwork {

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

   /** Returned by {@link #networkShouldHold} when the player has set no number for an item. */
   protected static final int NO_TARGET = -1;

   /**
    * What may cross, and how much of it, in <b>the game's own filter type</b>.
    *
    * <p>This started life as a hand-written rule primitive of three fields. That was a mistake found by
    * looking for the wrong word: searching for "rule" and "threshold" finds nothing, and the game's word
    * is <i>filter</i>. {@code ItemCategoriesFilter} already carries per-item and per-category limits, four
    * limit modes, tri-state category inheritance, save data, {@code writePacket}/{@code readPacket},
    * {@code copy()} and equality — and {@code ItemCategoriesFilterForm} is an editor for it that the
    * player has already learned, because it is the panel behind "configure storage" on a settlement chest.
    *
    * <p>The rule a player reads off that panel is one sentence in both directions: <b>a ticked item moves,
    * and a number is how much of it the network should end up holding.</b> An import bus fills up to that
    * number, an export bus drains down to it, and no number means move as much as possible.
    *
    * <p>Public and mutable because the form edits it in place, exactly as the settlement one does.
    */
   public final ItemCategoriesFilter filter;


   /**
    * Whether this bus is working, and why not when it is not.
    *
    * <p>Derived and never saved. Synced to clients through {@link #setupContentPacket}, because the sprite
    * that shows it is drawn client-side.
    */
   private DeviceState state = DeviceState.ACTIVE;

   /**
    * This bus's number among its own kind on its network, or 0 before it has been given one.
    *
    * <p>Coordinates are what a device really is addressed by, and they are useless to a player: nothing in
    * the game shows a tile position, so "the bus at 1786,1912" identifies a device only to somebody willing
    * to go and stand on it. A number does the job that coordinates cannot.
    *
    * <p>Assigned once, from the network, and then saved -- so breaking bus #2 of three leaves #1 and #3
    * rather than renumbering the survivors under a player who had learnt which was which. The next bus
    * placed takes one above the highest in use, not one above the count, or it would collide with #3.
    *
    * <p>Purely a label. Nothing reads it to decide anything.
    */
   private int ordinal;

   /** What the player called this bus, or null to use the assigned name. */
   private String customName;

   /** The item a conflict was found on, for the explanation. Null unless {@link DeviceState#RULE_CONFLICT}. */
   private String conflictItemID;

   /** Where the device this bus conflicts with stands, so the explanation can point at it. */
   private int conflictX;

   private int conflictY;

   /**
    * The index this bus last used, kept so it can be reused without walking.
    *
    * <p>A reference rather than a copy, so that when another device on the same network rebuilds it, this bus
    * follows along for free. Never saved: it is derived state about the world, and the world is what is saved.
    */
   private NetworkIndex cachedNetwork;

   /** When this bus last looked for a network, and under which layout, so a fruitless search is not repeated. */
   private long lastSearched = Long.MIN_VALUE / 2;

   private long searchedUnderTopology = -1;

   /**
    * Counters for diagnosis, not for behaviour: how much work the buses are doing.
    *
    * <p>They exist because "it locks up" is not a testable claim and "the move count is still climbing after
    * the network stopped changing" is. Static because a test process runs one server, and reset between
    * scenarios by the harness.
    */
   public static long moves;

   public static long transfers;

   public static long slotsScanned;

   public static long networkWalks;

   public static long walkedNoCache;

   public static long walkedStale;

   public static long walkedNotMember;

   /**
    * @param allowAllByDefault whether an unconfigured bus moves everything. True for an import bus, since
    *        importing only adds and "point it at a chest" is the whole feature; false for an export bus,
    *        because one that emptied a network the moment it was placed would be a trap. This is vanilla's
    *        own constructor flag, not an invention of ours.
    */
   protected BusObjectEntity(Level level, String stringID, int x, int y, boolean allowAllByDefault) {
      super(level, stringID, x, y);
      this.filter = new ItemCategoriesFilter(ItemCategory.masterCategory, allowAllByDefault);

      // A device coming into existence changes the shape of a network, and this catches every way that can
      // happen: placed by a player, created while a world loads, or put there by a test. The placement hook on
      // the object covers only the first of those.
      NetworkIndexes.topologyChanged();
   }

   /**
    * How much of an item may cross, given how much each side holds.
    *
    * <p>Ours rather than the filter's, for one concrete reason: {@code getAddAmount} and
    * {@code getRemoveAmount} take an {@code InventoryRange}, which is a range within <i>one</i>
    * inventory, and a network is many. Evaluating the filter per unit would silently turn "the network
    * keeps 200" into "each unit keeps 200". The numbers still come from the filter; only the arithmetic
    * across units is ours.
    */
   protected abstract int allowedToMove(Item item, int inSource, int inDestination, NetworkIndex network);

   /**
    * How much of an item the player has said the network should hold, or {@link #NO_TARGET}.
    *
    * <p>Reads the per-item limit first, then falls back to the filter-wide number when its mode is a
    * per-item one. The two whole-container modes are deliberately ignored: a network's total capacity is
    * how many units it has, and a bus is not the place to cap it.
    */
   /**
    * The most the network should hold of one item, given every limit the panel can express.
    *
    * <p>One number, read from both directions: an import bus fills the network to it, an export bus drains
    * the network to it. {@link #NO_TARGET} means nothing caps this item and the bus moves what it can.
    *
    * <p>Three kinds of limit are folded together and the tightest wins, in the spirit of
    * {@code ItemCategoriesFilter.getAddAmount} within a single inventory: the item's own limit, a limit on any
    * category above it (walking up parents, as vanilla does), and the panel-wide number, which for a bus is
    * always per item.
    *
    * <p>Two wrong versions preceded this one, both found in game. The first honoured only the two "each item"
    * modes, so since {@code TOTAL_ITEMS} is the panel's default, typing a number did nothing at all. The
    * second took the whole-container modes literally and capped the network's entire item count, which in any
    * real network leaves zero headroom -- an import bus stopped dead and an export bus treated everything as
    * surplus. A bus's number is per item; that is the only reading that means something for a network, and
    * the mode dropdown is therefore not offered.
    */
   public int networkShouldHold(Item item, NetworkIndex network) {
      return this.networkShouldHold(this.filter, item, network);
   }

   /** The same, reading a filter that need not be the one this bus is using -- see {@link #whyRefused}. */
   public int networkShouldHold(ItemCategoriesFilter rules, Item item, NetworkIndex network) {
      int ceiling = NO_TARGET;

      ItemCategoriesFilter.ItemLimits limits = rules.getItemLimits(item);
      if (limits != null && !limits.isDefault()) {
         ceiling = tighten(ceiling, limits.getMaxItems());
      }

      for (ItemCategoriesFilter.ItemCategoryFilter category = rules.getItemCategory(item);
            category != null;
            category = category.parent) {
         if (!category.isDefault()) {
            ceiling = tighten(ceiling, category.getMaxItems() - network.inCategoryExcept(category, item));
         }
      }

      if (rules.maxAmount != Integer.MAX_VALUE) {
         // Per item, whatever mode the filter carries. A bus's number cannot sensibly mean anything else:
         // read as a cap on the network's entire item count -- which is what TOTAL_ITEMS means, and it is
         // the mode the panel starts in -- a network holding more than the number leaves zero headroom, so
         // an import bus stops dead and an export bus sees the whole network as surplus. Both were observed
         // in game. The panel's mode dropdown is not offered for a bus for the same reason.
         boolean inStacks = rules.limitMode == ItemCategoriesFilter.ItemLimitMode.TOTAL_STACKS
               || rules.limitMode == ItemCategoriesFilter.ItemLimitMode.TOTAL_STACKS_EACH_ITEM;
         ceiling = tighten(ceiling, inStacks
               ? rules.maxAmount * item.getStackSize()
               : rules.maxAmount);
      }

      return ceiling;
   }

   /** The same question with the bus's live network, for diagnostics and the harness. */
   public int networkShouldHold(Item item) {
      NetworkIndex index = this.networkIndex();
      return index == null ? NO_TARGET : this.networkShouldHold(item, index);
   }

   /** Keeps the lowest ceiling seen, treating a cap already exceeded as zero rather than as a negative. */
   private static int tighten(int ceiling, int candidate) {
      int floored = Math.max(candidate, 0);
      return ceiling == NO_TARGET ? floored : Math.min(ceiling, floored);
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
   protected abstract List<Inventory> sources(NetworkIndex network, Inventory container);

   /** Where they go. The other side. */
   protected abstract List<Inventory> destinations(NetworkIndex network, Inventory container);

   @Override
   public void serverTick() {
      super.serverTick();
      if (!this.isServer()) {
         return;
      }

      Level level = this.getLevel();
      if (level == null) {
         return;
      }

      // A bus no longer decides anything on a timer. It contributes rules, executes what the network's
      // scheduler asks of it, and -- if it happens to be the lowest-ordered device on the network -- drives
      // that scheduler. Every bus still ticks, because that is how the engine reaches us, but a tick with
      // nothing dirty costs a few field reads.
      NetworkIndex index = this.networkIndex();
      if (index == null) {
         this.evaluate(this.attachedContainer(), null);
         return;
      }

      // The container is watched so that somebody filling it wakes the network. Re-registered on every tick
      // rather than once, because the chest a bus points at can be broken and replaced without anything
      // telling us, and the registration is a map put with an unchanged value in the common case.
      this.assignOrdinal(index);

      IndexedInventories.watch(this.attachedContainer(), index);

      NetworkIndexes.drive(level, index, this);
   }

   /**
    * Runs the network's scheduler if this bus leads it.
    *
    * <p>Called from the tick rather than being the tick, because leadership is a property of the network and
    * every device asks the same question of the same list.
    */
   @Override
   public long tileOrder() {
      return ((long)this.tileY << 32) | (this.tileX & 0xFFFFFFFFL);
   }

   @Override
   public boolean fillsNetwork() {
      return this.movesIntoNetwork();
   }

   @Override
   public boolean wants(Item item) {
      return item != null && this.filter.isItemAllowed(item);
   }

   @Override
   public int targetFor(Item item, NetworkIndex index) {
      return this.networkShouldHold(item, index);
   }

   @Override
   public Inventory container() {
      return this.attachedContainer();
   }

   @Override
   public void revalidate(NetworkIndex index) {
      this.evaluate(this.attachedContainer(), index);
   }

   @Override
   public void reportChurn(Item item) {
      this.setState(DeviceState.CHURN, item.getStringID(), this.tileX, this.tileY);
   }

   /**
    * Moves up to {@code amount} of one item in this bus's direction.
    *
    * <p>Bounded by {@link #MAX_PER_TRANSFER} per call so that one item cannot consume a whole network's budget
    * in a single move, and so a large transfer stays visible as it happens rather than teleporting.
    *
    * <p>The source side is scanned for the item, which is the one place this design still does more work than
    * it needs to: the index knows how many of a thing the network holds but not which unit holds them. A
    * location index -- what vanilla's settlement storage keeps, a record per stack -- would turn this into a
    * lookup, and is the obvious next improvement rather than something this step needs.
    */
   @Override
   public int moveItem(Level level, NetworkIndex index, Item item, int amount) {
      if (this.state.stopsWork() || amount <= 0) {
         return 0;
      }

      Inventory container = this.attachedContainer();
      if (container == null) {
         return 0;
      }

      List<Inventory> from = this.sources(index, container);
      List<Inventory> to = this.destinations(index, container);
      int wanted = Math.min(amount, MAX_PER_TRANSFER);
      int movedTotal = 0;

      for (Inventory fromInventory : from) {
         for (int slot = 0; slot < fromInventory.getSize() && movedTotal < wanted; slot++) {
            slotsScanned++;
            InventoryItem inSlot = fromInventory.getItem(slot);
            if (inSlot == null || inSlot.item != item) {
               continue;
            }

            int take = Math.min(wanted - movedTotal, inSlot.getAmount());
            movedTotal += move(level, fromInventory, to, inSlot, take);
         }
      }

      if (movedTotal > 0) {
         transfers++;
      }

      return movedTotal;
   }

   /**
    * Decides whether this bus should be working, and records why not when it should not.
    *
    * <p>Ordered by what a player is most likely to have got wrong: no container, then no network, then rules
    * that cannot be satisfied. The first two were previously invisible unless the panel was opened.
    *
    * <p><b>The cheap case stays cheap.</b> A cycle needs a bus of the opposite direction sharing this bus's
    * container, and that is answered from the walk already performed. Only when such a bus exists is the
    * per-item comparison done, so the overwhelmingly common layout costs one identity check per peer.
    */
   private DeviceState evaluate(Inventory container, NetworkIndex index) {
      if (container == null) {
         return this.setState(DeviceState.NO_CONTAINER, null, 0, 0);
      }

      if (index == null || index.units().isEmpty()) {
         return this.setState(DeviceState.NO_NETWORK, null, 0, 0);
      }

      // Before the rule comparison, because a stop already decided on the evidence of the work itself outranks
      // a fresh look at the rules -- which will say everything is fine, since the rules are fine. What is wrong
      // is outside them.
      Item churning = index.scheduler().stalledItemFor(this);
      if (churning != null) {
         return this.setState(DeviceState.CHURN, churning.getStringID(), this.tileX, this.tileY);
      }

      BusObjectEntity opposed = null;
      for (ObjectEntity peer : index.devices()) {
         if (peer != this
               && peer instanceof BusObjectEntity
               && ((BusObjectEntity)peer).movesIntoNetwork() != this.movesIntoNetwork()
               && ((BusObjectEntity)peer).attachedContainer() == container) {
            opposed = (BusObjectEntity)peer;
            break;
         }
      }

      if (opposed == null) {
         return this.setState(DeviceState.ACTIVE, null, 0, 0);
      }

      BusObjectEntity importer = this.movesIntoNetwork() ? this : opposed;
      BusObjectEntity exporter = this.movesIntoNetwork() ? opposed : this;
      String contested = firstUnsatisfiableItem(importer, exporter, index);

      return contested == null
         ? this.setState(DeviceState.ACTIVE, null, 0, 0)
         : this.setState(DeviceState.RULE_CONFLICT, contested, opposed.tileX, opposed.tileY);
   }

   /**
    * The first item, if any, whose rules on these two buses describe no state the network can rest in.
    *
    * <p>An import bus drives the count of an item <i>up</i> toward its ceiling C; an export bus drives it
    * <i>down</i> toward its floor F. So:
    *
    * <ul>
    *   <li>{@code C <= F} rests. The import bus fills to C and the export bus finds nothing above F.
    *   <li>{@code C > F} never rests: every value the one side reaches, the other undoes.
    * </ul>
    *
    * <p>An import bus with no number is unbounded, which is above every floor and therefore always a
    * conflict when the other bus exports the same item. An export bus with no number has a floor of zero,
    * which is what {@code allowedToMove} already does with {@link #NO_TARGET}.
    *
    * <p><b>Both buses must share a container for any of this to matter</b>, which the caller has already
    * established. Import from one chest with export to another is {@code C > F} and perfectly well behaved:
    * items flow from the first to the second until the first is empty, and that is a feature. Flagging it
    * would break a legitimate and useful layout, so the shared-container test is not an optimisation.
    */
   private static String firstUnsatisfiableItem(
      BusObjectEntity importer, BusObjectEntity exporter, NetworkIndex network
   ) {
      return firstUnsatisfiableItem(importer.filter, exporter.filter, importer, exporter, network);
   }

   /**
    * The same question with the filters given separately from the buses that hold them.
    *
    * <p>Which is what makes a proposal judgeable: the numbers come from a filter, and the filter need not be the
    * one the device is currently using.
    */
   private static String firstUnsatisfiableItem(
      ItemCategoriesFilter importRules, ItemCategoriesFilter exportRules,
      BusObjectEntity importer, BusObjectEntity exporter, NetworkIndex network
   ) {
      for (Item item : ItemRegistry.getItems()) {
         if (item == null
               || !importRules.isItemAllowed(item)
               || !exportRules.isItemAllowed(item)) {
            continue;
         }

         int ceiling = importer.networkShouldHold(importRules, item, network);
         int floor = exporter.networkShouldHold(exportRules, item, network);
         if (ceiling == NO_TARGET || ceiling > Math.max(floor, 0)) {
            return item.getStringID();
         }
      }

      return null;
   }

   /**
    * Records the state, and tells the player when it changes.
    *
    * <p>{@link #markDirty()} is the engine's own push: {@code EntityManager}'s server tick sends a
    * {@code PacketObjectEntity} for every dirty object entity and then clears the flag, so one call here
    * reaches every client that can see this tile without a packet of our own.
    *
    * <p>The chat line fires once per transition into a state, never per tick, and is an alert rather than
    * the record. A player who was elsewhere at the time will never see it, which is why the state is also
    * on the sprite, in the hover tip, in the panel, and at the terminal.
    */
   private DeviceState setState(DeviceState next, String itemID, int x, int y) {
      boolean changed = this.state != next
         || !java.util.Objects.equals(this.conflictItemID, itemID)
         || this.conflictX != x
         || this.conflictY != y;

      this.state = next;
      this.conflictItemID = itemID;
      this.conflictX = x;
      this.conflictY = y;

      if (changed) {
         this.markDirty();
         if (this.isServer() && !next.isActive()) {
            this.announce();
         }
      }

      return next;
   }

   /** Says what went wrong, to whoever is on this level to hear it. */
   private void announce() {
      Level level = this.getLevel();
      if (level == null || level.getServer() == null) {
         return;
      }

      String message = this.stateMessage();
      for (ServerClient client : level.getServer().getClients()) {
         if (client != null && client.getLevel() == level) {
            client.sendChatMessage(message);
         }
      }
   }

   /** What this bus is doing, or why it is not. Empty when it is simply working. */
   public String stateMessage() {
      return this.summary().message();
   }

   /**
    * Gives this bus its number, once, the first time it is on a network.
    *
    * <p>One above the highest in use among the same direction on this network, rather than one above how many
    * there are: names are saved and survivors are never renumbered, so counting would hand a new bus a number
    * somebody else is already using.
    *
    * <p>Deferred to the first tick with a network rather than done when the bus is placed, because a bus is
    * placed before it is connected to anything -- and the number is meant to be a position within a network,
    * so a bus with no network has nothing to be numbered against. It gets one when it joins.
    */
   private void assignOrdinal(NetworkIndex network) {
      if (this.ordinal != 0 || network == null) {
         return;
      }

      int highest = 0;
      for (ObjectEntity device : network.devices()) {
         if (device instanceof BusObjectEntity && device != this) {
            BusObjectEntity peer = (BusObjectEntity)device;
            if (peer.movesIntoNetwork() == this.movesIntoNetwork()) {
               highest = Math.max(highest, peer.ordinal);
            }
         }
      }

      this.ordinal = highest + 1;
      this.markDirty();
   }

   /**
    * What to call this bus: what the player named it, or the number it was given.
    *
    * <p>Assembled from parts rather than stored as text, so it is localized wherever it is read and a
    * dedicated server does not send its own language to a client.
    */
   public static String busName(boolean importing, int ordinal, String customName) {
      if (customName != null && !customName.isEmpty()) {
         return customName;
      }

      String objectKey = importing ? "arcanestorageimportbus" : "arcanestorageexportbus";
      if (ordinal <= 0) {
         return Localization.translate("object", objectKey);
      }

      return Localization.translate("ui",
            importing ? "arcanestorage_importbusname" : "arcanestorage_exportbusname",
            "n", String.valueOf(ordinal));
   }

   public String name() {
      return busName(this.movesIntoNetwork(), this.ordinal, this.customName);
   }

   public int getOrdinal() {
      return this.ordinal;
   }

   /** What the player called this bus, or an empty string when it is using its assigned name. */
   public String getCustomName() {
      return this.customName == null ? "" : this.customName;
   }

   /**
    * Renames this bus, or returns it to its assigned name when given nothing or that name back.
    *
    * <p>A label and nothing else: no rule reads it, so unlike a rule set there is nothing here that can be
    * refused, and it applies immediately rather than waiting for Apply.
    */
   public void setCustomName(String name) {
      // A name is read back out into labels and into the chat line announcing a stopped device, so it goes
      // through the same places the game's own coloured text does. Left as typed, a name containing the colour
      // marker would let one player recolour another's panel and chat, and a newline would put a second line
      // into a block whose height is reserved. Neither is dangerous; both are avoidable here rather than at
      // every point of use.
      String trimmed = name == null ? "" : name.replaceAll("[\\p{Cntrl}\u00A7]", "").trim();
      if (trimmed.length() > MAX_NAME_LENGTH) {
         trimmed = trimmed.substring(0, MAX_NAME_LENGTH);
      }

      // Typing the assigned name, or clearing the box, means "use the assigned name" rather than pinning a
      // copy of it -- otherwise a bus renumbered by nothing would keep a stale number as a custom name.
      String next = trimmed.isEmpty() || trimmed.equals(busName(this.movesIntoNetwork(), this.ordinal, null))
            ? null
            : trimmed;
      if (java.util.Objects.equals(this.customName, next)) {
         return;
      }

      this.customName = next;
      this.markDirty();
   }

   /** Long enough for "Grain Import" and short enough to fit a device row. */
   public static final int MAX_NAME_LENGTH = 24;

   /**
    * This bus as the terminal sees it.
    *
    * <p>Also how this bus words its own state, so the reason on the sprite's hover tip, the reason in this
    * bus's panel and the reason in the terminal's logistics tab are one piece of code rather than three that
    * agree today.
    */
   public BusSummary summary() {
      // The other side of a conflict is named, not just located, and its name is looked up here rather than
      // travelling in the packet: it is a bus on this level a tile or two away, so whoever can see this one
      // can almost certainly see that one too. When the lookup fails the message falls back to coordinates.
      BusObjectEntity other = null;
      if (this.state == DeviceState.RULE_CONFLICT) {
         Level level = this.getLevel();
         if (level != null) {
            ObjectEntity entity = level.entityManager.getObjectEntity(this.conflictX, this.conflictY);
            if (entity instanceof BusObjectEntity) {
               other = (BusObjectEntity)entity;
            }
         }
      }

      return new BusSummary(this.tileX, this.tileY, this.movesIntoNetwork(), this.state,
            this.conflictItemID, this.conflictX, this.conflictY, this.ordinal, this.getCustomName(),
            other == null ? 0 : other.ordinal, other == null ? "" : other.getCustomName());
   }

   public DeviceState getState() {
      return this.state;
   }

   public boolean isInactive() {
      return !this.state.isActive();
   }

   /**
    * Which way this bus moves items, which is what makes a cycle detectable.
    *
    * <p>A direction rather than a class check, so the predicate reads as the rule it enforces and a third
    * kind of device would only have to answer the same question.
    */
   public abstract boolean movesIntoNetwork();

   /**
    * Re-runs the evaluation and reports which branch it took. For diagnosis only.
    *
    * <p>Exists because the state alone cannot distinguish "no opposed bus was found" from "the numbers were
    * compatible", and those want opposite fixes.
    */
   public String describeEvaluation() {
      Inventory container = this.attachedContainer();
      if (container == null) {
         return "no container";
      }

      List<ObjectEntity> peers = new ArrayList<>();
      List<NetworkStorage> network = this.network(peers);
      if (network.isEmpty()) {
         return "no network";
      }

      StringBuilder out = new StringBuilder("peers=" + peers.size());
      for (ObjectEntity found : peers) {
         if (!(found instanceof BusObjectEntity)) {
            continue;
         }

         BusObjectEntity peer = (BusObjectEntity)found;
         out.append(" [").append(peer.tileX).append(',').append(peer.tileY)
            .append(" into=").append(peer.movesIntoNetwork())
            .append(" same=").append(peer == this)
            .append(" shares=").append(peer.attachedContainer() == container)
            .append(']');
      }

      out.append(" mine into=").append(this.movesIntoNetwork());
      return out.toString();
   }

   @Override
   public void setupContentPacket(PacketWriter writer) {
      super.setupContentPacket(writer);
      writer.putNextEnum(this.state);
      writer.putNextString(this.conflictItemID == null ? "" : this.conflictItemID);
      writer.putNextInt(this.conflictX);
      writer.putNextInt(this.conflictY);
      writer.putNextInt(this.ordinal);
      writer.putNextString(this.customName == null ? "" : this.customName);
   }

   @Override
   public void applyContentPacket(PacketReader reader) {
      super.applyContentPacket(reader);
      this.state = reader.getNextEnum(DeviceState.class);
      String itemID = reader.getNextString();
      this.conflictItemID = itemID.isEmpty() ? null : itemID;
      this.conflictX = reader.getNextInt();
      this.conflictY = reader.getNextInt();
      this.ordinal = reader.getNextInt();
      String name = reader.getNextString();
      this.customName = name.isEmpty() ? null : name;
   }

   /**
    * Asks the server for this bus's state when a client first sees it.
    *
    * <p>Without this the push path only covers changes, so a bus that went inactive before a player logged
    * in would draw as though it were working — {@code Level.replaceObjectEntity} consults this before
    * queueing the request. The filter is deliberately not sent this way: it is large, only the panel needs
    * it, and it travels in the open packet.
    */
   @Override
   public boolean shouldRequestPacket() {
      return true;
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

      NetworkIndex index = this.networkIndex();
      if (index == null) {
         return 0;
      }

      return this.transferOnce(level, container, index);
   }

   /**
    * The same transfer, against the network's shared index.
    *
    * <p><b>Where the old cost was.</b> This loop used to ask, for every slot it looked at, how many of that
    * item each side held -- and the network side of that question scanned every unit's every slot. So a chest
    * of mixed items cost slots times slots, and a second bus paid it again privately. Now the network's side
    * of the question is a lookup in one shared count, and the container's side is one pass over forty slots
    * taken before the loop. The arithmetic and the decisions are unchanged; only who counts, and how often.
    */
   private int transferOnce(Level level, Inventory container, NetworkIndex index) {
      List<Inventory> from = this.sources(index, container);
      List<Inventory> to = this.destinations(index, container);
      boolean into = this.movesIntoNetwork();

      // One pass, because the container is the side the index does not cover. Forty slots at most, and a
      // network of any size costs nothing on top of it.
      Map<Item, Integer> inContainer = new HashMap<>();
      for (int slot = 0; slot < container.getSize(); slot++) {
         slotsScanned++;
         InventoryItem item = container.getItem(slot);
         if (item != null) {
            inContainer.merge(item.item, item.getAmount(), Integer::sum);
         }
      }

      for (Inventory fromInventory : from) {
         for (int slot = 0; slot < fromInventory.getSize(); slot++) {
            slotsScanned++;
            InventoryItem item = fromInventory.getItem(slot);
            if (item == null) {
               continue;
            }

            int held = inContainer.getOrDefault(item.item, 0);
            int inNetwork = index.of(item.item);
            int allowed = this.allowedToMove(item.item,
               into ? held : inNetwork,
               into ? inNetwork : held,
               index);
            if (allowed <= 0) {
               continue;
            }

            int wanted = Math.min(Math.min(allowed, MAX_PER_TRANSFER), item.getAmount());
            int moved = move(level, fromInventory, to, item, wanted);
            if (moved > 0) {
               // The index is not corrected here, and the first version of this code was wrong to try. Our own
               // move goes through Inventory like anyone else's, so the change hook already applied it; doing
               // it again counted every transfer twice. The drift check found it immediately, which is the
               // argument for having one.
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

      moves++;

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
    * Why a proposed rule set would be refused, or null if it is fine.
    *
    * <p>The right moment to reject an unsatisfiable rule set is when it is written, not by retrying the write
    * forever -- which is what the old code did, and what a player experienced as items shuttling back and forth.
    * A device that has already been stopped can explain itself, but a rule that was never accepted needs no
    * explaining later.
    *
    * <p>Evaluated against the proposal rather than by adopting it and looking: a rule set is accepted or refused
    * as one thing, so nothing may be in force while it is still being judged.
    */
   public String whyRefused(ItemCategoriesFilter proposed) {
      Inventory container = this.attachedContainer();
      NetworkIndex index = this.networkIndex();
      if (container == null || index == null) {
         return null;
      }

      for (ObjectEntity peer : index.devices()) {
         if (peer == this || !(peer instanceof BusObjectEntity)) {
            continue;
         }

         BusObjectEntity other = (BusObjectEntity)peer;
         if (other.movesIntoNetwork() == this.movesIntoNetwork() || other.attachedContainer() != container) {
            continue;
         }

         String contested = this.movesIntoNetwork()
            ? firstUnsatisfiableItem(proposed, other.filter, this, other, index)
            : firstUnsatisfiableItem(other.filter, proposed, other, this, index);

         if (contested != null) {
            return Localization.translate("ui", "arcanestorage_refused",
               "item", Localization.translate("item", contested),
               "x", String.valueOf(other.tileX),
               "y", String.valueOf(other.tileY));
         }
      }

      return null;
   }

   /**
    * The player changed this bus's rules, so the network reconsiders everything and every device revalidates.
    *
    * <p>Called from the panel's apply path and from the harness's rule verbs. Without it a new rule would sit
    * unnoticed until something else happened to disturb the same item -- which was the first thing to go wrong
    * when the timers came out, and it made a correct system look broken.
    */
   public void rulesChanged() {
      NetworkIndex index = this.networkIndex();
      if (index != null) {
         index.scheduler().rulesChanged();
      }
   }

   /**
    * The shared index for this bus's network, walking only when it has to.
    *
    * <p><b>This is where the idle cost went.</b> A bus used to walk its network every time it ticked, and so
    * did every other bus on the same network, each building its own private count. Now the first device to ask
    * within the freshness window walks and builds; the rest hold a reference to the same object and do
    * nothing. A layout change invalidates every copy at once, so the saving costs no staleness that the old
    * per-second recount did not already have.
    *
    * <p>Returns null when this bus is on no network, which the caller reports as a state rather than treating
    * as an empty network -- the two are different mistakes and want different messages.
    */
   public NetworkIndex networkIndex() {
      Level level = this.getLevel();
      if (level == null) {
         return null;
      }

      // A bus that found no network at all has nothing to cache, so without this it would walk on every single
      // tick -- measured at 62 walks in 60 ticks for one disconnected bus, which is worse than the timer this
      // design replaced. Retried at the heartbeat cadence instead, and immediately when the layout changes,
      // which is the only thing that can connect it.
      if (this.cachedNetwork == null
            && this.searchedUnderTopology == NetworkIndexes.topologyVersion()
            && NetworkIndexes.ticksSince(level, this.lastSearched) < NetworkScheduler.HEARTBEAT_TICKS) {
         return null;
      }

      if (this.cachedNetwork == null) {
         walkedNoCache++;
      } else if (!NetworkIndexes.freshFor(level, this.cachedNetwork)) {
         walkedStale++;
      } else if (!this.cachedNetwork.holds(this)) {
         walkedNotMember++;
      }

      if (NetworkIndexes.stillGood(level, this.cachedNetwork, this)) {
         // The periodic check lives here rather than on a timer of its own: a network with no devices left has
         // nobody to be wrong for, and one being used is exactly the one worth checking.
         NetworkIndexes.reconcile(level, this.cachedNetwork);
         return this.cachedNetwork;
      }

      // Itself first, and not as a nicety. The walk starts on this tile and tests its neighbours, so a device
      // never discovers itself -- which would leave the shared list missing whichever device built it. Two
      // consequences, both silent: a bus would not find its opposite number if that bus had built the index,
      // so a conflict would go undetected; and no device would recognise itself as a member, so every one of
      // them would walk again every tick and the sharing would achieve nothing.
      List<ObjectEntity> devices = new ArrayList<>();
      devices.add(this);
      this.cachedNetwork = NetworkIndexes.share(level, this.network(devices), devices);
      this.lastSearched = NetworkIndexes.tickOn(level);
      this.searchedUnderTopology = NetworkIndexes.topologyVersion();
      return this.cachedNetwork;
   }

   /**
    * The network this bus belongs to, walked from its own tile.
    *
    * <p>Recomputed rather than stored, for the same reason the terminal recomputes it: membership is a
    * pure function of the layout, so breaking a unit needs no cleanup anywhere.
    */
   public List<NetworkStorage> network() {
      return this.network(null);
   }

   /**
    * The network, and optionally the other buses standing on it.
    *
    * <p>Peers cost nothing extra: a bus conducts, so every bus on the network is already visited by this
    * walk as a conducting tile. Recording them there is what lets a bus ask whether anything else is
    * fighting it without a second traversal.
    */
   public List<NetworkStorage> network(List<ObjectEntity> devicesOut) {
      networkWalks++;
      final Level level = this.getLevel();
      return UnitNetwork.discover(this.tileX, this.tileY, (x, y) -> {
         ObjectEntity candidate = level.entityManager.getObjectEntity(x, y);
         if (candidate instanceof NetworkStorage) {
            NetworkStorage member = (NetworkStorage)candidate;
            return member.isOnNetwork() ? member : null;
         }

         return null;
      }, (x, y) -> {
         if (!(level.getObject(x, y) instanceof NetworkConductor)) {
            return false;
         }

         if (devicesOut != null) {
            ObjectEntity at = level.entityManager.getObjectEntity(x, y);
            if (at instanceof BusObjectEntity && !at.removed()) {
               devicesOut.add(at);
            }
         }

         return true;
      }, StorageTerminalObjectEntity.MAX_UNITS, StorageTerminalObjectEntity.MAX_CONDUITS);
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
      SaveData filterSave = new SaveData("FILTER");
      this.filter.addSaveData(filterSave);
      save.addSaveData(filterSave);

      // The number is saved rather than derived on load, because deriving it would renumber the survivors
      // every time a bus was broken, and a player who has learnt which one is #2 would find it moved.
      save.addInt("ordinal", this.ordinal);
      if (this.customName != null) {
         // Safe, not unsafe: this string is whatever a player typed, and the unsafe variant writes it into the
         // save verbatim. A name containing the format's own delimiter would corrupt this entity's save data.
         save.addSafeString("customName", this.customName);
      }
   }

   @Override
   public void applyLoadData(LoadData save) {
      super.applyLoadData(save);
      LoadData filterSave = save.getFirstLoadDataByName("FILTER");
      if (filterSave != null) {
         this.filter.applyLoadData(filterSave);
      }

      this.ordinal = save.getInt("ordinal", 0, false);
      String name = save.getSafeString("customName", "", false);
      this.customName = name.isEmpty() ? null : name;
   }
}
