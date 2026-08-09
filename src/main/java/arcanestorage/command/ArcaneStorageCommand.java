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
import necesse.engine.network.client.Client;
import necesse.engine.network.server.Server;
import necesse.engine.network.server.ServerClient;
import necesse.engine.registries.ObjectRegistry;
import necesse.entity.objectEntity.ObjectEntity;
import necesse.inventory.Inventory;
import necesse.inventory.InventoryItem;
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
      return "<place|fill|break|report|expect> ...";
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
