package arcanestorage.harness;

import java.awt.Point;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import arcanestorage.ArcaneStorage;
import arcanestorage.container.StorageTerminalContainer;
import arcanestorage.network.NetworkContents;
import arcanestorage.object.StorageConduitObject;
import arcanestorage.objectentity.StorageTerminalObjectEntity;
import arcanestorage.objectentity.StorageUnitObjectEntity;
import necesse.engine.network.Packet;
import necesse.engine.network.PacketReader;
import necesse.engine.network.PacketWriter;
import necesse.engine.registries.ItemRegistry;
import necesse.engine.registries.ObjectRegistry;
import necesse.entity.objectEntity.ObjectEntity;
import necesse.inventory.container.ContainerAction;
import necesse.inventory.container.slots.ContainerSlot;
import necesse.inventory.item.Item;
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

      Harness.registerVerb(new ReportVerb());
      Harness.registerVerb(new ResetVerb());
      Harness.registerVerb(new WithdrawVerb());
      Harness.registerVerb(new DepositVerb());
      Harness.registerVerb(new DepositAllVerb());
      Harness.registerVerb(new DepositCursorVerb());
      Harness.registerVerb(new BenchVerb());

      Harness.registerExpectation(new UnitsExpectation());
      Harness.registerExpectation(new NetworkItemExpectation());
      Harness.registerExpectation(new CapacityExpectation());
      Harness.registerExpectation(new FitsExpectation());
      Harness.registerExpectation(new MaskExpectation());
      Harness.registerExpectation(new NetworkTotalExpectation());
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

   static List<StorageUnitObjectEntity> unitsFrom(TestContext context) {
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

         List<StorageUnitObjectEntity> units = terminal.getLinkedUnits();
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
                  && (entity instanceof StorageUnitObjectEntity || entity instanceof StorageTerminalObjectEntity)) {
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
            if (!(entity instanceof StorageUnitObjectEntity)) {
               continue;
            }

            StorageUnitObjectEntity unit = (StorageUnitObjectEntity)entity;
            for (int slot = 0; slot < unit.inventory.getSize(); slot++) {
               // Distinct items are what aggregation scales on: a network holding one item type in
               // every slot is cheap no matter how large it is.
               unit.inventory.setItem(slot, new InventoryItem(pool.get(distinct++ % pool.size()), 1));
            }

            placed++;
         }

         // Membership is recomputed from layout on every call, so this measures discovery honestly
         // rather than a cached result.
         List<StorageUnitObjectEntity> units = terminal.getLinkedUnits();

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

   /** {@code expect units <dx> <dy> <n>} -- how many units the terminal's walk reaches. */
   private static final class UnitsExpectation implements TestVerb, TestQuery {
      public String name() {
         return "units";
      }

      public String usage() {
         return "expect units <dx> <dy> <count>";
      }

      public void query(TestContext context, Json.Writer out) {
         List<StorageUnitObjectEntity> units = unitsFrom(context);
         out.num("units", units == null ? -1 : units.size());
      }

      public int coordinateArgIndex() {
         return 2;
      }

      public boolean run(TestContext context) {
         List<StorageUnitObjectEntity> units = unitsFrom(context);
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
         List<StorageUnitObjectEntity> units = unitsFrom(context);
         out.str("item", context.arg(4))
            .num("count", units == null ? -1 : NetworkContents.totalOf(units, context.arg(4)));
      }

      public int coordinateArgIndex() {
         return 2;
      }

      public boolean run(TestContext context) {
         List<StorageUnitObjectEntity> units = unitsFrom(context);
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
         List<StorageUnitObjectEntity> units = unitsFrom(context);
         out.num("used", units == null ? -1 : NetworkContents.usedSlots(units))
            .num("total", units == null ? -1 : NetworkContents.totalSlots(units));
      }

      public int coordinateArgIndex() {
         return 2;
      }

      public boolean run(TestContext context) {
         List<StorageUnitObjectEntity> units = unitsFrom(context);
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
         List<StorageUnitObjectEntity> units = unitsFrom(context);
         out.str("item", context.arg(4)).bool("fits", units != null && NetworkContents.canFit(
            context.level, units, new InventoryItem(context.arg(4)),
            StorageTerminalContainer.DEPOSIT_PURPOSE));
      }

      public int coordinateArgIndex() {
         return 2;
      }

      public boolean run(TestContext context) {
         List<StorageUnitObjectEntity> units = unitsFrom(context);
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

   static List<StorageUnitObjectEntity> allUnits(Level level) {
      java.util.ArrayList<StorageUnitObjectEntity> units = new java.util.ArrayList<>();

      for (ObjectEntity entity : level.entityManager.objectEntities) {
         if (entity instanceof StorageUnitObjectEntity && !entity.removed()) {
            units.add((StorageUnitObjectEntity)entity);
         }
      }

      return units;
   }
}
