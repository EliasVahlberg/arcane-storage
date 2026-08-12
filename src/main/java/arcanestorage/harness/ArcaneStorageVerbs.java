package arcanestorage.harness;

import java.awt.Point;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import arcanestorage.ArcaneStorage;
import arcanestorage.container.StorageTerminalContainer;
import arcanestorage.network.NetworkContents;
import arcanestorage.object.StorageConduitObject;
import arcanestorage.container.BusContainer;
import arcanestorage.objectentity.BusObjectEntity;
import necesse.inventory.item.ItemCategory;
import necesse.inventory.itemFilter.ItemCategoriesFilter;
import arcanestorage.objectentity.StorageTerminalObjectEntity;
import arcanestorage.network.NetworkStorage;
import necesse.engine.network.Packet;
import necesse.engine.network.PacketReader;
import necesse.engine.network.packet.PacketOpenContainer;
import necesse.engine.network.PacketWriter;
import necesse.engine.registries.ItemRegistry;
import necesse.engine.registries.ObjectRegistry;
import necesse.entity.objectEntity.ObjectEntity;
import necesse.entity.objectEntity.interfaces.OEInventory;
import necesse.inventory.container.ContainerAction;
import necesse.inventory.container.slots.ContainerSlot;
import necesse.inventory.InventoryUpdateListener;
import necesse.inventory.item.Item;
import necesse.inventory.Inventory;
import necesse.inventory.InventoryItem;
import necesse.level.maps.Level;
import necesseheadlessharness.Harness;
import necesseheadlessharness.command.TestContext;
import necesseheadlessharness.Json;
import necesseheadlessharness.command.TestQuery;
import necesseheadlessharness.command.TestVerb;

/**
 * Registers this mod's own vocabulary with the harness.
 *
 * <p><strong>Nothing outside this package may reference it.</strong> The harness is an
 * {@code optionalDependencies} entry, so it may be absent at runtime -- and merely loading a class
 * that mentions a missing type throws {@code NoClassDefFoundError}. Keeping every kit reference
 * inside this one class means a player without the harness loses the test verbs and nothing else.
 * {@link #register()} is the only door in, and <strong>its caller must wrap it in a try/catch (Throwable)</strong> -- a guard inside this class cannot work, because the JVM fails while resolving the class and no code in it runs.
 *
 * <p>What lives here is what the harness cannot know: the shape of a storage network. The harness's own
 * {@code expect item} counts one tile's inventory, which is the right answer for a chest and the
 * wrong one for a terminal, so this registers a version that means "everything the network at this
 * tile can see". Registering over a built-in is supported by the harness for exactly this case.
 */
public final class ArcaneStorageVerbs {

   private ArcaneStorageVerbs() {
   }

   /**
    * Registers everything. <strong>The caller must guard this call</strong>, because a guard cannot
    * live in here: with the harness absent, the JVM fails while resolving this class and no code in
    * it ever runs. See the try/catch in {@code ArcaneStorage.postInit}.
    */
   public static void register() {
      // Lets scenarios read 'place unit 5 0' rather than naming full string IDs.
      Harness.registerObjectAlias("terminal", ArcaneStorage.TERMINAL_STRING_ID);
      Harness.registerObjectAlias("unit", ArcaneStorage.UNIT_STRING_ID);
      Harness.registerObjectAlias("conduit", ArcaneStorage.CONDUIT_STRING_ID);
      Harness.registerObjectAlias("importbus", ArcaneStorage.IMPORT_BUS_STRING_ID);
      Harness.registerObjectAlias("exportbus", ArcaneStorage.EXPORT_BUS_STRING_ID);

      Harness.registerVerb(new ReportVerb());
      Harness.registerVerb(new ResetVerb());
      Harness.registerVerb(new WithdrawVerb());
      Harness.registerVerb(new DepositVerb());
      Harness.registerVerb(new DepositAllVerb());
      Harness.registerVerb(new DepositCursorVerb());
      Harness.registerVerb(new InstallVerb());
      Harness.registerVerb(new BenchVerb());
      Harness.registerVerb(new RuleVerb());
      Harness.registerVerb(new TransferVerb());
      Harness.registerVerb(new BusEditVerb());
      Harness.registerVerb(new BusRoundTripVerb());
      Harness.registerVerb(new BusStatsResetVerb());
      Harness.registerExpectation(new ListenerCheckVerb());
      Harness.registerExpectation(new BusStatsQuery());
      Harness.registerVerb(new RuleGlobalVerb());
      Harness.registerVerb(new RuleCategoryVerb());
      Harness.registerExpectation(new BusOpenPacketQuery());

      Harness.registerExpectation(new UnitsExpectation());
      Harness.registerExpectation(new InUseExpectation());
      Harness.registerExpectation(new NetworkItemExpectation());
      Harness.registerExpectation(new CapacityExpectation());
      Harness.registerExpectation(new FitsExpectation());
      Harness.registerExpectation(new MaskExpectation());
      Harness.registerExpectation(new NetworkTotalExpectation());
      Harness.registerExpectation(new BusFilterExpectation());
      Harness.registerExpectation(new ContainerItemExpectation());
   }

   // ---------------------------------------------------------------------------------------
   // Helpers shared by the expectations below.
   // ---------------------------------------------------------------------------------------

   static StorageTerminalObjectEntity terminalAt(TestContext context, int dxIndex) {
      int x = context.tileX(context.intArg(dxIndex));
      int y = context.tileY(context.intArg(dxIndex + 1));
      ObjectEntity entity = context.level.entityManager.getObjectEntity(x, y);
      return entity instanceof StorageTerminalObjectEntity ? (StorageTerminalObjectEntity)entity : null;
   }

   static List<NetworkStorage> unitsFrom(TestContext context) {
      StorageTerminalObjectEntity terminal = terminalAt(context, 2);
      return terminal == null ? null : terminal.getLinkedUnits();
   }

   static boolean noTerminal(TestContext context) {
      context.fail("no terminal at " + context.arg(2) + "," + context.arg(3));
      return false;
   }

   /**
    * Requires an open terminal, which several verbs below do because they exercise the container
    * rather than the network directly. Going through the container is the point: it is what a
    * player's click actually touches.
    */
   static StorageTerminalContainer requireTerminalContainer(TestContext context, String verb) {
      if (!(context.client.getContainer() instanceof StorageTerminalContainer)) {
         context.fail("'" + verb + "' needs an open terminal; run 'open <dx> <dy>' first");
         return null;
      }

      return (StorageTerminalContainer)context.client.getContainer();
   }

   /** {@code report <dx> <dy>} -- dumps what a terminal's network can see. Diagnostic, not an assertion. */
   private static final class ReportVerb implements TestVerb {
      public String name() {
         return "report";
      }

      public String usage() {
         return "report <dx> <dy>";
      }

      public int coordinateArgIndex() {
         return 1;
      }

      public boolean run(TestContext context) {
         StorageTerminalObjectEntity terminal = terminalAt(context, 1);
         if (terminal == null) {
            context.fail("no terminal at " + context.arg(1) + "," + context.arg(2));
            return false;
         }

         List<NetworkStorage> units = terminal.getLinkedUnits();
         context.info("network at " + context.arg(1) + "," + context.arg(2) + ": " + units.size() + " units");

         for (InventoryItem item : NetworkContents.aggregate(
               context.level, units, StorageTerminalContainer.AGGREGATE_PURPOSE)) {
            context.info("  " + item.item.getStringID() + " x" + item.getAmount());
         }

         return true;
      }
   }

   /**
    * {@code reset} -- removes every storage object on the level.
    *
    * <p>Scenarios share one server boot, so isolation is each scenario's own responsibility. The
    * harness's {@code clear} covers a radius, which is not the same as covering the level, and
    * {@code expect total} scans everything -- so a unit left in an unexpected place by an earlier
    * scenario would show up as a failure here. This removes them wherever they are.
    */
   private static final class ResetVerb implements TestVerb {
      public String name() {
         return "reset";
      }

      public String usage() {
         return "reset";
      }

      public int coordinateArgIndex() {
         return -1;
      }

      public boolean run(TestContext context) {
         ArrayList<Point> ours = new ArrayList<>();

         for (ObjectEntity entity : context.level.entityManager.objectEntities) {
            if (!entity.removed()
                  && (entity instanceof NetworkStorage || entity instanceof StorageTerminalObjectEntity)) {
               ours.add(new Point(entity.tileX, entity.tileY));
            }
         }

         for (Point tile : ours) {
            context.level.setObject(tile.x, tile.y, 0);
         }

         context.info("reset removed " + ours.size() + " storage objects from the level");
         return true;
      }
   }

   /** {@code withdraw <item> <n> [cursor]} -- through the container's own action and packet encoding. */
   private static final class WithdrawVerb implements TestVerb {
      public String name() {
         return "withdraw";
      }

      public String usage() {
         return "withdraw <itemStringID> <amount> [cursor]";
      }

      public int coordinateArgIndex() {
         return -1;
      }

      public boolean needsPlayer() {
         return true;
      }

      public boolean run(TestContext context) {
         StorageTerminalContainer container = requireTerminalContainer(context, "withdraw");
         if (container == null) {
            return false;
         }

         String itemID = context.arg(1);
         int amount = context.intArg(2);
         boolean toCursor = context.argCount() > 3 && "cursor".equalsIgnoreCase(context.arg(3));

         // Encoded exactly as WithdrawAction.runAndSend does, and handed to the same executePacket,
         // so the packet encoding is exercised rather than bypassed. A withdrawal that works only
         // when called in-process is not a working withdrawal.
         Packet content = new Packet();
         PacketWriter writer = new PacketWriter(content);
         new InventoryItem(itemID, 1).addPacketContent(writer);
         writer.putNextInt(amount);
         writer.putNextBoolean(toCursor);
         container.withdrawAction.executePacket(new PacketReader(content));
         container.markFullDirty();

         context.info("withdrew up to " + amount + " " + itemID
               + (toCursor ? " to the cursor" : " to the inventory"));
         return true;
      }
   }

   /**
    * {@code install <item>} -- installs a crafting station into the open terminal.
    *
    * <p>Goes through {@code Inventory.addItem}, so the terminal's own {@code isItemValid} decides
    * whether the item is a station. Refusing is reported as a failure, which is what lets a test
    * assert that a rock is not a workbench.
    */
   private static final class InstallVerb implements TestVerb {
      public String name() {
         return "install";
      }

      public String usage() {
         return "install <item>";
      }

      public boolean needsPlayer() {
         return true;
      }

      public boolean run(TestContext context) {
         StorageTerminalContainer container = requireTerminalContainer(context, "install");
         if (container == null) {
            return false;
         }

         StorageTerminalObjectEntity terminal = container.terminal;
         InventoryItem item = new InventoryItem(context.arg(1), 1);
         boolean added = terminal.inventory.addItem(terminal.getLevel(), context.client.playerMob, item, "arcanestorageinstall", null);
         if (!added || item.getAmount() > 0) {
            context.fail("the terminal refused to install " + context.arg(1));
            return false;
         }

         container.markFullDirty();
         context.info("installed " + context.arg(1));
         return true;
      }
   }

   /**
    * {@code depositcursor [amount]} -- puts what the player is holding into the network.
    *
    * <p>Exists so the click conventions added for the storage panel are testable without a mouse.
    * Pair it with {@code withdraw <item> <n> cursor}, which already fills the cursor, and the pair
    * covers the interesting case: a partial deposit must leave the remainder on the cursor and must
    * not change how many items exist in total.
    */
   private static final class DepositCursorVerb implements TestVerb {
      public String name() {
         return "depositcursor";
      }

      public String usage() {
         return "depositcursor [amount]  (default: everything held)";
      }

      public boolean needsPlayer() {
         return true;
      }

      public boolean run(TestContext context) {
         StorageTerminalContainer container = requireTerminalContainer(context, "depositcursor");
         if (container == null) {
            return false;
         }

         int amount = context.argCount() > 1 ? context.intArg(1) : -1;

         // Through the same executePacket the click path uses, for the same reason the withdraw
         // verb does: a deposit that only works when called in-process is not a working deposit.
         Packet content = new Packet();
         PacketWriter writer = new PacketWriter(content);
         writer.putNextInt(amount);
         container.depositCursorAction.executePacket(new PacketReader(content));
         container.markFullDirty();

         context.info("deposited " + (amount <= 0 ? "everything held" : String.valueOf(amount)) + " from the cursor");
         return true;
      }
   }

   /**
    * {@code deposit <item> <n>} -- shift-clicks the item out of the player's own inventory.
    *
    * <p>Player slots are the indices below {@code NETWORK_START}: {@code Container} assigns them in
    * its own constructor, before this container adds any unit slots.
    */
   private static final class DepositVerb implements TestVerb {
      public String name() {
         return "deposit";
      }

      public String usage() {
         return "deposit <itemStringID> <amount>";
      }

      public int coordinateArgIndex() {
         return -1;
      }

      public boolean needsPlayer() {
         return true;
      }

      public boolean run(TestContext context) {
         StorageTerminalContainer container = requireTerminalContainer(context, "deposit");
         if (container == null) {
            return false;
         }

         String itemID = context.arg(1);
         int amount = context.intArg(2);
         int moved = 0;

         for (int slot = 0; slot < container.NETWORK_START && moved < amount; slot++) {
            ContainerSlot containerSlot = container.getSlot(slot);
            InventoryItem item = containerSlot == null ? null : containerSlot.getItem();
            if (item == null || !item.item.getStringID().equals(itemID)) {
               continue;
            }

            moved += container.applyContainerAction(slot, ContainerAction.QUICK_MOVE).value;
         }

         return context.check(moved == amount, "deposited " + amount + " " + itemID,
               "only moved " + moved + "; the network may be full or the inventory short");
      }
   }

   /** {@code depositall} -- the button that moves everything the network will accept. */
   private static final class DepositAllVerb implements TestVerb {
      public String name() {
         return "depositall";
      }

      public String usage() {
         return "depositall";
      }

      public int coordinateArgIndex() {
         return -1;
      }

      public boolean needsPlayer() {
         return true;
      }

      public boolean run(TestContext context) {
         StorageTerminalContainer container = requireTerminalContainer(context, "depositall");
         if (container == null) {
            return false;
         }

         context.info("depositall moved " + container.depositAll() + " item(s)");
         return true;
      }
   }

   /**
    * {@code bench <dx> <dy> <units> [iterations]} -- times the work the interface redoes on every
    * redraw, and fails if it does not fit in a frame.
    *
    * <p>Exists because "usable with a large network" was a claim backed by reasoning rather than
    * measurement. Two costs were removed on the strength of reading the code, and reading the code
    * cannot tell you whether what remains fits in a frame.
    */
   /**
    * Writes a bus's filter, since a test should not have to drive a form to set a threshold.
    *
    * <p>{@code rule <dx> <dy> <item> [<target>]} allows an item and optionally says how much of it the
    * network should hold; {@code deny <item>} unticks one; {@code only <item> [<target>]} clears
    * everything first, which is what a player does with the panel's "Clear all" button before ticking one
    * thing; {@code all} and {@code none} set the master category.
    */
   private static final class RuleVerb implements TestVerb {
      public String name() {
         return "rule";
      }

      public String usage() {
         return "rule <dx> <dy> all|none|<item> [<target>] | deny <item> | only <item> [<target>]";
      }

      public int coordinateArgIndex() {
         return 1;
      }

      public boolean run(TestContext context) {
         BusObjectEntity bus = busAt(context, 1);
         if (bus == null) {
            context.fail("rule: no bus at " + context.arg(1) + "," + context.arg(2));
            return false;
         }

         String word = context.arg(3);
         if ("all".equals(word) || "none".equals(word)) {
            bus.filter.master.setAllowed("all".equals(word));
            context.info("set every item " + ("all".equals(word) ? "allowed" : "denied"));
            return true;
         }

         boolean allowed = !"deny".equals(word);
         boolean exclusive = "only".equals(word);
         int itemIndex = allowed && !exclusive ? 3 : 4;
         String itemStringID = context.arg(itemIndex);
         Item item = ItemRegistry.getItem(itemStringID);
         if (item == null) {
            context.fail("rule: no such item " + itemStringID);
            return false;
         }

         if (exclusive) {
            bus.filter.master.setAllowed(false);
         }

         int target = context.argCount() > itemIndex + 1 ? context.intArg(itemIndex + 1) : 0;
         if (target > 0) {
            bus.filter.setItemAllowed(item, allowed, target);
         } else {
            bus.filter.setItemAllowed(item, allowed);
         }

         context.info((allowed ? "allowed " : "denied ") + itemStringID
               + (target > 0 ? ", network should hold " + target : ""));
         return true;
      }
   }

   /**
    * Edits an open bus panel the way the panel itself will: by sending a whole filter through the
    * container action.
    *
    * <p>{@code busedit <item> <target>}, after {@code open <dx> <dy>} on a bus. The form is client-side
    * and a headless server never builds one, so this is as far as automation can reach into the interface
    * — but it reaches the part worth checking. It exercises the container registration, the open packet,
    * and {@code ItemCategoriesFilter}'s own {@code writePacket}/{@code readPacket} round trip through our
    * action, which is where a wire-format mistake would otherwise sit unnoticed until a player set a rule
    * and watched it do nothing.
    */
   private static final class BusEditVerb implements TestVerb {
      public String name() {
         return "busedit";
      }

      public String usage() {
         return "busedit <item> <target>  (after 'open <dx> <dy>' on a bus)";
      }

      public boolean needsPlayer() {
         return true;
      }

      public boolean run(TestContext context) {
         if (!(context.client.getContainer() instanceof BusContainer)) {
            context.fail("busedit needs an open bus panel; run 'open <dx> <dy>' on a bus first");
            return false;
         }

         BusContainer container = (BusContainer)context.client.getContainer();
         Item item = ItemRegistry.getItem(context.arg(1));
         if (item == null) {
            context.fail("busedit: no such item " + context.arg(1));
            return false;
         }

         int target = context.argCount() > 2 ? context.intArg(2) : 0;

         // Built the way the client builds it: a fresh filter denying everything, then one item ticked.
         ItemCategoriesFilter edited = new ItemCategoriesFilter(ItemCategory.masterCategory, false);
         if (target > 0) {
            edited.setItemAllowed(item, true, target);
         } else {
            edited.setItemAllowed(item, true);
         }

         Packet content = new Packet();
         edited.writePacket(new PacketWriter(content));
         container.setFilterAction.executePacket(new PacketReader(content));

         context.info("sent a filter allowing " + context.arg(1)
               + (target > 0 ? " with a target of " + target : ""));
         return true;
      }
   }

   /**
    * Whether the engine notifies more than one inventory listener, which decides whether an event-driven
    * design can subscribe to vanilla inventories at all.
    *
    * <p>{@code listenercheck <dx> <dy>}. Read in the source first: {@code Inventory}'s notify site uses
    * {@code if (updateIterator.hasNext())} rather than {@code while}, which would mean only the first live
    * listener is ever told, and a disposed first listener swallows the notification entirely. That is a large
    * claim to design around on a reading, so it is measured here: two listeners are attached to a real
    * inventory, one slot is changed, and both counts are reported.
    */
   private static final class ListenerCheckVerb implements TestVerb, TestQuery {
      public String name() {
         return "listenercheck";
      }

      public String usage() {
         return "query listenercheck <dx> <dy>";
      }

      public int coordinateArgIndex() {
         return 2;
      }

      public boolean run(TestContext context) {
         return true;
      }

      public void query(TestContext context, Json.Writer out) {
         ObjectEntity entity = context.level.entityManager.getObjectEntity(
            context.tileX(context.intArg(2)), context.tileY(context.intArg(3)));
         if (!(entity instanceof OEInventory)) {
            out.num("first", -1);
            out.num("second", -1);
            return;
         }

         Inventory inventory = ((OEInventory)entity).getInventory();
         int[] counts = new int[2];
         inventory.addSlotUpdateListener(new CountingListener(counts, 0));
         inventory.addSlotUpdateListener(new CountingListener(counts, 1));

         InventoryItem stone = new InventoryItem("stone", 1);
         inventory.addItem(context.level, null, stone, "arcanestoragelistenercheck", null);

         out.num("first", counts[0]);
         out.num("second", counts[1]);
      }
   }

   /** Counts notifications into a shared array, so the verb can report both without any static state. */
   private static final class CountingListener extends InventoryUpdateListener {
      private final int[] counts;
      private final int index;

      private CountingListener(int[] counts, int index) {
         this.counts = counts;
         this.index = index;
      }

      @Override
      public void onSlotUpdate(int slot) {
         this.counts[this.index]++;
      }

      @Override
      public boolean isDisposed() {
         return false;
      }
   }

   /**
    * How much work the buses have done, and a way to zero it.
    *
    * <p>{@code query busstats} -> {@code {moves, transfers, slots, walks}}; {@code busstatsreset} zeroes them.
    * A system that has reached the state its rules describe should stop moving things; this is how a test
    * says so.
    */
   private static final class BusStatsQuery implements TestVerb, TestQuery {
      public String name() {
         return "busstats";
      }

      public String usage() {
         return "query busstats";
      }

      public boolean run(TestContext context) {
         return true;
      }

      public void query(TestContext context, Json.Writer out) {
         out.num("moves", BusObjectEntity.moves);
         out.num("transfers", BusObjectEntity.transfers);
         out.num("slots", BusObjectEntity.slotsScanned);
         out.num("walks", BusObjectEntity.networkWalks);
      }
   }

   private static final class BusStatsResetVerb implements TestVerb {
      public String name() {
         return "busstatsreset";
      }

      public String usage() {
         return "busstatsreset";
      }

      public boolean run(TestContext context) {
         BusObjectEntity.moves = 0;
         BusObjectEntity.transfers = 0;
         BusObjectEntity.slotsScanned = 0;
         BusObjectEntity.networkWalks = 0;
         context.info("counters zeroed");
         return true;
      }
   }

   /**
    * Sets the panel-wide limit and its mode, which is the control a player reaches for first.
    *
    * <p>{@code ruleglobal <dx> <dy> total|stacks|each|eachstacks|none [amount]}. Nothing could set this
    * headlessly before, which is why the two whole-network modes shipped as silent no-ops.
    */
   private static final class RuleGlobalVerb implements TestVerb {
      public String name() {
         return "ruleglobal";
      }

      public String usage() {
         return "ruleglobal <dx> <dy> total|stacks|each|eachstacks|none [amount]";
      }

      public int coordinateArgIndex() {
         return 1;
      }

      public boolean run(TestContext context) {
         BusObjectEntity bus = busAt(context, 1);
         if (bus == null) {
            context.fail("ruleglobal: no bus there");
            return false;
         }

         String mode = context.arg(3);
         if (mode.equals("none")) {
            bus.filter.maxAmount = Integer.MAX_VALUE;
            context.info("cleared the panel-wide limit");
            return true;
         }

         switch (mode) {
            case "total":
               bus.filter.limitMode = ItemCategoriesFilter.ItemLimitMode.TOTAL_ITEMS;
               break;
            case "stacks":
               bus.filter.limitMode = ItemCategoriesFilter.ItemLimitMode.TOTAL_STACKS;
               break;
            case "each":
               bus.filter.limitMode = ItemCategoriesFilter.ItemLimitMode.TOTAL_EACH_ITEM;
               break;
            case "eachstacks":
               bus.filter.limitMode = ItemCategoriesFilter.ItemLimitMode.TOTAL_STACKS_EACH_ITEM;
               break;
            default:
               context.fail("ruleglobal: unknown mode " + mode);
               return false;
         }

         bus.filter.maxAmount = context.intArg(4);
         context.info("limit " + bus.filter.maxAmount + " in mode " + bus.filter.limitMode);
         return true;
      }
   }

   /**
    * Sets a limit on a whole item category, which the panel can do and which the buses ignored.
    *
    * <p>{@code rulecategory <dx> <dy> <category stringID> <amount>}.
    */
   private static final class RuleCategoryVerb implements TestVerb {
      public String name() {
         return "rulecategory";
      }

      public String usage() {
         return "rulecategory <dx> <dy> <category> <amount>";
      }

      public int coordinateArgIndex() {
         return 1;
      }

      public boolean run(TestContext context) {
         BusObjectEntity bus = busAt(context, 1);
         Item sample = ItemRegistry.getItem(context.arg(3));
         if (bus == null) {
            context.fail("rulecategory: no bus there");
            return false;
         }

         // Named by an item in it, because a category's stringID is not something a test author knows and
         // "the category iron bars are in" is what a rule is actually about.
         ItemCategoriesFilter.ItemCategoryFilter category = sample == null ? null : bus.filter.getItemCategory(sample);
         if (category == null) {
            context.fail("rulecategory: no category for item " + context.arg(3));
            return false;
         }

         category.setMaxItems(context.intArg(4));
         context.info("category " + category.category.stringID + " limited to " + category.getMaxItems());
         return true;
      }
   }

   /**
    * Sends a bus's open packet through bytes and unpacks it the way the engine does, reporting the filter
    * the client would end up editing.
    *
    * <p>{@code query busopenpacket <dx> <dy>} -> {@code {servercount, clientcount}}. This is the hop that a
    * headless server cannot otherwise reach, and the one that was broken while every other test passed: the
    * client received a filter that decoded as empty and then wrote it back, erasing the bus's rules.
    */
   private static final class BusOpenPacketQuery implements TestVerb, TestQuery {
      public String name() {
         return "busopenpacket";
      }

      public String usage() {
         return "query busopenpacket <dx> <dy>";
      }

      public int coordinateArgIndex() {
         return 2;
      }

      public boolean run(TestContext context) {
         return true;
      }

      public void query(TestContext context, Json.Writer out) {
         BusObjectEntity bus = busAt(context, 2);
         if (bus == null) {
            out.num("servercount", -1);
            out.num("clientcount", -1);
            return;
         }

         out.num("servercount", countAllowed(bus.filter));

         // Exactly what leaves the server, put through bytes the way the network does.
         PacketOpenContainer sent = BusContainer.openPacket(ArcaneStorage.BUS_CONTAINER, bus);
         PacketOpenContainer received = new PacketOpenContainer(sent.getPacketData());

         // Exactly what ContainerRegistry.registerOEContainer does before handing over to the container.
         PacketReader reader = new PacketReader(received.content);
         reader.getNextInt();
         reader.getNextInt();
         Packet forContainer = reader.getNextContentPacket();

         // Exactly what BusContainer does on a client.
         ItemCategoriesFilter clientCopy = new ItemCategoriesFilter(ItemCategory.masterCategory, false);
         if (forContainer != null) {
            clientCopy.readPacket(new PacketReader(forContainer));
         }

         out.num("clientcount", countAllowed(clientCopy));
      }
   }

   private static int countAllowed(ItemCategoriesFilter filter) {
      int count = 0;
      for (Item item : ItemRegistry.getItems()) {
         if (item != null && filter.isItemAllowed(item)) {
            count++;
         }
      }

      return count;
   }

   /**
    * Replays the panel's whole cycle in one server-side call: open, copy, edit, send, apply.
    *
    * <p>{@code busroundtrip <dx> <dy> <item> [target]}. This exists because the first bus panel lost every
    * edit in a real client while every headless test passed, and the reason the tests missed it is that
    * they drove the server's container action directly with a <i>freshly constructed</i> filter. The client
    * never does that. It builds its filter by reading the server's, edits <i>that</i>, and writes it back —
    * so the round trip through {@code readPacket} before the edit is the part that was never exercised.
    */
   private static final class BusRoundTripVerb implements TestVerb {
      public String name() {
         return "busroundtrip";
      }

      public String usage() {
         return "busroundtrip <dx> <dy> <item> [target]";
      }

      public int coordinateArgIndex() {
         return 1;
      }

      public boolean run(TestContext context) {
         BusObjectEntity bus = busAt(context, 1);
         Item item = ItemRegistry.getItem(context.arg(3));
         if (bus == null || item == null) {
            context.fail("busroundtrip: no bus or no such item");
            return false;
         }

         int target = context.argCount() > 4 ? context.intArg(4) : 0;

         // 1. What the server sends when the panel opens.
         Packet opened = new Packet();
         bus.filter.writePacket(new PacketWriter(opened));

         // 2. What the client builds from it -- same construction as BusContainer's client branch.
         ItemCategoriesFilter clientCopy = new ItemCategoriesFilter(ItemCategory.masterCategory, false);
         clientCopy.readPacket(new PacketReader(opened));

         // 3. The edit the panel makes, through the same call the form's checkbox uses.
         boolean accepted = target > 0
            ? clientCopy.setItemAllowed(item, true, target)
            : clientCopy.setItemAllowed(item, true);
         context.info("the client's copy accepted the edit: " + accepted
               + ", and now reads allowed=" + clientCopy.isItemAllowed(item));

         // 4. Back to the server, as SetFilterAction does it.
         Packet edited = new Packet();
         clientCopy.writePacket(new PacketWriter(edited));
         bus.filter.readPacket(new PacketReader(edited));

         context.info("the bus now reads allowed=" + bus.filter.isItemAllowed(item)
               + " target=" + bus.networkShouldHold(item));
         return true;
      }
   }

   /**
    * Runs a bus's transfer immediately, instead of waiting out its interval.
    *
    * <p>A bus moves at most one stack per second on its own, which is right for a player watching a chest
    * empty and wrong for a test: waiting is slow, and a test that slept would be measuring the clock
    * rather than the transfer. This calls exactly what the tick calls.
    *
    * <p>{@code transfer <dx> <dy> [times]} — repeated, because one call moves one item type, so emptying
    * a mixed chest takes as many calls as it has kinds of thing in it.
    */
   private static final class TransferVerb implements TestVerb {
      // Deliberately not a TestQuery. 'query' is for reading a value, and a query that performed a
      // transfer to report how much it moved would be a question that changes the answer -- which is
      // exactly the kind of test tooling that produces results nobody can reproduce. What moved is
      // observable: read both sides.
      public String name() {
         return "transfer";
      }

      public String usage() {
         return "transfer <dx> <dy> [times]";
      }

      public int coordinateArgIndex() {
         return 1;
      }

      public boolean run(TestContext context) {
         BusObjectEntity bus = busAt(context, 1);
         if (bus == null) {
            context.fail("transfer: no bus at " + context.arg(1) + "," + context.arg(2));
            return false;
         }

         int times = context.argCount() > 3 ? context.intArg(3) : 1;
         int moved = 0;
         for (int i = 0; i < times; i++) {
            moved += bus.transferOnce();
         }

         context.info("moved " + moved);
         return true;
      }
   }

   /** How many rules a bus holds, so a test can assert that loading a world brought them back. */
   /**
    * What a bus's filter says about one item: whether it may move, and how much the network should hold.
    *
    * <p>Note the coordinate index is 2, not 1: an expectation's arguments are shifted by the word
    * {@code expect}.
    */
   private static final class BusFilterExpectation implements TestVerb, TestQuery {
      public String name() {
         return "busfilter";
      }

      public String usage() {
         return "expect busfilter <dx> <dy> <item> allowed|denied";
      }

      public int coordinateArgIndex() {
         return 2;
      }

      public void query(TestContext context, Json.Writer out) {
         BusObjectEntity bus = busAt(context, 2);
         Item item = ItemRegistry.getItem(context.arg(4));
         if (bus == null || item == null) {
            out.bool("allowed", false);
            out.num("target", -1);
            return;
         }

         out.bool("allowed", bus.filter.isItemAllowed(item));
         out.num("target", bus.networkShouldHold(item));

         // Everything the filter allows, not just the asked-about item. Added after a diagnostic that
         // watched one hard-coded item twice failed to see what was actually being edited.
         List<String> allowed = new ArrayList<>();
         for (Item candidate : ItemRegistry.getItems()) {
            if (candidate != null && bus.filter.isItemAllowed(candidate)) {
               allowed.add(candidate.getStringID() + "=" + bus.networkShouldHold(candidate));
            }
         }

         out.num("allowedcount", allowed.size());
         out.strings("allowedlist", allowed.subList(0, Math.min(12, allowed.size())));
      }

      public boolean run(TestContext context) {
         BusObjectEntity bus = busAt(context, 2);
         Item item = ItemRegistry.getItem(context.arg(4));
         if (bus == null || item == null) {
            context.fail("expect busfilter: no bus or no such item at " + context.arg(2) + "," + context.arg(3));
            return false;
         }

         boolean allowed = bus.filter.isItemAllowed(item);
         boolean wanted = "allowed".equals(context.arg(5));
         return context.check(allowed == wanted, "busfilter " + context.arg(4) + " " + context.arg(5),
               "expected " + context.arg(5) + ", found " + (allowed ? "allowed" : "denied"));
      }
   }

   /**
    * How many of an item an ordinary container at a tile holds.
    *
    * <p>Exists because this mod replaces the harness's generic {@code expect item} with a network-wide
    * reading, which is right for the terminal and leaves no way to look inside a plain chest — and the
    * buses are precisely about what crosses between a chest and the network, so both sides have to be
    * readable. Asks for {@code OEInventory}, so it works on any container in the game.
    */
   private static final class ContainerItemExpectation implements TestVerb, TestQuery {
      public String name() {
         return "container";
      }

      public String usage() {
         return "expect container <dx> <dy> <item> <count>";
      }

      public int coordinateArgIndex() {
         return 2;
      }

      public void query(TestContext context, Json.Writer out) {
         out.num("count", count(context, 2, context.arg(4)));
      }

      public boolean run(TestContext context) {
         int found = count(context, 2, context.arg(4));
         int wanted = context.intArg(5);
         return context.check(found == wanted, "container " + context.arg(4) + " = " + wanted,
               "expected " + wanted + ", found " + found);
      }

      private static int count(TestContext context, int argIndex, String itemStringID) {
         ObjectEntity entity = context.level.entityManager.getObjectEntity(
            context.tileX(context.intArg(argIndex)), context.tileY(context.intArg(argIndex + 1)));
         if (!(entity instanceof OEInventory)) {
            return -1;
         }

         Inventory inventory = ((OEInventory)entity).getInventory();
         return inventory == null ? -1 : BusObjectEntity.countIn(inventory, itemStringID);
      }
   }

   /** The bus at a tile, or null with no message: callers report their own verb's name. */
   private static BusObjectEntity busAt(TestContext context, int argIndex) {
      ObjectEntity entity = context.level.entityManager.getObjectEntity(
         context.tileX(context.intArg(argIndex)), context.tileY(context.intArg(argIndex + 1)));
      return entity instanceof BusObjectEntity ? (BusObjectEntity)entity : null;
   }

   private static final class BenchVerb implements TestVerb {
      /** A frame has 16.67ms for everything, so the interface's own share must be a small part of it. */
      private static final double BUDGET_MS = 2.0;

      public String name() {
         return "bench";
      }

      public String usage() {
         return "bench <dx> <dy> <units> [iterations]";
      }

      public int coordinateArgIndex() {
         return 1;
      }

      public boolean run(TestContext context) {
         int x = context.tileX(context.intArg(1));
         int y = context.tileY(context.intArg(2));
         int wantedUnits = context.intArg(3);
         int iterations = context.argCount() > 4 ? context.intArg(4) : 200;

         StorageTerminalObjectEntity terminal = terminalAt(context, 1);
         if (terminal == null) {
            context.fail("bench: no terminal at " + context.arg(1) + "," + context.arg(2));
            return false;
         }

         // Stackable, non-placeable items only: an object item would build scenery instead of
         // filling a slot, and unstackable items cap every slot at 1 and understate the load.
         List<String> pool = new ArrayList<>();
         for (Item item : ItemRegistry.getItems()) {
            if (item != null && item.getStringID() != null && item.getStackSize() > 1) {
               pool.add(item.getStringID());
            }
         }

         if (pool.isEmpty()) {
            context.fail("bench: no stackable items in the registry");
            return false;
         }

         int unitObjectID = ObjectRegistry.getObjectID(ArcaneStorage.UNIT_STRING_ID);
         int placed = 0;
         int distinct = 0;

         for (int i = 0; placed < wantedUnits; i++) {
            // A solid rectangle beside the terminal, so every unit links by adjacency alone and the
            // walk has the widest possible frontier -- the worst case for discovery too.
            int unitX = x + 1 + i % 8;
            int unitY = y + i / 8;

            context.level.setObject(unitX, unitY, unitObjectID);
            ObjectEntity entity = context.level.entityManager.getObjectEntity(unitX, unitY);
            if (!(entity instanceof NetworkStorage)) {
               continue;
            }

            NetworkStorage unit = (NetworkStorage)entity;
            for (int slot = 0; slot < unit.getInventory().getSize(); slot++) {
               // Distinct items are what aggregation scales on: a network holding one item type in
               // every slot is cheap no matter how large it is.
               unit.getInventory().setItem(slot, new InventoryItem(pool.get(distinct++ % pool.size()), 1));
            }

            placed++;
         }

         // Membership is recomputed from layout on every call, so this measures discovery honestly
         // rather than a cached result.
         List<NetworkStorage> units = terminal.getLinkedUnits();

         // Warm up first: the first calls pay for classloading and JIT, and reporting that as the
         // per-frame cost would overstate it by an order of magnitude.
         for (int i = 0; i < 20; i++) {
            NetworkContents.aggregate(context.level, units, StorageTerminalContainer.AGGREGATE_PURPOSE);
         }

         long start = System.nanoTime();
         int lastSize = 0;
         for (int i = 0; i < iterations; i++) {
            lastSize = NetworkContents.aggregate(
                  context.level, units, StorageTerminalContainer.AGGREGATE_PURPOSE).size();
         }
         double perCall = (System.nanoTime() - start) / (double)iterations / 1_000_000.0;

         context.info("BENCH units=" + units.size()
               + " slots=" + NetworkContents.totalSlots(units)
               + " distinct=" + lastSize
               + " aggregate=" + String.format(Locale.ROOT, "%.3f", perCall) + "ms"
               + " (" + String.format(Locale.ROOT, "%.1f", perCall / 16.67 * 100.0) + "% of a 60fps frame)");

         return context.check(perCall <= BUDGET_MS, "bench: aggregation within budget",
               "aggregation costs " + String.format(Locale.ROOT, "%.3f", perCall)
                  + "ms, over the " + BUDGET_MS + "ms budget -- this needs caching rather than tuning");
      }
   }

   /**
    * {@code expect inuse <dx> <dy> <n>} -- how many players the terminal believes are using it.
    *
    * <p>Exists to make a multiplayer question answerable with one client. A terminal shows itself as
    * open through vanilla's {@code OEUsers}, which the object entity writes into its content packet,
    * so *other* players seeing it open depends entirely on this server-side count being right.
    * Verifying the count therefore verifies everything except the sprite.
    *
    * <p>Worth knowing why it can go stale rather than wrong: a user entry expires after two seconds
    * unless refreshed, so the container re-asserts it every tick, exactly as vanilla's chest
    * container does. A test that opens and immediately asserts would pass even if that refresh were
    * missing -- which is why the scenario waits before checking.
    */
   private static final class InUseExpectation implements TestVerb, TestQuery {
      public String name() {
         return "inuse";
      }

      public String usage() {
         return "expect inuse <dx> <dy> <count>";
      }

      public void query(TestContext context, Json.Writer out) {
         StorageTerminalObjectEntity terminal = terminalAt(context, 2);
         out.num("users", terminal == null ? -1 : terminal.getTotalUsers())
            .bool("inUse", terminal != null && terminal.isInUse());
      }

      public int coordinateArgIndex() {
         return 2;
      }

      public boolean run(TestContext context) {
         StorageTerminalObjectEntity terminal = terminalAt(context, 2);
         if (terminal == null) {
            return noTerminal(context);
         }

         int wanted = context.intArg(4);
         return context.check(terminal.getTotalUsers() == wanted, "inuse = " + wanted,
               "expected " + wanted + " user(s), found " + terminal.getTotalUsers());
      }
   }

   /** {@code expect units <dx> <dy> <n>} -- how many units the terminal's walk reaches. */
   private static final class UnitsExpectation implements TestVerb, TestQuery {
      public String name() {
         return "units";
      }

      public String usage() {
         return "expect units <dx> <dy> <count>";
      }

      public void query(TestContext context, Json.Writer out) {
         List<NetworkStorage> units = unitsFrom(context);
         out.num("units", units == null ? -1 : units.size());
      }

      public int coordinateArgIndex() {
         return 2;
      }

      public boolean run(TestContext context) {
         List<NetworkStorage> units = unitsFrom(context);
         if (units == null) {
            return noTerminal(context);
         }

         int wanted = context.intArg(4);
         return context.check(units.size() == wanted, "units = " + wanted,
               "expected " + wanted + ", found " + units.size());
      }
   }

   /**
    * {@code expect item <dx> <dy> <itemStringID> <n>} -- the aggregate the terminal shows.
    *
    * <p>Replaces the harness's built-in {@code item}, which counts the inventory of the tile itself. A
    * terminal has no inventory of its own; the number that matters is what its network can see.
    */
   private static final class NetworkItemExpectation implements TestVerb, TestQuery {
      public String name() {
         return "item";
      }

      public String usage() {
         return "expect item <dx> <dy> <itemStringID> <count>";
      }

      public void query(TestContext context, Json.Writer out) {
         List<NetworkStorage> units = unitsFrom(context);
         out.str("item", context.arg(4))
            .num("count", units == null ? -1 : NetworkContents.totalOf(units, context.arg(4)));
      }

      public int coordinateArgIndex() {
         return 2;
      }

      public boolean run(TestContext context) {
         List<NetworkStorage> units = unitsFrom(context);
         if (units == null) {
            return noTerminal(context);
         }

         String itemID = context.arg(4);
         int wanted = context.intArg(5);
         int actual = 0;

         for (InventoryItem item : NetworkContents.aggregate(
               context.level, units, StorageTerminalContainer.AGGREGATE_PURPOSE)) {
            if (item.item.getStringID().equals(itemID)) {
               actual += item.getAmount();
            }
         }

         return context.check(actual == wanted, "item " + itemID + " = " + wanted,
               "expected " + wanted + ", found " + actual);
      }
   }

   /** {@code expect capacity <dx> <dy> <used> <total>}. */
   private static final class CapacityExpectation implements TestVerb, TestQuery {
      public String name() {
         return "capacity";
      }

      public String usage() {
         return "expect capacity <dx> <dy> <usedSlots> <totalSlots>";
      }

      public void query(TestContext context, Json.Writer out) {
         List<NetworkStorage> units = unitsFrom(context);
         out.num("used", units == null ? -1 : NetworkContents.usedSlots(units))
            .num("total", units == null ? -1 : NetworkContents.totalSlots(units));
      }

      public int coordinateArgIndex() {
         return 2;
      }

      public boolean run(TestContext context) {
         List<NetworkStorage> units = unitsFrom(context);
         if (units == null) {
            return noTerminal(context);
         }

         int wantedUsed = context.intArg(4);
         int wantedTotal = context.intArg(5);
         int used = NetworkContents.usedSlots(units);
         int total = NetworkContents.totalSlots(units);

         return context.check(used == wantedUsed && total == wantedTotal,
               "capacity = " + wantedUsed + "/" + wantedTotal + " slots",
               "expected " + wantedUsed + "/" + wantedTotal + ", found " + used + "/" + total);
      }
   }

   /** {@code expect fits <dx> <dy> <itemStringID> <true|false>}. */
   private static final class FitsExpectation implements TestVerb, TestQuery {
      public String name() {
         return "fits";
      }

      public String usage() {
         return "expect fits <dx> <dy> <itemStringID> <true|false>";
      }

      public void query(TestContext context, Json.Writer out) {
         StorageTerminalObjectEntity terminal = terminalAt(context, 2);
         List<NetworkStorage> units = unitsFrom(context);
         out.str("item", context.arg(4)).bool("fits", units != null && NetworkContents.canFit(
            context.level, units, new InventoryItem(context.arg(4)),
            StorageTerminalContainer.DEPOSIT_PURPOSE));
      }

      public int coordinateArgIndex() {
         return 2;
      }

      public boolean run(TestContext context) {
         List<NetworkStorage> units = unitsFrom(context);
         if (units == null) {
            return noTerminal(context);
         }

         String itemID = context.arg(4);
         boolean wanted = Boolean.parseBoolean(context.arg(5));
         boolean fits = NetworkContents.canFit(context.level, units, new InventoryItem(itemID),
               StorageTerminalContainer.AGGREGATE_PURPOSE);

         return context.check(fits == wanted, "fits " + itemID + " = " + wanted,
               "expected " + wanted + ", found " + fits);
      }
   }

   /**
    * {@code expect mask <dx> <dy> <bitmask>} -- which neighbours a conduit joins.
    *
    * <p>Addresses a tile rather than an object entity, because a conduit has none.
    */
   private static final class MaskExpectation implements TestVerb, TestQuery {
      public String name() {
         return "mask";
      }

      public String usage() {
         return "expect mask <dx> <dy> <bitmask: north 1, east 2, south 4, west 8>";
      }

      public void query(TestContext context, Json.Writer out) {
         out.num("mask", StorageConduitObject.connectionMask(
            context.level, context.tileX(context.intArg(2)), context.tileY(context.intArg(3))));
      }

      public int coordinateArgIndex() {
         return 2;
      }

      public boolean run(TestContext context) {
         int x = context.tileX(context.intArg(2));
         int y = context.tileY(context.intArg(3));
         int wanted = context.intArg(4);
         int mask = StorageConduitObject.connectionMask(context.level, x, y);

         return context.check(mask == wanted,
               "mask at " + context.arg(2) + "," + context.arg(3) + " = " + wanted,
               "expected " + wanted + ", found " + mask);
      }
   }

   /**
    * {@code expect total <itemStringID> <n>} -- every storage unit on the level.
    *
    * <p>Replaces the harness's built-in {@code total}, which sums every inventory on the level. That
    * is the better default for a mod that does not own its containers, but here it would also
    * count vanilla chests and mask the thing being tested: whether an action conserved items
    * inside the network.
    */
   private static final class NetworkTotalExpectation implements TestVerb, TestQuery {
      public String name() {
         return "total";
      }

      public String usage() {
         return "expect total <itemStringID> <count>";
      }

      public void query(TestContext context, Json.Writer out) {
         out.str("item", context.arg(2))
            .num("count", NetworkContents.totalOf(allUnits(context.level), context.arg(2)));
      }

      public boolean run(TestContext context) {
         String itemID = context.arg(2);
         int wanted = context.intArg(3);
         int actual = NetworkContents.totalOf(allUnits(context.level), itemID);

         return context.check(actual == wanted, "total " + itemID + " = " + wanted,
               "expected " + wanted + ", found " + actual);
      }
   }

   static List<NetworkStorage> allUnits(Level level) {
      java.util.ArrayList<NetworkStorage> units = new java.util.ArrayList<>();

      for (ObjectEntity entity : level.entityManager.objectEntities) {
         if (entity instanceof NetworkStorage && !entity.removed()) {
            units.add((NetworkStorage)entity);
         }
      }

      return units;
   }
}
