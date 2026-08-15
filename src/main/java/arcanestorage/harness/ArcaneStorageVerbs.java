package arcanestorage.harness;

import java.awt.Point;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import arcanestorage.ArcaneStorage;
import arcanestorage.object.UnitTier;
import arcanestorage.upgrade.UnitUpgrade;
import arcanestorage.container.StorageTerminalContainer;
import arcanestorage.network.NetworkContents;
import arcanestorage.object.StorageConduitObject;
import arcanestorage.container.BusContainer;
import arcanestorage.objectentity.BusSummary;
import arcanestorage.objectentity.BusObjectEntity;
import necesse.inventory.item.ItemCategory;
import necesse.inventory.itemFilter.ItemCategoriesFilter;
import arcanestorage.objectentity.StorageTerminalObjectEntity;
import arcanestorage.network.NetworkConductor;
import arcanestorage.network.IndexedInventories;
import arcanestorage.network.NetworkIndex;
import arcanestorage.network.NetworkIndexes;
import arcanestorage.network.NetworkScheduler;
import arcanestorage.network.NetworkStations;
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
import necesse.level.maps.regionSystem.RegionManager;
import necesseheadlessharness.Harness;
import necesseheadlessharness.Ticks;
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
      Harness.registerObjectAlias("stationunit", ArcaneStorage.STATION_UNIT_STRING_ID);

      // One alias per rung, so a tier test names the tier rather than reciting a string ID. The base tier
      // keeps its short aliases above, since the great majority of tests do not care about tiering.
      for (UnitTier tier : UnitTier.values()) {
         if (tier.below() != null) {
            Harness.registerObjectAlias(tier.suffix + "unit", tier.storageId());
            Harness.registerObjectAlias(tier.suffix + "stationunit", tier.stationId());
         }
      }
      // The transceiver ladder. 'transceiver' is the bottom rung, matching how 'unit' names the units' bottom rung,
      // so a test that does not care about tiering never mentions one.
      for (UnitTier tier : UnitTier.values()) {
         if (!tier.hasWireless()) {
            continue;
         }

         Harness.registerObjectAlias(
               tier == UnitTier.WIRELESS_BOTTOM ? "transceiver" : tier.suffix + "transceiver",
               tier.transceiverId());
      }

      Harness.registerObjectAlias("conduit", ArcaneStorage.CONDUIT_STRING_ID);
      Harness.registerObjectAlias("importbus", ArcaneStorage.IMPORT_BUS_STRING_ID);
      Harness.registerObjectAlias("exportbus", ArcaneStorage.EXPORT_BUS_STRING_ID);

      Harness.registerVerb(new ReportVerb());
      Harness.registerVerb(new ResetVerb());
      Harness.registerVerb(new IndexPoisonVerb());
      Harness.registerVerb(new HaulVerb());
      Harness.registerVerb(new BusApplyVerb());
      Harness.registerVerb(new TerminalRulesVerb());
      Harness.registerVerb(new BusNameVerb());
      Harness.registerExpectation(new BusNameQuery());
      Harness.registerExpectation(new BusesQuery());
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
      Harness.registerExpectation(new BusStateQuery());
      Harness.registerExpectation(new TerminalProblemsQuery());
      Harness.registerExpectation(new IndexDriftQuery());
      Harness.registerVerb(new UpgradeVerb());
      Harness.registerExpectation(new UpgradeQuery());
      Harness.registerExpectation(new PlayerItemQuery());
      Harness.registerExpectation(new HookQuery());
      Harness.registerVerb(new RuleGlobalVerb());
      Harness.registerVerb(new RuleCategoryVerb());
      Harness.registerExpectation(new BusOpenPacketQuery());

      Harness.registerExpectation(new UnitsExpectation());
      Harness.registerExpectation(new StationsExpectation());
      Harness.registerExpectation(new InUseExpectation());
      Harness.registerExpectation(new NetworkItemExpectation());
      Harness.registerExpectation(new CapacityExpectation());
      Harness.registerExpectation(new FitsExpectation());
      Harness.registerExpectation(new MaskExpectation());
      Harness.registerExpectation(new NetworkTotalExpectation());
      Harness.registerExpectation(new BusFilterExpectation());
      Harness.registerExpectation(new ContainerItemExpectation());
      Harness.registerVerb(new PairVerb());
      Harness.registerVerb(new OpenRemoteVerb());
      Harness.registerExpectation(new BindingQuery());
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

   /**
    * {@code pair <dx> <dy>} -- puts a wireless terminal in the player's bag, bound to the terminal at that tile.
    *
    * <p>Binds directly rather than simulating the click. What the click adds is
    * {@code ItemInteractAction} plumbing -- range, animation, which tile the cursor was over -- and none of that
    * is what these tests are about; the binding, the resolution and the container are. The click path is
    * therefore one of the things that still needs a human in the game.
    */
   private static final class PairVerb implements TestVerb {
      public String name() {
         return "pair";
      }

      public String usage() {
         return "pair <dx> <dy> [demonic|tungsten|fallen]";
      }

      public int coordinateArgIndex() {
         return 1;
      }

      public boolean needsPlayer() {
         return true;
      }

      public boolean run(TestContext context) {
         int x = context.tileX(context.intArg(1));
         int y = context.tileY(context.intArg(2));

         // A tier may be named, because the reach a pairing grants is the lower of the two ends and a test about
         // range has to be able to set both. Unnamed means the bottom rung, which is what most tests want.
         UnitTier tier = UnitTier.WIRELESS_BOTTOM;
         if (context.args.size() > 3) {
            String named = context.args.get(3).toLowerCase(java.util.Locale.ROOT);
            tier = null;

            for (UnitTier candidate : UnitTier.values()) {
               if (candidate.hasWireless() && candidate.name().toLowerCase(java.util.Locale.ROOT).equals(named)) {
                  tier = candidate;
               }
            }

            if (tier == null) {
               context.fail("'" + named + "' is not a wireless tier");
               return false;
            }
         }

         // Rebinds the one already carried rather than handing out another, which is what using the item on a
         // second transceiver does. Adding one per call also made a test read a stale binding off the first copy.
         // A named tier that differs from what is carried replaces it, since the carried rung is the thing under
         // test.
         necesse.inventory.InventoryItem item = findWireless(context);
         if (item != null && ((arcanestorage.remote.WirelessTerminalItem)item.item).tier != tier) {
            context.client.playerMob.getInv().main.removeItems(context.level, context.client.playerMob,
                  item.item, item.getAmount(), "harnesspair");
            item = null;
         }

         if (item == null) {
            item = new necesse.inventory.InventoryItem(tier.wirelessTerminalId(), 1);
            new arcanestorage.remote.RemoteBinding(context.level, x, y).write(item);
            context.client.playerMob.getInv().addItem(item, true, "harnesspair");
            return true;
         }

         new arcanestorage.remote.RemoteBinding(context.level, x, y).write(item);
         return true;
      }
   }

   /**
    * {@code openremote} -- opens the network through the wireless terminal in the player's bag.
    *
    * <p>Deliberately does not move the player, which is the difference that matters: the {@code open} verb
    * stands the player on the target first, because a local container closes itself when nobody is in range.
    */
   private static final class OpenRemoteVerb implements TestVerb {
      public String name() {
         return "openremote";
      }

      public String usage() {
         return "openremote";
      }

      public boolean needsPlayer() {
         return true;
      }

      public boolean run(TestContext context) {
         necesse.inventory.InventoryItem held = findWireless(context);
         if (held == null) {
            context.fail("'openremote' needs a paired wireless terminal; run 'pair <dx> <dy>' first");
            return false;
         }

         arcanestorage.remote.RemoteBinding binding = arcanestorage.remote.RemoteBinding.read(held);
         arcanestorage.remote.RemoteTerminal.Resolved resolved =
            arcanestorage.remote.RemoteTerminal.resolve(context.client, binding);
         if (!resolved.ok()) {
            context.fail("the paired transceiver could not be resolved: " + resolved.result);
            return false;
         }

         // The same gate a right-click applies, so a scenario can assert a refusal rather than only a success. It is
         // applied here rather than left to the container because the container's check runs a tick later, and a
         // test that opened and was closed again looks like a test that never opened.
         arcanestorage.remote.Reach.Decision decision = arcanestorage.remote.Reach.check(
               context.client.playerMob, ((arcanestorage.remote.WirelessTerminalItem)held.item).tier,
               resolved.tier(), binding.levelID, binding.tileX, binding.tileY);
         if (!decision.ok()) {
            context.fail("out of reach: " + decision.verdict + " (distance " + decision.distance
                  + ", limit " + decision.limit + ")");
            return false;
         }

         arcanestorage.remote.RemoteTerminalContainer.openAndSend(context.client, binding, resolved);
         return true;
      }
   }

   /** {@code query binding} -- what the wireless terminal in the bag is paired to, if anything. */
   private static final class BindingQuery implements TestVerb, TestQuery {
      public String name() {
         return "binding";
      }

      public String usage() {
         return "query binding";
      }

      public boolean run(TestContext context) {
         return true;
      }

      public void query(TestContext context, Json.Writer out) {
         necesse.inventory.InventoryItem held = findWireless(context);
         arcanestorage.remote.RemoteBinding binding =
            held == null ? null : arcanestorage.remote.RemoteBinding.read(held);

         out.bool("carried", held != null);
         out.bool("paired", binding != null);
         out.str("level", binding == null ? "none" : binding.levelID);
         out.num("x", binding == null ? -1 : binding.tileX);
         out.num("y", binding == null ? -1 : binding.tileY);
         out.bool("remoteopen",
            context.client.getContainer() instanceof arcanestorage.remote.RemoteTerminalContainer);
      }
   }

   /** The first wireless terminal in the player's main inventory, or null. */
   static necesse.inventory.InventoryItem findWireless(TestContext context) {
      necesse.inventory.Inventory inventory = context.client.playerMob.getInv().main;
      for (int slot = 0; slot < inventory.getSize(); slot++) {
         necesse.inventory.InventoryItem item = inventory.getItem(slot);
         if (item != null && item.item instanceof arcanestorage.remote.WirelessTerminalItem) {
            return item;
         }
      }

      return null;
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

      /** How far from spawn the sweep for objects without an object entity reaches. */
      private static final int CONDUCTOR_SWEEP = 16;

      public boolean run(TestContext context) {
         ArrayList<Point> ours = new ArrayList<>();

         for (ObjectEntity entity : context.level.entityManager.objectEntities) {
            if (!entity.removed()
                  && (entity instanceof NetworkStorage
                     || entity instanceof NetworkStations
                     || entity instanceof StorageTerminalObjectEntity
                     || entity instanceof BusObjectEntity)) {
               ours.add(new Point(entity.tileX, entity.tileY));
            }
         }

         // Indexes are derived from a layout that is about to stop existing, and setObject below does not
         // run the destroy hook that would normally invalidate them.
         NetworkIndexes.forget();

         // Regions first, or the sweep below reads an unloaded region as empty and leaves everything in it
         // standing. That is not hypothetical: a saved world boots with no regions loaded, so the first test
         // of a session inherited the previous session's chests, and the chest sitting on a tile silently
         // blocked a bus placement -- which removed one side of a conflict and made a test fail depending on
         // what had run before it. Loading from a verb is safe because verbs run on the server thread.
         int bits = RegionManager.REGION_SIZE_BITS;
         for (int regionY = context.tileY(-CONDUCTOR_SWEEP) >> bits;
               regionY <= context.tileY(CONDUCTOR_SWEEP) >> bits; regionY++) {
            for (int regionX = context.tileX(-CONDUCTOR_SWEEP) >> bits;
                  regionX <= context.tileX(CONDUCTOR_SWEEP) >> bits; regionX++) {
               context.level.regionManager.getRegion(regionX, regionY, true);
            }
         }

         // Buses used to survive a reset, which made a test's result depend on what the previous test had
         // configured: a stale import bus kept its ceiling, and a conflict that should have been detected was
         // not. Conduits have no object entity at all, so they need a sweep rather than a scan -- bounded,
         // since only a test ever places one and every test places near spawn.
         for (int dx = -CONDUCTOR_SWEEP; dx <= CONDUCTOR_SWEEP; dx++) {
            for (int dy = -CONDUCTOR_SWEEP; dy <= CONDUCTOR_SWEEP; dy++) {
               int x = context.tileX(dx);
               int y = context.tileY(dy);
               if (context.level.getObject(x, y) instanceof NetworkConductor) {
                  ours.add(new Point(x, y));
                  continue;
               }

               // Containers a test placed. Not ours, but leaving them standing made results depend on test
               // order: a chest left at a tile silently blocked the next test's bus, so the layout under test
               // was not the layout described, and a conflict went undetected because one bus was missing.
               ObjectEntity at = context.level.entityManager.getObjectEntity(x, y);
               if (at instanceof OEInventory && !(at instanceof NetworkStorage)) {
                  ours.add(new Point(x, y));
               }
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

         // Sockets live on Station Units now, so this installs into the network's sockets rather than into
         // the terminal. Walks the units in the same tile order the container addresses them in, so a test
         // that installs two benches knows which socket each landed in.
         List<NetworkStations> units = terminal.getLinkedStationUnits();
         if (units.isEmpty()) {
            context.fail("no Station Unit on this network -- place one before installing " + context.arg(1));
            return false;
         }

         InventoryItem item = new InventoryItem(context.arg(1), 1);
         boolean added = false;
         for (NetworkStations unit : units) {
            added = unit.getInventory().addItem(
                  terminal.getLevel(), context.client.playerMob, item, "arcanestorageinstall", null);
            if (added && item.getAmount() == 0) {
               break;
            }
         }

         if (!added || item.getAmount() > 0) {
            context.fail("the station units refused to install " + context.arg(1)
                  + " -- either every socket is full or it needs its own placement");
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
            bus.rulesChanged();
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

         bus.rulesChanged();
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

         // How many networks are indexed, and how many rebuilds the sharing did not avoid. Both are here so a
         // test can assert that cost is a property of the network rather than of how many devices watch it.
         out.num("indexes", NetworkIndexes.indexedOn(context.level));
         out.num("rebuilds", NetworkIndex.rebuilds);
         out.num("driftslots", NetworkIndex.driftScans);

         // The scheduler's own state, because "nothing moved" has several very different causes: nothing was
         // dirty, nothing led the network, the budget ran out, or an item was given up on.
         out.num("scheduled", NetworkScheduler.scheduled);
         out.num("deferred", NetworkScheduler.deferred);
         out.num("resolves", NetworkScheduler.resolves);
         out.num("tick", context.level.getWorldEntity() == null ? -1
               : context.level.getWorldEntity().getGameTicks());

         int pending = 0;
         int stalled = 0;
         int leaders = 0;
         for (NetworkIndex index : NetworkIndexes.on(context.level)) {
            pending += index.scheduler().pending();
            stalled += index.scheduler().stalledCount();
            leaders += index.scheduler().hasLeader() ? 1 : 0;
         }

         out.num("pending", pending);
         out.num("stalled", stalled);
         out.num("led", leaders);
         out.num("nocache", BusObjectEntity.walkedNoCache);
         out.num("stale", BusObjectEntity.walkedStale);
         out.num("notmember", BusObjectEntity.walkedNotMember);
      }
   }

   /**
    * Whether a bus is working, and why not when it is not.
    *
    * <p>{@code query busstate <dx> <dy>} -> {@code {state, reason, conflictitem}}. The state is derived on
    * the server tick rather than on demand, so a test that wants a fresh answer should let time pass first.
    */
   private static final class BusStateQuery implements TestVerb, TestQuery {
      public String name() {
         return "busstate";
      }

      public String usage() {
         return "query busstate <dx> <dy>";
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
            out.str("state", "nobus");
            out.str("reason", "");
            return;
         }

         out.str("state", bus.getState().name().toLowerCase(java.util.Locale.ROOT));
         out.str("reason", bus.stateMessage());

         // What the predicate had to work with. Added while diagnosing a conflict that went undetected
         // after a restart: the state alone cannot say whether the peer walk or the comparison was at fault.
         List<ObjectEntity> peers = new ArrayList<>();
         int members = bus.network(peers).size();
         out.num("members", members);
         out.num("peers", peers.size());
         out.str("evaluation", bus.describeEvaluation());
         out.bool("container", bus.attachedContainer() != null);

         // The predicate's two numbers for a probe item, when one is named: ceiling from the importer,
         // floor from the exporter. A conflict is a ceiling strictly above a floor.
         if (context.argCount() > 4) {
            Item probe = ItemRegistry.getItem(context.arg(4));
            if (probe != null) {
               out.num("target", bus.networkShouldHold(probe));
               out.bool("allows", bus.filter.isItemAllowed(probe));
               for (ObjectEntity found : peers) {
                  if (found == bus || !(found instanceof BusObjectEntity)) {
                     continue;
                  }

                  BusObjectEntity peer = (BusObjectEntity)found;
                  out.bool("peerallows", peer.filter.isItemAllowed(probe));
                  out.num("peertarget", peer.networkShouldHold(probe));
                  out.bool("sharescontainer", peer.attachedContainer() == bus.attachedContainer());
                  break;
               }
            }
         }
      }
   }

   /**
    * What the terminal reports about stopped devices on its network.
    *
    * <p>{@code query problems <dx> <dy>} -> {@code {where, count}}. Computed only while the terminal is in
    * use, so a test must open it first -- which is the behaviour being asserted, not an inconvenience: an
    * unattended terminal walking its network on a timer is the polling this whole design removes.
    */
   private static final class TerminalProblemsQuery implements TestVerb, TestQuery {
      public String name() {
         return "problems";
      }

      public String usage() {
         return "query problems <dx> <dy>";
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
         if (!(entity instanceof StorageTerminalObjectEntity)) {
            out.str("where", "");
            out.num("count", -1);
            return;
         }

         StringBuilder where = new StringBuilder();
         int count = 0;
         for (BusSummary summary : ((StorageTerminalObjectEntity)entity).getBuses()) {
            if (summary.state.isActive()) {
               continue;
            }

            if (count > 0) {
               where.append(' ');
            }

            where.append(summary.where());
            count++;
         }

         out.str("where", where.toString());
         out.num("count", count);
      }
   }

   /**
    * Whether the index still agrees with what the units hold.
    *
    * <p>{@code query indexdrift} -> {@code {drift, indexes}}. Zero drift is agreement. The point of a query
    * rather than an assertion inside the mod is that a test can induce drift deliberately and watch it be
    * caught, which is the only way to know the reconciliation works.
    */
   private static final class IndexDriftQuery implements TestVerb, TestQuery {
      public String name() {
         return "indexdrift";
      }

      public String usage() {
         return "query indexdrift";
      }

      public boolean run(TestContext context) {
         return true;
      }

      public void query(TestContext context, Json.Writer out) {
         int drift = 0;
         for (NetworkIndex index : NetworkIndexes.on(context.level)) {
            drift += index.driftAgainstWorld();
         }

         out.num("drift", drift);
         out.num("indexes", NetworkIndexes.indexedOn(context.level));
      }
   }

   /**
    * Whether the change hook is woven in, and how much traffic it has seen.
    *
    * <p>{@code query hook} -> {@code {applied, notifications, relevant, watched}}. Asserted by a test rather
    * than trusted, because a patch that silently fails to apply leaves an index believing in items that are
    * gone, and nothing anywhere says so. The notification count also shows the cost of being on that path: it
    * counts every inventory change in the game, ours or not.
    */
   private static final class HookQuery implements TestVerb, TestQuery {
      public String name() {
         return "hook";
      }

      public String usage() {
         return "query hook";
      }

      public boolean run(TestContext context) {
         return true;
      }

      public void query(TestContext context, Json.Writer out) {
         out.bool("applied", IndexedInventories.hookWorks());
         out.num("notifications", IndexedInventories.notifications);
         out.num("relevant", IndexedInventories.relevant);
         out.num("watched", IndexedInventories.watched());
         out.num("resyncs", NetworkIndex.resyncs);
      }
   }

   /**
    * Makes an index wrong on purpose, so the reconciliation can be watched catching it.
    *
    * <p>{@code indexpoison <itemStringID> <delta>}. There is no other way to test a safety net: waiting for a
    * real bug to appear is not a test, and asserting the net exists is not the same as knowing it works. The
    * poison is applied to the counts only, so the world stays consistent and the drift is exactly the delta.
    */
   private static final class IndexPoisonVerb implements TestVerb {
      public String name() {
         return "indexpoison";
      }

      public String usage() {
         return "indexpoison <itemStringID> <delta>";
      }

      public boolean run(TestContext context) {
         Item item = ItemRegistry.getItem(context.arg(1));
         if (item == null) {
            return context.check(false, "poison " + context.arg(1), "no such item");
         }

         int delta = context.intArg(2);
         int poisoned = 0;
         for (NetworkIndex index : NetworkIndexes.on(context.level)) {
            index.changed(item, delta);
            poisoned++;
         }

         context.info("poisoned " + poisoned + " index(es) with " + delta + " " + context.arg(1));
         return poisoned > 0;
      }
   }

   /**
    * Plays the part of something outside the network that keeps undoing its work.
    *
    * <p>{@code haul <dx> <dy> <itemStringID> <amountPerTick> <ticks>} -- every tick, moves that much of the item
    * out of the network's units and back into the container at those coordinates.
    *
    * <p>This exists because the churn backstop cannot be provoked any other way. A cycle built only from our own
    * devices always has one container with both an import and an export bus on it, which the static check sees
    * and stops before anything moves. The loops the backstop is actually for are closed by somebody else -- a
    * settler hauling to a priority container, a hopper, another mod's pipe -- and none of those exist in a
    * scenario. So a scenario has to play the settler.
    */
   private static final class HaulVerb implements TestVerb {
      public String name() {
         return "haul";
      }

      public String usage() {
         return "haul <dx> <dy> <itemStringID> <amountPerTick> <ticks>";
      }

      public int coordinateArgIndex() {
         return 1;
      }

      public boolean run(TestContext context) {
         int x = context.tileX(context.intArg(1));
         int y = context.tileY(context.intArg(2));
         Item item = ItemRegistry.getItem(context.arg(3));
         int perTick = context.intArg(4);
         int ticks = context.intArg(5);
         Level level = context.level;

         if (item == null) {
            return context.check(false, "haul " + context.arg(3), "no such item");
         }

         ObjectEntity target = level.entityManager.getObjectEntity(x, y);
         if (!(target instanceof OEInventory)) {
            return context.check(false, "haul into " + x + "," + y, "no container there");
         }

         Inventory destination = ((OEInventory)target).getInventory();
         int[] remaining = {ticks};

         Ticks.each(tickedLevel -> {
            if (remaining[0]-- <= 0) {
               return false;
            }

            int moved = 0;
            for (NetworkStorage unit : allUnits(level)) {
               Inventory from = unit.getInventory();
               for (int slot = 0; slot < from.getSize() && moved < perTick; slot++) {
                  InventoryItem inSlot = from.getItem(slot);
                  if (inSlot == null || inSlot.item != item) {
                     continue;
                  }

                  InventoryItem moving = inSlot.copy();
                  moving.setAmount(Math.min(perTick - moved, inSlot.getAmount()));
                  int wanted = moving.getAmount();
                  destination.addItem(level, null, moving, "haul", null);
                  int accepted = wanted - moving.getAmount();
                  if (accepted > 0) {
                     from.removeItems(level, null, item, accepted, "haul");
                     moved += accepted;
                  }
               }
            }

            return true;
         });

         context.info("hauling " + perTick + " " + context.arg(3) + " per tick back to " + x + "," + y
            + " for " + ticks + " ticks");
         return true;
      }
   }

   /**
    * The panel's Apply button, end to end: propose a rule set, and either have it adopted or refused whole.
    *
    * <p>{@code busapply <dx> <dy> <item> <target> <accepted|refused>}. Deliberately separate from
    * {@code busroundtrip}, which exists to exercise the packet round trip that once lost every edit. This one
    * exercises the decision: the client's copy is built from what the server would send, edited, and then judged
    * exactly as {@code SetFilterAction} judges it -- so a test can assert that a contradictory set is refused and,
    * more importantly, that none of it was applied anyway.
    */
   private static final class BusApplyVerb implements TestVerb {
      public String name() {
         return "busapply";
      }

      public String usage() {
         return "busapply <dx> <dy> <item> <target> <accepted|refused>";
      }

      public int coordinateArgIndex() {
         return 1;
      }

      public boolean run(TestContext context) {
         BusObjectEntity bus = busAt(context, 1);
         Item item = ItemRegistry.getItem(context.arg(3));
         if (bus == null || item == null) {
            context.fail("busapply: no bus or no such item");
            return false;
         }

         int target = context.intArg(4);
         String expected = context.argCount() > 5 ? context.arg(5) : "accepted";

         // As the client does it: read what the server would send on open, edit that copy, propose it back.
         Packet opened = new Packet();
         bus.filter.writePacket(new PacketWriter(opened));
         ItemCategoriesFilter proposed = new ItemCategoriesFilter(ItemCategory.masterCategory, false);
         proposed.readPacket(new PacketReader(opened));
         if (target > 0) {
            proposed.setItemAllowed(item, true, target);
         } else {
            proposed.setItemAllowed(item, true);
         }

         String refusal = bus.whyRefused(proposed);
         if (refusal == null) {
            Packet accepted = new Packet();
            proposed.writePacket(new PacketWriter(accepted));
            bus.filter.readPacket(new PacketReader(accepted));
            bus.rulesChanged();
         }

         context.info(refusal == null
            ? "applied, and the bus now reads target=" + bus.networkShouldHold(item)
            : "refused: " + refusal);

         return context.check("refused".equals(expected) == (refusal != null),
            "busapply " + context.arg(3) + " " + expected,
            refusal == null ? "it was applied" : "it was refused: " + refusal);
      }
   }

   /**
    * What the terminal's logistics tab is showing, as the terminal itself computed it.
    *
    * <p>{@code query buses <dx> <dy>} -> {@code {count, stopped, list}}, where list is
    * {@code x,y:import|export:state} per bus, space separated. The tab is client-side and no test can look at
    * it, but everything it draws comes from this one survey, so asserting the survey is asserting the tab's
    * content -- which devices it lists, in which order, and which of them it will colour red.
    */
   private static final class BusesQuery implements TestVerb, TestQuery {
      public String name() {
         return "buses";
      }

      public String usage() {
         return "buses <dx> <dy>";
      }

      public int coordinateArgIndex() {
         return 2;
      }

      /** Read-only, so there is nothing to do as a verb. */
      public boolean run(TestContext context) {
         return true;
      }

      public void query(TestContext context, Json.Writer out) {
         ObjectEntity entity = context.level.entityManager.getObjectEntity(
            context.tileX(context.intArg(2)), context.tileY(context.intArg(3)));
         if (!(entity instanceof StorageTerminalObjectEntity)) {
            out.num("count", -1);
            out.num("stopped", -1);
            out.str("list", "");
            return;
         }

         StringBuilder list = new StringBuilder();
         int stopped = 0;
         List<BusSummary> buses = ((StorageTerminalObjectEntity)entity).getBuses();
         for (BusSummary bus : buses) {
            if (list.length() > 0) {
               list.append(' ');
            }

            list.append(bus.where()).append(':').append(bus.importing ? "import" : "export")
                  .append(':').append(bus.state.name().toLowerCase())
                  .append(':').append(bus.name());
            if (!bus.state.isActive()) {
               stopped++;
            }
         }

         out.num("count", buses.size());
         out.num("stopped", stopped);
         out.str("list", list.toString());
      }
   }

   /**
    * Writing a bus's rules from the terminal, by exactly the route the logistics tab uses.
    *
    * <p>{@code terminalrules <tdx> <tdy> <bdx> <bdy> <item> <target> <accepted|refused|notfound>}. This is the
    * server half of {@code StorageTerminalContainer.SetRulesAction}: find the bus <i>and check it is on this
    * terminal's network</i>, judge the proposal, adopt it or refuse it whole. The membership check is the part
    * worth testing rather than trusting -- the tab addresses buses by coordinate, so without it a crafted
    * packet could rewrite a bus in somebody else's base.
    */
   /**
    * Renames a bus: {@code busname <dx> <dy> <name>}.
    *
    * <p>Separate from the query below because the two are reached by different commands and so read their
    * coordinates from different positions -- {@code busname 2 0} puts them at 1 and 2, {@code query busname 2 0}
    * at 2 and 3. One class serving both would have to guess which it was, and guess wrong somewhere.
    */
   private static final class BusNameVerb implements TestVerb {
      public String name() {
         return "busname";
      }

      public String usage() {
         return "busname <dx> <dy> <name>";
      }

      public int coordinateArgIndex() {
         return 1;
      }

      public boolean run(TestContext context) {
         BusObjectEntity bus = busAt(context, 1);
         if (bus == null) {
            context.fail("busname: no bus there");
            return false;
         }

         // Joined rather than taken as one argument: a scenario line is split on whitespace and a name has
         // spaces in it -- "Grain Import" is the example the feature exists for.
         StringBuilder name = new StringBuilder();
         for (int i = 3; i < context.argCount(); i++) {
            if (name.length() > 0) {
               name.append(' ');
            }

            name.append(context.arg(i));
         }

         bus.setCustomName(name.toString());
         context.info("named " + bus.name());
         return true;
      }
   }

   /**
    * A bus's name as it stands: {@code query busname <dx> <dy>} -> {@code {name, ordinal, custom}}.
    *
    * <p>The name is only a label, but the number in an assigned one is chosen by looking at the network, and
    * that choice has a collision case worth proving rather than assuming: it has to be one above the highest
    * number in use, not one above how many buses there are, or breaking one and placing another hands out a
    * number somebody else is still using.
    */
   private static final class BusNameQuery implements TestVerb, TestQuery {
      public String name() {
         return "busname";
      }

      public String usage() {
         return "query busname <dx> <dy>";
      }

      public int coordinateArgIndex() {
         return 2;
      }

      public boolean run(TestContext context) {
         return true;
      }

      public void query(TestContext context, Json.Writer out) {
         BusObjectEntity bus = busAt(context, 2);
         out.str("name", bus == null ? "" : bus.name());
         out.num("ordinal", bus == null ? -1 : bus.getOrdinal());
         out.str("custom", bus == null ? "" : bus.getCustomName());
      }
   }

   private static final class TerminalRulesVerb implements TestVerb {
      public String name() {
         return "terminalrules";
      }

      public String usage() {
         return "terminalrules <tdx> <tdy> <bdx> <bdy> <item> <target> <accepted|refused|notfound>";
      }

      public int coordinateArgIndex() {
         return 1;
      }

      public boolean run(TestContext context) {
         ObjectEntity entity = context.level.entityManager.getObjectEntity(
            context.tileX(context.intArg(1)), context.tileY(context.intArg(2)));
         if (!(entity instanceof StorageTerminalObjectEntity)) {
            context.fail("terminalrules: no terminal there");
            return false;
         }

         StorageTerminalObjectEntity terminal = (StorageTerminalObjectEntity)entity;
         int busX = context.tileX(context.intArg(3));
         int busY = context.tileY(context.intArg(4));
         Item item = ItemRegistry.getItem(context.arg(5));
         if (item == null) {
            context.fail("terminalrules: no such item");
            return false;
         }

         int target = context.intArg(6);
         String expected = context.argCount() > 7 ? context.arg(7) : "accepted";

         BusObjectEntity bus = terminal.busOnNetwork(busX, busY);
         if (bus == null) {
            context.info("the terminal does not have a bus at " + busX + "," + busY + " on its network");
            return context.check("notfound".equals(expected), "terminalrules " + expected,
                  "the bus was not on the terminal's network");
         }

         ItemCategoriesFilter proposed = new ItemCategoriesFilter(ItemCategory.masterCategory, false);
         Packet current = new Packet();
         bus.filter.writePacket(new PacketWriter(current));
         proposed.readPacket(new PacketReader(current));
         if (target > 0) {
            proposed.setItemAllowed(item, true, target);
         } else {
            proposed.setItemAllowed(item, true);
         }

         String refusal = bus.whyRefused(proposed);
         if (refusal == null) {
            Packet accepted = new Packet();
            proposed.writePacket(new PacketWriter(accepted));
            bus.filter.readPacket(new PacketReader(accepted));
            bus.rulesChanged();
         }

         context.info(refusal == null ? "applied through the terminal" : "refused: " + refusal);
         return context.check("refused".equals(expected) == (refusal != null), "terminalrules " + expected,
               refusal == null ? "it was applied" : "it was refused: " + refusal);
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
         BusObjectEntity.walkedNoCache = 0;
         BusObjectEntity.walkedStale = 0;
         BusObjectEntity.walkedNotMember = 0;
         NetworkIndex.rebuilds = 0;
         NetworkIndex.resyncs = 0;
         NetworkIndex.driftScans = 0;
         NetworkScheduler.scheduled = 0;
         NetworkScheduler.deferred = 0;
         NetworkScheduler.resolves = 0;
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
            bus.rulesChanged();
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
         bus.rulesChanged();
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
         bus.rulesChanged();
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
   /**
    * The last upgrade attempt's outcome, so a refusal can be asserted without the verb having to fail.
    *
    * <p>A verb that fails is awkward to test against a refusal that is <i>expected</i> -- the interesting
    * assertion for "abort when short" is not that the call errored but that <b>nothing was consumed</b>, which
    * needs the run to continue afterwards.
    */
   private static UnitUpgrade.Result lastUpgrade;

   private static final class UpgradeVerb implements TestVerb {
      public String name() {
         return "upgrade";
      }

      public String usage() {
         return "upgrade <dx> <dy>";
      }

      public int coordinateArgIndex() {
         return 1;
      }

      public boolean needsPlayer() {
         return true;
      }

      public boolean run(TestContext context) {
         int x = context.tileX(context.intArg(1));
         int y = context.tileY(context.intArg(2));
         lastUpgrade = UnitUpgrade.attempt(context.level, x, y, context.client);
         return true;
      }
   }

   private static final class UpgradeQuery implements TestVerb, TestQuery {
      public String name() {
         return "upgrade";
      }

      public String usage() {
         return "query upgrade <dx> <dy>";
      }

      public int coordinateArgIndex() {
         return 2;
      }

      public boolean run(TestContext context) {
         return true;
      }

      public void query(TestContext context, Json.Writer out) {
         int x = context.tileX(context.intArg(2));
         int y = context.tileY(context.intArg(3));

         out.str("outcome", lastUpgrade == null ? "none" : lastUpgrade.outcome.name().toLowerCase(java.util.Locale.ROOT));
         out.num("carried", lastUpgrade == null ? -1 : lastUpgrade.carried);
         out.num("dropped", lastUpgrade == null ? -1 : lastUpgrade.dropped);
         out.num("slotsbefore", lastUpgrade == null ? -1 : lastUpgrade.slotsBefore);
         out.num("slotsafter", lastUpgrade == null ? -1 : lastUpgrade.slotsAfter);

         UnitTier tier = UnitUpgrade.tierAt(context.level, x, y);
         out.str("tier", tier == null ? "none" : tier.name().toLowerCase(java.util.Locale.ROOT));
         out.str("next", tier == null || tier.next() == null ? "none"
            : tier.next().name().toLowerCase(java.util.Locale.ROOT));
         out.str("target", UnitUpgrade.targetId(context.level, x, y) == null
            ? "none" : UnitUpgrade.targetId(context.level, x, y));
         out.bool("station", UnitUpgrade.isStation(context.level, x, y));
         out.bool("affordable", UnitUpgrade.affordable(context.level, context.client.playerMob, x, y));

         necesse.inventory.recipe.Ingredient[] cost = UnitUpgrade.cost(context.level, x, y);
         java.util.List<arcanestorage.network.NetworkStorage> pool = UnitUpgrade.pool(context.level, x, y);
         StringBuilder names = new StringBuilder();

         if (cost != null) {
            for (necesse.inventory.recipe.Ingredient ingredient : cost) {
               String id = ingredient.ingredientStringID;
               if (names.length() > 0) {
                  names.append(',');
               }

               names.append(id);
               out.num("req_" + id, ingredient.getIngredientAmount());
               out.num("haveinv_" + id, UnitUpgrade.inPlayer(context.level, context.client.playerMob, ingredient));
               out.num("havenet_" + id, UnitUpgrade.inNetwork(pool, ingredient));
               out.num("have_" + id,
                  UnitUpgrade.available(context.level, context.client.playerMob, pool, ingredient));
            }
         }

         out.str("cost", names.toString());
         out.num("poolsize", pool.size());
      }
   }

   /**
    * How many of an item the player is carrying.
    *
    * <p>Needed because the upgrade's own query only reports the materials of the <i>next</i> tier, so it
    * cannot answer "was the player's stock left alone" after the tier has already moved on.
    *
    * <p>Uses the same four inventory flags the upgrade consumes with, so the number a test reads is the number
    * the upgrade would have spent -- not a wider view that would hide a discrepancy.
    */
   private static final class PlayerItemQuery implements TestVerb, TestQuery {
      public String name() {
         return "playerinv";
      }

      public String usage() {
         return "query playerinv <item>";
      }

      public boolean needsPlayer() {
         return true;
      }

      public boolean run(TestContext context) {
         return true;
      }

      public void query(TestContext context, Json.Writer out) {
         necesse.inventory.recipe.Ingredient probe =
            new necesse.inventory.recipe.Ingredient(context.arg(2), 1);
         out.num("count", UnitUpgrade.inPlayer(context.level, context.client.playerMob, probe));
      }
   }

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
    * {@code expect stations <dx> <dy> <n>} -- how many sockets the network offers.
    *
    * <p>Also reports the order the sockets are addressed in, as a list of the units' tile keys. That is
    * the part worth querying rather than counting: the count is visible in the interface anyway, while the
    * order is an invariant both sides depend on and neither displays. A test can therefore check that the
    * published order is sorted without needing a mouse or a second client.
    */
   private static final class StationsExpectation implements TestVerb, TestQuery {
      public String name() {
         return "stations";
      }

      public String usage() {
         return "expect stations <dx> <dy> <count>";
      }

      public int coordinateArgIndex() {
         return 2;
      }

      public void query(TestContext context, Json.Writer out) {
         StorageTerminalObjectEntity terminal = terminalAt(context, 2);
         if (terminal == null) {
            out.num("sockets", -1);
            return;
         }

         List<NetworkStations> units = terminal.getLinkedStationUnits();
         List<String> order = new ArrayList<>();
         int sockets = 0;
         int installed = 0;
         for (NetworkStations unit : units) {
            sockets += unit.getInventory().getSize();
            installed += unit.getInventory().getUsedSlots();
            order.add(String.valueOf(unit.tileOrder()));
         }

         out.num("sockets", sockets);
         out.num("units", units.size());

         // Benches actually in sockets, as opposed to sockets available. Added for the upgrade tests: growing
         // a Station Unit is only correct if the count of installed benches is unchanged by it.
         out.num("installed", installed);
         out.strings("order", order);
      }

      public boolean run(TestContext context) {
         StorageTerminalObjectEntity terminal = terminalAt(context, 2);
         if (terminal == null) {
            return noTerminal(context);
         }

         int found = terminal.getStationSlotCount();
         int wanted = context.intArg(4);
         return context.check(found == wanted, "stations = " + wanted,
               "expected " + wanted + " socket(s), found " + found);
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
