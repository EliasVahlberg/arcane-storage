package arcanestorage.command;

import java.awt.Point;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import arcanestorage.ArcaneStorage;
import arcanestorage.container.StorageTerminalContainer;
import arcanestorage.network.NetworkContents;
import arcanestorage.object.StorageConduitObject;
import arcanestorage.network.UnitNetwork;
import arcanestorage.objectentity.StorageTerminalObjectEntity;
import arcanestorage.objectentity.StorageUnitObjectEntity;
import necesse.engine.commands.AutoComplete;
import necesse.engine.commands.ChatCommand;
import necesse.engine.commands.CommandLog;
import necesse.engine.commands.ParsedCommand;
import necesse.engine.commands.PermissionLevel;
import necesse.engine.network.Packet;
import necesse.engine.network.PacketReader;
import necesse.engine.network.PacketWriter;
import necesse.engine.network.client.Client;
import necesse.engine.network.server.Server;
import necesse.engine.network.server.ServerClient;
import necesse.engine.registries.ObjectRegistry;
import necesse.engine.registries.TileRegistry;
import necesse.entity.objectEntity.ObjectEntity;
import necesse.inventory.Inventory;
import necesse.inventory.InventorySlot;
import necesse.inventory.InventoryItem;
import necesse.inventory.container.Container;
import necesse.inventory.container.ContainerAction;
import necesse.inventory.container.ContainerActionResult;
import necesse.inventory.container.slots.ContainerSlot;
import necesse.level.maps.Level;
import necesse.level.maps.regionSystem.RegionManager;

/**
 * Test-harness command, driven by scenario files through a headless server.
 *
 * <p>Exists because driving the mod by hand is slow and unrepeatable. Every subcommand is
 * a single console line, so a scenario file is just a list of these and any prefix of one
 * can be pasted into a live server to investigate a failure.
 *
 * <p>Coordinates are <b>relative to the world spawn tile</b>, which keeps scenarios valid
 * across worlds and seeds rather than hardcoding tiles from one save.
 *
 * <p>Assertions print a line beginning {@code PASS} or {@code FAIL}; the runner counts
 * those and sets its exit status from them.
 *
 * <p>Scoped to {@code OWNER} so the server console (which is {@code SERVER}, above owner)
 * can run it non-interactively, and so it also works in a singleplayer session where
 * assertions that need a real player can be checked.
 */
public class ArcaneStorageCommand extends ChatCommand {

   /** Guards against a scenario file that calls {@code run} on itself. */
   private boolean running = false;

   public ArcaneStorageCommand() {
      super("arcanestorage", PermissionLevel.OWNER);
   }

   @Override
   public String getUsage() {
      return "<place|fill|clear|reset|break|report|expect|give|open|close|withdraw|deposit"
         + "|depositall|quickstack|restock|click|run|echo> ...";
   }

   @Override
   public String getAction() {
      return "Arcane Storage test harness";
   }

   @Override
   public String getCurrentUsage(Client client, Server server, ServerClient serverClient, String[] args) {
      return this.getUsage();
   }

   @Override
   public List<AutoComplete> autocomplete(Client client, Server server, ServerClient serverClient, String[] args) {
      return Collections.emptyList();
   }

   @Override
   public boolean run(Client client, Server server, ServerClient serverClient, ArrayList<String> args, CommandLog logs) {
      if (args.isEmpty()) {
         logs.add("FAIL usage: " + this.getUsage());
         return false;
      }

      Level level = server.world.getLevel(server.world.worldEntity.spawnLevelIdentifier);
      if (level == null) {
         logs.add("FAIL could not resolve the spawn level");
         return false;
      }

      Point spawn = server.world.worldEntity.spawnTile;
      String sub = args.get(0).toLowerCase();

      // Console commands run on the 'Command scanner' thread, not the server thread, and
      // every subcommand here touches region data. Level.setObject reaches LightManager and
      // then wants a region lock, while the server thread takes entityManager.lock first and
      // LightManager second -- opposite order, so the two deadlock. The engine's own
      // ThreadFreezeMonitor caught exactly that during development.
      //
      // Taking entityManager.lock around the whole subcommand matches the order the engine
      // uses (RegionManager holds it outermost), which removes the inversion. It also makes
      // each subcommand atomic with respect to the tick, so an assertion cannot observe a
      // half-applied change. A player-issued command arrives as a packet on the server
      // thread and is already safe; the lock is reentrant, so holding it costs nothing there.
      // Regions load on demand, and reads do NOT trigger a load: the object layer resolves a
      // tile through RegionBoundsExecutor with loadIfNotLoaded=false, so an unloaded region
      // reads as empty rather than as itself. On a freshly generated world every region is
      // already in memory, which hides this completely -- it only appears after a restart,
      // where a scenario would see an empty world and report a persistence bug that is not
      // there. Loading the addressed region first is what makes reads mean what they say.
      //
      // Only a player normally causes regions to load, and the harness has no player.
      this.ensureRegionLoaded(level, spawn, args);

      if (sub.equals("run")) {
         // Each line locks itself, so a long scenario never holds the tick hostage for its
         // whole duration. ThreadFreezeMonitor reports a freeze after 15 seconds.
         return this.runScenario(server, serverClient, args, logs);
      }

      if (sub.equals("echo")) {
         logs.add(String.join(" ", args.subList(1, args.size())));
         return true;
      }

      synchronized (level.entityManager.lock) {
         return this.dispatch(sub, level, spawn, server, serverClient, args, logs);
      }
   }

   private boolean dispatch(String sub, Level level, Point spawn, Server server, ServerClient serverClient,
                            ArrayList<String> args, CommandLog logs) {
      try {
         switch (sub) {
            case "place":
               return this.place(level, spawn, args, logs);
            case "fill":
               return this.fill(level, spawn, args, logs);
            case "break":
               return this.breakObject(level, spawn, args, logs);
            case "report":
               return this.report(level, spawn, args, logs);
            case "expect":
               return this.expect(level, spawn, serverClient, args, logs);
            case "give":
               return this.give(level, serverClient, args, logs);
            case "clear":
               return this.clear(level, spawn, args, logs);
            case "reset":
               return this.reset(level, logs);
            case "open":
               return this.open(level, spawn, serverClient, args, logs);
            case "close":
               return this.close(serverClient, logs);
            case "withdraw":
               return this.withdraw(serverClient, args, logs);
            case "deposit":
               return this.deposit(serverClient, args, logs);
            case "depositall":
               return this.depositAll(serverClient, logs);
            case "quickstack":
               return this.quickStack(serverClient, logs);
            case "restock":
               return this.restock(serverClient, logs);
            case "click":
               return this.click(serverClient, args, logs);
            case "run":
               return this.runScenario(server, serverClient, args, logs);
            default:
               logs.add("FAIL unknown subcommand '" + sub + "'; usage: " + this.getUsage());
               return false;
         }
      } catch (IndexOutOfBoundsException e) {
         logs.add("FAIL missing arguments for '" + sub + "'");
         return false;
      } catch (NumberFormatException e) {
         logs.add("FAIL expected a number: " + e.getMessage());
         return false;
      }
   }

   /** {@code place <terminal|unit> <dx> <dy>} */
   private boolean place(Level level, Point spawn, ArrayList<String> args, CommandLog logs) {
      String what = args.get(1).toLowerCase();
      int x = spawn.x + Integer.parseInt(args.get(2));
      int y = spawn.y + Integer.parseInt(args.get(3));

      String stringID;
      if ("terminal".equals(what)) {
         stringID = ArcaneStorage.TERMINAL_STRING_ID;
      } else if ("unit".equals(what)) {
         stringID = ArcaneStorage.UNIT_STRING_ID;
      } else if ("conduit".equals(what)) {
         stringID = ArcaneStorage.CONDUIT_STRING_ID;
      } else {
         logs.add("FAIL place expects 'terminal', 'unit' or 'conduit', got '" + what + "'");
         return false;
      }

      // setObject creates the object entity itself, so nothing else is needed here.
      level.setObject(x, y, ObjectRegistry.getObjectID(stringID));
      logs.add("placed " + what + " at " + args.get(2) + "," + args.get(3));
      return true;
   }

   /** {@code fill <dx> <dy> <itemStringID> <amount>} — writes straight into free slots. */
   private boolean fill(Level level, Point spawn, ArrayList<String> args, CommandLog logs) {
      int x = spawn.x + Integer.parseInt(args.get(1));
      int y = spawn.y + Integer.parseInt(args.get(2));
      String itemID = args.get(3);
      int amount = Integer.parseInt(args.get(4));

      StorageUnitObjectEntity unit = this.unitAt(level, x, y);
      if (unit == null) {
         logs.add("FAIL no storage unit at " + args.get(1) + "," + args.get(2));
         return false;
      }

      InventoryItem template;
      try {
         template = new InventoryItem(itemID, 1);
      } catch (Exception e) {
         logs.add("FAIL unknown item '" + itemID + "'");
         return false;
      }

      // Deliberately setItem rather than addItem: addItem takes a PlayerMob, and the whole
      // point of this command is to work with no player connected.
      int stack = template.item.getStackSize();
      int remaining = amount;
      Inventory inventory = unit.inventory;

      for (int slot = 0; slot < inventory.getSize() && remaining > 0; slot++) {
         if (!inventory.isSlotClear(slot)) {
            continue;
         }

         int put = Math.min(remaining, stack);
         inventory.setItem(slot, new InventoryItem(itemID, put));
         remaining -= put;
      }

      if (remaining > 0) {
         logs.add("FAIL unit at " + args.get(1) + "," + args.get(2) + " had no room for " + remaining + " of " + itemID);
         return false;
      }

      logs.add("filled " + args.get(1) + "," + args.get(2) + " with " + amount + " " + itemID);
      return true;
   }

   /** {@code break <dx> <dy>} — clears the tile, as destroying the object would. */
   private boolean breakObject(Level level, Point spawn, ArrayList<String> args, CommandLog logs) {
      int x = spawn.x + Integer.parseInt(args.get(1));
      int y = spawn.y + Integer.parseInt(args.get(2));
      level.setObject(x, y, 0);
      logs.add("broke object at " + args.get(1) + "," + args.get(2));
      return true;
   }

   /** {@code report <dx> <dy>} — the network as one terminal sees it. */
   private boolean report(Level level, Point spawn, ArrayList<String> args, CommandLog logs) {
      int x = spawn.x + Integer.parseInt(args.get(1));
      int y = spawn.y + Integer.parseInt(args.get(2));

      StorageTerminalObjectEntity terminal = this.terminalAt(level, x, y);
      if (terminal == null) {
         logs.add("FAIL no terminal at " + args.get(1) + "," + args.get(2) + " (tile " + x + "," + y + ")");
         return false;
      }

      List<StorageUnitObjectEntity> units = terminal.getLinkedUnits();
      logs.add("network at " + args.get(1) + "," + args.get(2) + ": " + units.size() + " units");

      for (InventoryItem item : NetworkContents.aggregate(level, units, StorageTerminalContainer.AGGREGATE_PURPOSE)) {
         logs.add("  " + item.item.getStringID() + " x" + item.getAmount());
      }

      return true;
   }

   /**
    * {@code expect units <dx> <dy> <n>} — linked unit count for a terminal.
    * {@code expect item <dx> <dy> <itemStringID> <n>} — aggregated amount for a terminal.
    * {@code expect total <itemStringID> <n>} — amount across <b>every</b> unit on the level,
    * which is the conservation check: it does not care about topology, so it catches items
    * created or destroyed by any action.
    */
   private boolean expect(Level level, Point spawn, ServerClient serverClient, ArrayList<String> args, CommandLog logs) {
      String kind = args.get(1).toLowerCase();

      if ("total".equals(kind)) {
         String itemID = args.get(2);
         int wanted = Integer.parseInt(args.get(3));
         int actual = NetworkContents.totalOf(this.allUnits(level), itemID);
         return this.check(logs, actual == wanted, "total " + itemID + " = " + wanted, "expected " + wanted + ", found " + actual);
      }

      if ("held".equals(kind)) {
         String itemID = args.get(2);
         int wanted = Integer.parseInt(args.get(3));
         if (this.requirePlayer(serverClient, logs, "expect held") == null) {
            return false;
         }

         int actual = this.countHeld(serverClient, itemID);
         return this.check(logs, actual == wanted, "held " + itemID + " = " + wanted, "expected " + wanted + ", found " + actual);
      }

      int x = spawn.x + Integer.parseInt(args.get(2));
      int y = spawn.y + Integer.parseInt(args.get(3));

      // A conduit has no object entity, so its assertion works from the tile and must come
      // before the terminal lookup below.
      if ("mask".equals(kind)) {
         int wanted = Integer.parseInt(args.get(4));
         int mask = StorageConduitObject.connectionMask(level, x, y);
         return this.check(
            logs,
            mask == wanted,
            "mask at " + args.get(2) + "," + args.get(3) + " = " + wanted,
            "expected " + wanted + ", found " + mask
         );
      }

      StorageTerminalObjectEntity terminal = this.terminalAt(level, x, y);
      if (terminal == null) {
         logs.add("FAIL no terminal at " + args.get(2) + "," + args.get(3) + " (tile " + x + "," + y + ")");
         return false;
      }

      List<StorageUnitObjectEntity> units = terminal.getLinkedUnits();

      if ("units".equals(kind)) {
         int wanted = Integer.parseInt(args.get(4));
         return this.check(logs, units.size() == wanted, "units = " + wanted, "expected " + wanted + ", found " + units.size());
      }

      if ("item".equals(kind)) {
         String itemID = args.get(4);
         int wanted = Integer.parseInt(args.get(5));
         int actual = 0;

         for (InventoryItem item : NetworkContents.aggregate(level, units, StorageTerminalContainer.AGGREGATE_PURPOSE)) {
            if (item.item.getStringID().equals(itemID)) {
               actual += item.getAmount();
            }
         }

         return this.check(logs, actual == wanted, "item " + itemID + " = " + wanted, "expected " + wanted + ", found " + actual);
      }

      if ("capacity".equals(kind)) {
         int wantedUsed = Integer.parseInt(args.get(4));
         int wantedTotal = Integer.parseInt(args.get(5));
         int used = NetworkContents.usedSlots(units);
         int total = NetworkContents.totalSlots(units);
         return this.check(
            logs,
            used == wantedUsed && total == wantedTotal,
            "capacity = " + wantedUsed + "/" + wantedTotal + " slots",
            "expected " + wantedUsed + "/" + wantedTotal + ", found " + used + "/" + total
         );
      }

      if ("fits".equals(kind)) {
         String itemID = args.get(4);
         boolean wanted = Boolean.parseBoolean(args.get(5));
         boolean fits = NetworkContents
            .canFit(level, units, new InventoryItem(itemID), StorageTerminalContainer.AGGREGATE_PURPOSE);
         return this.check(
            logs,
            fits == wanted,
            "fits " + itemID + " = " + wanted,
            "expected " + wanted + ", found " + fits
         );
      }

      logs.add("FAIL expect takes 'units', 'item', 'capacity', 'fits', 'mask', 'total' or 'held', got '" + kind + "'");
      return false;
   }

   private boolean check(CommandLog logs, boolean ok, String what, String detail) {
      logs.add((ok ? "PASS " : "FAIL ") + what + (ok ? "" : " -- " + detail));
      return ok;
   }

   // ------------------------------------------------------------------------------------
   // Player-coupled subcommands.
   //
   // Container(client, uniqueSeed) reads client.playerMob.getInv(), so a container cannot
   // exist without a player. That is the harness's hard boundary: with nobody connected
   // there is nothing to open, so everything below needs a live session and reports a clear
   // failure when run from the server console, where serverClient is null.
   //
   // These do not fabricate a click. They call the exact methods the packet handlers call:
   // a click is Container.applyContainerAction(slot, action) per PacketContainerAction, and
   // a withdraw is WithdrawAction.executePacket(reader) per PacketContainerCustomAction. So
   // what is tested is the shipping path, not a parallel imitation of it.
   // ------------------------------------------------------------------------------------

   private ServerClient requirePlayer(ServerClient serverClient, CommandLog logs, String sub) {
      if (serverClient == null) {
         logs.add("FAIL '" + sub + "' needs a player: a container is built from the player's inventory, "
            + "so it cannot be opened from the console. Run this from in-game chat.");
      }

      return serverClient;
   }

   private StorageTerminalContainer requireTerminalContainer(ServerClient serverClient, CommandLog logs, String sub) {
      if (this.requirePlayer(serverClient, logs, sub) == null) {
         return null;
      }

      if (!(serverClient.getContainer() instanceof StorageTerminalContainer)) {
         logs.add("FAIL '" + sub + "' needs an open terminal; run 'open <dx> <dy>' first");
         return null;
      }

      return (StorageTerminalContainer)serverClient.getContainer();
   }

   /** {@code give <itemStringID> <amount>} — into the player's own inventory. */
   private boolean give(Level level, ServerClient serverClient, ArrayList<String> args, CommandLog logs) {
      if (this.requirePlayer(serverClient, logs, "give") == null) {
         return false;
      }

      String itemID = args.get(1);
      int amount = Integer.parseInt(args.get(2));
      boolean added = serverClient.playerMob.getInv().addItem(new InventoryItem(itemID, amount), true, "harness");
      return this.check(logs, added, "gave " + amount + " " + itemID, "inventory would not take them");
   }

   /** {@code open <dx> <dy>} — the same call the object's interact makes. */
   private boolean open(Level level, Point spawn, ServerClient serverClient, ArrayList<String> args, CommandLog logs) {
      if (this.requirePlayer(serverClient, logs, "open") == null) {
         return false;
      }

      int x = spawn.x + Integer.parseInt(args.get(1));
      int y = spawn.y + Integer.parseInt(args.get(2));
      if (this.terminalAt(level, x, y) == null) {
         logs.add("FAIL no terminal at " + args.get(1) + "," + args.get(2) + " (tile " + x + "," + y + ")");
         return false;
      }

      StorageTerminalContainer.openAndSendContainer(ArcaneStorage.TERMINAL_CONTAINER, serverClient, level, x, y);
      return this.check(logs, serverClient.getContainer() instanceof StorageTerminalContainer,
         "opened terminal at " + args.get(1) + "," + args.get(2), "container did not open");
   }

   private boolean close(ServerClient serverClient, CommandLog logs) {
      if (this.requirePlayer(serverClient, logs, "close") == null) {
         return false;
      }

      serverClient.closeContainer(true);
      logs.add("closed container");
      return true;
   }

   /** {@code withdraw <itemStringID> <amount> [cursor]} */
   private boolean withdraw(ServerClient serverClient, ArrayList<String> args, CommandLog logs) {
      StorageTerminalContainer container = this.requireTerminalContainer(serverClient, logs, "withdraw");
      if (container == null) {
         return false;
      }

      String itemID = args.get(1);
      int amount = Integer.parseInt(args.get(2));
      boolean toCursor = args.size() > 3 && "cursor".equalsIgnoreCase(args.get(3));

      this.sendWithdraw(container, itemID, amount, toCursor);
      logs.add("withdrew up to " + amount + " " + itemID + (toCursor ? " to the cursor" : " to the inventory"));
      return true;
   }

   /**
    * Encodes the request exactly as {@code WithdrawAction.runAndSend} does and hands it to
    * the same {@code executePacket}, so the packet encoding is exercised too rather than
    * bypassed.
    */
   private void sendWithdraw(StorageTerminalContainer container, String itemID, int amount, boolean toCursor) {
      Packet content = new Packet();
      PacketWriter writer = new PacketWriter(content);
      new InventoryItem(itemID, 1).addPacketContent(writer);
      writer.putNextInt(amount);
      writer.putNextBoolean(toCursor);
      container.withdrawAction.executePacket(new PacketReader(content));
      container.markFullDirty();
   }

   /** {@code click <slotIndex> <LEFT_CLICK|QUICK_MOVE|...>} — a raw container action. */
   private boolean click(ServerClient serverClient, ArrayList<String> args, CommandLog logs) {
      StorageTerminalContainer container = this.requireTerminalContainer(serverClient, logs, "click");
      if (container == null) {
         return false;
      }

      int slot = Integer.parseInt(args.get(1));
      ContainerAction action;
      try {
         action = ContainerAction.valueOf(args.get(2).toUpperCase());
      } catch (IllegalArgumentException e) {
         logs.add("FAIL unknown action '" + args.get(2) + "'; try LEFT_CLICK, RIGHT_CLICK, QUICK_MOVE or TAKE_ONE");
         return false;
      }

      ContainerActionResult result = container.applyContainerAction(slot, action);
      logs.add("click " + action + " on slot " + slot + " moved " + result.value);
      return true;
   }

   /**
    * {@code deposit <itemStringID> <amount>} — shift-click the item from the player's own
    * inventory into the network, which is a single user action rather than a slot index.
    *
    * <p>Player slots are the indices below {@code NETWORK_START}: {@code Container} assigns
    * them in its own constructor, before this container adds any unit slots.
    */
   private boolean deposit(ServerClient serverClient, ArrayList<String> args, CommandLog logs) {
      StorageTerminalContainer container = this.requireTerminalContainer(serverClient, logs, "deposit");
      if (container == null) {
         return false;
      }

      String itemID = args.get(1);
      int amount = Integer.parseInt(args.get(2));
      int moved = 0;

      for (int slot = 0; slot < container.NETWORK_START && moved < amount; slot++) {
         ContainerSlot containerSlot = container.getSlot(slot);
         InventoryItem item = containerSlot == null ? null : containerSlot.getItem();
         if (item == null || !item.item.getStringID().equals(itemID)) {
            continue;
         }

         moved += container.applyContainerAction(slot, ContainerAction.QUICK_MOVE).value;
      }

      return this.check(logs, moved == amount, "deposited " + amount + " " + itemID,
         "only moved " + moved + "; the network may be full or the inventory short");
   }

   /**
    * {@code clear <radius> [tileStringID]} — strips objects, and optionally flattens tiles,
    * in a square around the world spawn.
    *
    * <p>Makes a scenario independent of what world generation happened to put there. Vanilla
    * ships {@code cleararea}, which does the same and more thoroughly — it clears every
    * object layer — but it targets a {@code ServerClient}, so it cannot run with nobody
    * connected. This clears the main object layer only, which is where placeable furniture
    * lives; decorative layers are left alone.
    *
    * <p>Run it <b>before</b> placing anything, or it will remove what was just placed.
    */
   private boolean clear(Level level, Point spawn, ArrayList<String> args, CommandLog logs) {
      int radius = Integer.parseInt(args.get(1));
      if (radius < 1 || radius > 200) {
         logs.add("FAIL radius must be between 1 and 200");
         return false;
      }

      int tileID = -1;
      if (args.size() > 2) {
         tileID = TileRegistry.getTileID(args.get(2));
         if (tileID < 0) {
            logs.add("FAIL unknown tile '" + args.get(2) + "'");
            return false;
         }
      }

      int objectsCleared = 0;

      for (int y = spawn.y - radius; y <= spawn.y + radius; y++) {
         for (int x = spawn.x - radius; x <= spawn.x + radius; x++) {
            if (level.getObjectID(x, y) != 0) {
               level.setObject(x, y, 0);
               objectsCleared++;
            }

            if (tileID >= 0) {
               level.setTile(x, y, tileID);
            }
         }
      }

      logs.add("cleared " + objectsCleared + " objects within " + radius + " tiles of spawn"
         + (tileID >= 0 ? ", tiles set to " + args.get(2) : ""));
      return true;
   }

   /**
    * {@code reset} — removes every storage object on the level, wherever it is.
    *
    * <p>Needed because several scenarios now share one server boot. {@code clear} only covers
    * a radius, so a unit a previous scenario left outside that radius would still be counted
    * by {@code expect total}, which scans the whole level. This is radius-independent, so a
    * scenario cannot be polluted by one that ran before it.
    */
   private boolean reset(Level level, CommandLog logs) {
      ArrayList<Point> ours = new ArrayList<>();

      for (ObjectEntity entity : level.entityManager.objectEntities) {
         if (!entity.removed() && (entity instanceof StorageUnitObjectEntity || entity instanceof StorageTerminalObjectEntity)) {
            ours.add(new Point(entity.tileX, entity.tileY));
         }
      }

      for (Point tile : ours) {
         level.setObject(tile.x, tile.y, 0);
      }

      logs.add("reset removed " + ours.size() + " storage objects from the level");
      return true;
   }

   /**
    * {@code run <name>} — executes {@code <name>.txt} from the scenario directory, line by
    * line, as the caller.
    *
    * <p>This is what makes a session test a data file rather than Java. Lines are whole
    * console commands handed to {@code CommandsManager.runServerCommand}, so a session
    * scenario has exactly the same format as one the headless runner drives, can mix in
    * vanilla commands, and any line can be pasted into chat on its own to investigate a
    * failure. Composition belongs in the files; this class only supplies primitives.
    *
    * <p>The directory comes from {@code -Darcanestorage.scenarios} and a name cannot escape
    * it, so this does not become a way to read arbitrary files off the host.
    */
   private boolean runScenario(Server server, ServerClient serverClient, ArrayList<String> args, CommandLog logs) {
      if (this.running) {
         logs.add("FAIL 'run' cannot nest");
         return false;
      }

      String root = System.getProperty("arcanestorage.scenarios");
      if (root == null) {
         logs.add("FAIL scenario directory unknown: launch with -Darcanestorage.scenarios=<dir> "
            + "(make run PACKETLOG=1 and the harness scripts set it already)");
         return false;
      }

      Path rootPath = Paths.get(root).toAbsolutePath().normalize();
      Path file = rootPath.resolve(args.get(1) + ".txt").normalize();
      if (!file.startsWith(rootPath)) {
         logs.add("FAIL scenario name must stay inside the scenario directory");
         return false;
      }

      List<String> lines;
      try {
         lines = Files.readAllLines(file);
      } catch (IOException e) {
         logs.add("FAIL could not read " + file + ": " + e.getMessage());
         return false;
      }

      this.running = true;
      try {
         for (String raw : lines) {
            String line = raw.trim();
            if (line.isEmpty() || line.startsWith("#")) {
               continue;
            }

            logs.add("> " + line);
            server.commandsManager.runServerCommand(new ParsedCommand(line), serverClient);
         }
      } finally {
         this.running = false;
      }

      logs.add("ran scenario " + args.get(1));
      return true;
   }

   /**
    * Deposits everything the player carries, the way the interface button does.
    *
    * <p>Reports the count so a scenario can assert conservation: what left the player must
    * equal what arrived in the network.
    */
   private boolean depositAll(ServerClient serverClient, CommandLog logs) {
      StorageTerminalContainer container = this.requireTerminalContainer(serverClient, logs, "depositall");
      if (container == null) {
         return false;
      }

      int moved = container.depositAll();
      logs.add("PASS depositall moved " + moved + " item(s)");
      return true;
   }

   /**
    * Quick-stacks into the network: tops up what the network already holds and moves nothing
    * else. Asserting the difference from depositall is the point of testing it separately.
    */
   private boolean quickStack(ServerClient serverClient, CommandLog logs) {
      StorageTerminalContainer container = this.requireTerminalContainer(serverClient, logs, "quickstack");
      if (container == null) {
         return false;
      }

      container.applyContainerAction(Container.QUICK_STACK_SLOT, ContainerAction.LEFT_CLICK);
      logs.add("PASS quickstack applied");
      return true;
   }

   /** Restocks the player's stacks from the network. */
   private boolean restock(ServerClient serverClient, CommandLog logs) {
      StorageTerminalContainer container = this.requireTerminalContainer(serverClient, logs, "restock");
      if (container == null) {
         return false;
      }

      container.applyContainerAction(Container.RESTOCK_SLOT, ContainerAction.LEFT_CLICK);
      logs.add("PASS restock applied");
      return true;
   }

   /**
    * Loads the regions a subcommand is about to touch.
    *
    * <p>Every scenario coordinate is an offset from spawn, and every subcommand that names a
    * tile is covered by scanning the arguments for numbers rather than by teaching this method
    * each subcommand's argument order — a scenario that addresses a tile always passes it as a
    * pair of integers.
    *
    * <p>{@code clear} is the exception: it takes a radius rather than a coordinate, so its box
    * is loaded from spawn outward. That box is bounded by the same radius limit the subcommand
    * enforces.
    */
   private void ensureRegionLoaded(Level level, Point spawn, ArrayList<String> args) {
      if ("clear".equals(args.get(0).toLowerCase()) && args.size() > 1) {
         try {
            int radius = Math.min(200, Math.abs(Integer.parseInt(args.get(1))));
            loadRegionsIn(level, spawn.x - radius, spawn.y - radius, spawn.x + radius, spawn.y + radius);
         } catch (NumberFormatException ignored) {
            // Bad input is reported by the subcommand itself.
         }

         return;
      }

      ArrayList<Integer> offsets = new ArrayList<>();
      for (int i = 1; i < args.size(); i++) {
         try {
            offsets.add(Integer.parseInt(args.get(i)));
         } catch (NumberFormatException ignored) {
            // Item IDs, action names and similar are not coordinates.
         }
      }

      // Coordinates always arrive as a pair, so load a region around each pair found.
      for (int i = 0; i + 1 < offsets.size(); i += 2) {
         int x = spawn.x + offsets.get(i);
         int y = spawn.y + offsets.get(i + 1);
         loadRegionsIn(level, x, y, x, y);
      }
   }

   /** Loads every region overlapping a tile box, inclusive. */
   private static void loadRegionsIn(Level level, int fromX, int fromY, int toX, int toY) {
      int bits = RegionManager.REGION_SIZE_BITS;
      for (int regionY = fromY >> bits; regionY <= toY >> bits; regionY++) {
         for (int regionX = fromX >> bits; regionX <= toX >> bits; regionX++) {
            level.regionManager.getRegion(regionX, regionY, true);
         }
      }
   }

   /**
    * How much of an item the player is carrying.
    *
    * <p>Counts every slot the manager exposes, including inactive equipment sets and the
    * temporary and cloud slots, so nothing can hide from a conservation check by sitting
    * somewhere unusual.
    */
   private int countHeld(ServerClient serverClient, String itemID) {
      return serverClient.playerMob.getInv()
         .streamInventorySlots(true, true, true, true)
         .map(InventorySlot::getItem)
         .filter(item -> item != null && item.item.getStringID().equals(itemID))
         .mapToInt(InventoryItem::getAmount)
         .sum();
   }

   /** Every unit on the level, found by scanning loaded object entities. */
   private List<StorageUnitObjectEntity> allUnits(Level level) {
      List<StorageUnitObjectEntity> units = new ArrayList<>();

      for (ObjectEntity entity : level.entityManager.objectEntities) {
         if (entity instanceof StorageUnitObjectEntity && !entity.removed()) {
            units.add((StorageUnitObjectEntity)entity);
         }
      }

      return units;
   }

   private StorageUnitObjectEntity unitAt(Level level, int x, int y) {
      ObjectEntity entity = level.entityManager.getObjectEntity(x, y);
      return entity instanceof StorageUnitObjectEntity ? (StorageUnitObjectEntity)entity : null;
   }

   private StorageTerminalObjectEntity terminalAt(Level level, int x, int y) {
      ObjectEntity entity = level.entityManager.getObjectEntity(x, y);
      return entity instanceof StorageTerminalObjectEntity ? (StorageTerminalObjectEntity)entity : null;
   }
}
