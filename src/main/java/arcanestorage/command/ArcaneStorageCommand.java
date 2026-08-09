package arcanestorage.command;

import java.awt.Point;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import arcanestorage.ArcaneStorage;
import arcanestorage.container.StorageTerminalContainer;
import arcanestorage.network.NetworkContents;
import arcanestorage.network.UnitNetwork;
import arcanestorage.objectentity.StorageTerminalObjectEntity;
import arcanestorage.objectentity.StorageUnitObjectEntity;
import necesse.engine.commands.AutoComplete;
import necesse.engine.commands.ChatCommand;
import necesse.engine.commands.CommandLog;
import necesse.engine.commands.PermissionLevel;
import necesse.engine.network.Packet;
import necesse.engine.network.PacketReader;
import necesse.engine.network.PacketWriter;
import necesse.engine.network.client.Client;
import necesse.engine.network.server.Server;
import necesse.engine.network.server.ServerClient;
import necesse.engine.registries.ObjectRegistry;
import necesse.entity.objectEntity.ObjectEntity;
import necesse.inventory.Inventory;
import necesse.inventory.InventorySlot;
import necesse.inventory.InventoryItem;
import necesse.inventory.container.ContainerAction;
import necesse.inventory.container.ContainerActionResult;
import necesse.inventory.container.slots.ContainerSlot;
import necesse.level.maps.Level;

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

   public ArcaneStorageCommand() {
      super("arcanestorage", PermissionLevel.OWNER);
   }

   @Override
   public String getUsage() {
      return "<place|fill|break|report|expect|give|open|close|withdraw|click|selftest> ...";
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
               return this.expect(level, spawn, args, logs);
            case "give":
               return this.give(level, serverClient, args, logs);
            case "open":
               return this.open(level, spawn, serverClient, args, logs);
            case "close":
               return this.close(serverClient, logs);
            case "withdraw":
               return this.withdraw(serverClient, args, logs);
            case "click":
               return this.click(serverClient, args, logs);
            case "selftest":
               return this.selftest(level, spawn, serverClient, args, logs);
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
      } else {
         logs.add("FAIL place expects 'terminal' or 'unit', got '" + what + "'");
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
         logs.add("FAIL no terminal at " + args.get(1) + "," + args.get(2));
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
   private boolean expect(Level level, Point spawn, ArrayList<String> args, CommandLog logs) {
      String kind = args.get(1).toLowerCase();

      if ("total".equals(kind)) {
         String itemID = args.get(2);
         int wanted = Integer.parseInt(args.get(3));
         int actual = NetworkContents.totalOf(this.allUnits(level), itemID);
         return this.check(logs, actual == wanted, "total " + itemID + " = " + wanted, "expected " + wanted + ", found " + actual);
      }

      int x = spawn.x + Integer.parseInt(args.get(2));
      int y = spawn.y + Integer.parseInt(args.get(3));
      StorageTerminalObjectEntity terminal = this.terminalAt(level, x, y);
      if (terminal == null) {
         logs.add("FAIL no terminal at " + args.get(2) + "," + args.get(3));
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

      logs.add("FAIL expect takes 'units', 'item' or 'total', got '" + kind + "'");
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
         logs.add("FAIL no terminal at " + args.get(1) + "," + args.get(2));
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
    * {@code selftest <dx> <dy> <itemStringID>} — the whole player-coupled path in one
    * command: open, withdraw into the inventory, shift-click it back, close.
    *
    * <p>Exists so this half of the mod costs one line in chat rather than a click-through,
    * which is the difference between a check that gets run and one that gets skipped.
    *
    * <p>Every step asserts item conservation, not just the visible outcome: the total across
    * every unit plus the player's inventory must never change. Withdrawing is a move, so an
    * off-by-one that creates or destroys items fails here even when the grid looks right.
    */
   private boolean selftest(Level level, Point spawn, ServerClient serverClient, ArrayList<String> args, CommandLog logs) {
      if (this.requirePlayer(serverClient, logs, "selftest") == null) {
         return false;
      }

      int dx = Integer.parseInt(args.get(1));
      int dy = Integer.parseInt(args.get(2));
      String itemID = args.get(3);
      int x = spawn.x + dx;
      int y = spawn.y + dy;

      StorageTerminalObjectEntity terminal = this.terminalAt(level, x, y);
      if (terminal == null) {
         logs.add("FAIL no terminal at " + dx + "," + dy);
         return false;
      }

      List<StorageUnitObjectEntity> allUnits = this.allUnits(level);
      int storedBefore = NetworkContents.totalOf(allUnits, itemID);
      int heldBefore = this.countHeld(serverClient, itemID);
      int conserved = storedBefore + heldBefore;
      boolean ok = true;

      if (storedBefore <= 0) {
         logs.add("FAIL nothing to withdraw: no " + itemID + " in any unit. Use 'fill' first.");
         return false;
      }

      StorageTerminalContainer.openAndSendContainer(ArcaneStorage.TERMINAL_CONTAINER, serverClient, level, x, y);
      ok &= this.check(logs, serverClient.getContainer() instanceof StorageTerminalContainer,
         "terminal opens", "container is " + serverClient.getContainer());

      if (!(serverClient.getContainer() instanceof StorageTerminalContainer)) {
         return false;
      }

      StorageTerminalContainer container = (StorageTerminalContainer)serverClient.getContainer();
      ok &= this.check(logs, !container.isNetworkEmpty(), "network is not empty", "no unit slots registered");

      // --- withdraw ---
      int wanted = Math.min(10, storedBefore);
      this.sendWithdraw(container, itemID, wanted, false);

      int heldAfter = this.countHeld(serverClient, itemID);
      int storedAfter = NetworkContents.totalOf(allUnits, itemID);
      int moved = heldAfter - heldBefore;

      ok &= this.check(logs, moved == wanted, "withdraw moves " + wanted + " " + itemID,
         "inventory gained " + moved);
      ok &= this.check(logs, storedAfter == storedBefore - moved, "withdraw removes what it gave",
         "units held " + storedBefore + ", now " + storedAfter + ", inventory gained " + moved);
      ok &= this.check(logs, storedAfter + heldAfter == conserved, "withdraw conserves items",
         "expected " + conserved + " total, found " + (storedAfter + heldAfter));

      // --- deposit the same items straight back, as a shift-click would ---
      // Player slots are indices below NETWORK_START: Container assigns them in its own
      // constructor, before this container adds any unit slots.
      int deposited = 0;
      for (int slot = 0; slot < container.NETWORK_START && deposited < moved; slot++) {
         ContainerSlot containerSlot = container.getSlot(slot);
         InventoryItem item = containerSlot == null ? null : containerSlot.getItem();
         if (item == null || !item.item.getStringID().equals(itemID)) {
            continue;
         }

         deposited += container.applyContainerAction(slot, ContainerAction.QUICK_MOVE).value;
      }

      int heldEnd = this.countHeld(serverClient, itemID);
      int storedEnd = NetworkContents.totalOf(allUnits, itemID);

      ok &= this.check(logs, deposited == moved, "shift-click deposits all " + moved + " back",
         "deposited " + deposited);
      ok &= this.check(logs, storedEnd == storedBefore, "network is back to its starting amount",
         "started " + storedBefore + ", ended " + storedEnd);
      ok &= this.check(logs, heldEnd == heldBefore, "inventory is back to its starting amount",
         "started " + heldBefore + ", ended " + heldEnd);
      ok &= this.check(logs, storedEnd + heldEnd == conserved, "round trip conserves items",
         "expected " + conserved + " total, found " + (storedEnd + heldEnd));

      // --- close ---
      serverClient.closeContainer(true);
      ok &= this.check(logs, !(serverClient.getContainer() instanceof StorageTerminalContainer),
         "terminal closes", "container is still " + serverClient.getContainer());

      logs.add(ok ? "selftest: all checks passed" : "selftest: FAILURES above");
      return ok;
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
