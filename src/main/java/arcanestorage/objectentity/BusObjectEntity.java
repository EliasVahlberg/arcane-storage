package arcanestorage.objectentity;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import arcanestorage.network.NetworkConductor;
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

   private int ticksUntilTransfer = TRANSFER_INTERVAL;

   /**
    * Whether this bus is working, and why not when it is not.
    *
    * <p>Derived and never saved. Synced to clients through {@link #setupContentPacket}, because the sprite
    * that shows it is drawn client-side.
    */
   private DeviceState state = DeviceState.ACTIVE;

   /** The item a conflict was found on, for the explanation. Null unless {@link DeviceState#RULE_CONFLICT}. */
   private String conflictItemID;

   /** Where the device this bus conflicts with stands, so the explanation can point at it. */
   private int conflictX;

   private int conflictY;

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

   /**
    * @param allowAllByDefault whether an unconfigured bus moves everything. True for an import bus, since
    *        importing only adds and "point it at a chest" is the whole feature; false for an export bus,
    *        because one that emptied a network the moment it was placed would be a trap. This is vanilla's
    *        own constructor flag, not an invention of ours.
    */
   protected BusObjectEntity(Level level, String stringID, int x, int y, boolean allowAllByDefault) {
      super(level, stringID, x, y);
      this.filter = new ItemCategoriesFilter(ItemCategory.masterCategory, allowAllByDefault);
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
   protected abstract int allowedToMove(Item item, int inSource, int inDestination, BusObjectEntity.Holdings network);

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
   public int networkShouldHold(Item item, BusObjectEntity.Holdings network) {
      int ceiling = NO_TARGET;

      ItemCategoriesFilter.ItemLimits limits = this.filter.getItemLimits(item);
      if (limits != null && !limits.isDefault()) {
         ceiling = tighten(ceiling, limits.getMaxItems());
      }

      for (ItemCategoriesFilter.ItemCategoryFilter category = this.filter.getItemCategory(item);
            category != null;
            category = category.parent) {
         if (!category.isDefault()) {
            ceiling = tighten(ceiling, category.getMaxItems() - network.inCategoryExcept(category, item));
         }
      }

      if (this.filter.maxAmount != Integer.MAX_VALUE) {
         // Per item, whatever mode the filter carries. A bus's number cannot sensibly mean anything else:
         // read as a cap on the network's entire item count -- which is what TOTAL_ITEMS means, and it is
         // the mode the panel starts in -- a network holding more than the number leaves zero headroom, so
         // an import bus stops dead and an export bus sees the whole network as surplus. Both were observed
         // in game. The panel's mode dropdown is not offered for a bus for the same reason.
         boolean inStacks = this.filter.limitMode == ItemCategoriesFilter.ItemLimitMode.TOTAL_STACKS
               || this.filter.limitMode == ItemCategoriesFilter.ItemLimitMode.TOTAL_STACKS_EACH_ITEM;
         ceiling = tighten(ceiling, inStacks
               ? this.filter.maxAmount * item.getStackSize()
               : this.filter.maxAmount);
      }

      return ceiling;
   }

   /** The same question with the bus's live network, for diagnostics and the harness. */
   public int networkShouldHold(Item item) {
      return this.networkShouldHold(item, new BusObjectEntity.Holdings(inventoriesOf(this.network())));
   }

   /** Keeps the lowest ceiling seen, treating a cap already exceeded as zero rather than as a negative. */
   private static int tighten(int ceiling, int candidate) {
      int floored = Math.max(candidate, 0);
      return ceiling == NO_TARGET ? floored : Math.min(ceiling, floored);
   }

   /**
    * What one side of a transfer holds, counted the ways a filter's limits are measured.
    *
    * <p>Exists because a filter's limits are defined over an {@code InventoryRange} -- a range within one
    * inventory -- and a network is many. Evaluating them per unit would quietly turn "the network keeps 200"
    * into "each unit keeps 200". The numbers come from the filter; the summing across members is ours.
    */
   protected static final class Holdings {
      private final List<Inventory> side;
      private int total = -1;

      protected Holdings(List<Inventory> side) {
         this.side = side;
      }

      /** How many of one item the side holds. */
      protected int of(Item item) {
         return countIn(this.side, item.getStringID());
      }

      /** Everything the side holds, of every item, counted once and remembered. */
      protected int total() {
         if (this.total < 0) {
            int sum = 0;

            for (Inventory inventory : this.side) {
               for (int slot = 0; slot < inventory.getSize(); slot++) {
                  InventoryItem item = inventory.getItem(slot);
                  if (item != null) {
                     sum += item.getAmount();
                  }
               }
            }

            this.total = sum;
         }

         return this.total;
      }

      /**
       * What the side holds of a category's items, excluding one item.
       *
       * <p>The exclusion is what turns a category's limit into a ceiling for the item being moved: the rest
       * of the category is occupied space, and what is left is this item's room.
       */
      protected int inCategoryExcept(ItemCategoriesFilter.ItemCategoryFilter category, Item except) {
         int sum = 0;

         for (Inventory inventory : this.side) {
            for (int slot = 0; slot < inventory.getSize(); slot++) {
               InventoryItem item = inventory.getItem(slot);
               if (item != null && item.item != except && category.category.containsItemOrInChildren(item.item)) {
                  sum += item.getAmount();
               }
            }
         }

         return sum;
      }
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

      Level level = this.getLevel();
      if (level == null) {
         return;
      }

      // One walk serves both jobs. Evaluating the state needs the network and the buses on it, and so does
      // transferring, so the walk is done here and handed down rather than repeated.
      Inventory container = this.attachedContainer();
      List<BusObjectEntity> peers = new ArrayList<>();
      List<NetworkStorage> network =
         container == null ? Collections.emptyList() : this.network(peers);

      if (!this.evaluate(container, network, peers).isActive()) {
         return;
      }

      transfers++;
      this.transferOnce(level, container, network);
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
   private DeviceState evaluate(
      Inventory container, List<NetworkStorage> network, List<BusObjectEntity> peers
   ) {
      if (container == null) {
         return this.setState(DeviceState.NO_CONTAINER, null, 0, 0);
      }

      if (network.isEmpty()) {
         return this.setState(DeviceState.NO_NETWORK, null, 0, 0);
      }

      BusObjectEntity opposed = null;
      for (BusObjectEntity peer : peers) {
         if (peer != this
               && peer.movesIntoNetwork() != this.movesIntoNetwork()
               && peer.attachedContainer() == container) {
            opposed = peer;
            break;
         }
      }

      if (opposed == null) {
         return this.setState(DeviceState.ACTIVE, null, 0, 0);
      }

      BusObjectEntity importer = this.movesIntoNetwork() ? this : opposed;
      BusObjectEntity exporter = this.movesIntoNetwork() ? opposed : this;
      String contested = firstUnsatisfiableItem(importer, exporter, new Holdings(inventoriesOf(network)));

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
      BusObjectEntity importer, BusObjectEntity exporter, Holdings network
   ) {
      for (Item item : ItemRegistry.getItems()) {
         if (item == null
               || !importer.filter.isItemAllowed(item)
               || !exporter.filter.isItemAllowed(item)) {
            continue;
         }

         int ceiling = importer.networkShouldHold(item, network);
         int floor = exporter.networkShouldHold(item, network);
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
      if (this.state.isActive()) {
         return "";
      }

      if (this.state != DeviceState.RULE_CONFLICT) {
         return Localization.translate("ui", this.state.localeKey);
      }

      // The locale keys under [item] are the registry string IDs, so this needs no registry lookup and
      // works for a modded item as well as a vanilla one.
      return Localization.translate("ui", DeviceState.RULE_CONFLICT.localeKey,
         "item", this.conflictItemID == null
            ? "?"
            : Localization.translate("item", this.conflictItemID),
         "other", Localization.translate("object",
            this.movesIntoNetwork() ? "arcanestorageexportbus" : "arcanestorageimportbus"),
         "x", String.valueOf(this.conflictX),
         "y", String.valueOf(this.conflictY));
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

      List<BusObjectEntity> peers = new ArrayList<>();
      List<NetworkStorage> network = this.network(peers);
      if (network.isEmpty()) {
         return "no network";
      }

      StringBuilder out = new StringBuilder("peers=" + peers.size());
      for (BusObjectEntity peer : peers) {
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
   }

   @Override
   public void applyContentPacket(PacketReader reader) {
      super.applyContentPacket(reader);
      this.state = reader.getNextEnum(DeviceState.class);
      String itemID = reader.getNextString();
      this.conflictItemID = itemID.isEmpty() ? null : itemID;
      this.conflictX = reader.getNextInt();
      this.conflictY = reader.getNextInt();
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

      List<NetworkStorage> network = this.network();
      if (network.isEmpty()) {
         return 0;
      }

      return this.transferOnce(level, container, network);
   }

   /**
    * The same transfer, given what the caller has already looked up.
    *
    * <p>Split out so the tick pays for one network walk rather than two: the state evaluation needs the
    * network and the buses on it, and so does this.
    */
   private int transferOnce(Level level, Inventory container, List<NetworkStorage> network) {
      List<Inventory> from = this.sources(network, container);
      List<Inventory> to = this.destinations(network, container);

      // Counted once per transfer rather than per item: a whole-network limit needs the network's totals,
      // and the network does not change while one item is being moved.
      BusObjectEntity.Holdings networkHoldings = new BusObjectEntity.Holdings(inventoriesOf(network));

      for (Inventory fromInventory : from) {
         for (int slot = 0; slot < fromInventory.getSize(); slot++) {
            slotsScanned++;
            InventoryItem item = fromInventory.getItem(slot);
            if (item == null) {
               continue;
            }

            String itemID = item.item.getStringID();
            int allowed = this.allowedToMove(item.item, countIn(from, itemID), countIn(to, itemID), networkHoldings);
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
   public List<NetworkStorage> network(List<BusObjectEntity> peersOut) {
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

         if (peersOut != null) {
            ObjectEntity at = level.entityManager.getObjectEntity(x, y);
            if (at instanceof BusObjectEntity && !at.removed()) {
               peersOut.add((BusObjectEntity)at);
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
   }

   @Override
   public void applyLoadData(LoadData save) {
      super.applyLoadData(save);
      LoadData filterSave = save.getFirstLoadDataByName("FILTER");
      if (filterSave != null) {
         this.filter.applyLoadData(filterSave);
      }
   }
}
