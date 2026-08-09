package arcanestorage.testkit;

import java.util.List;

import arcanestorage.ArcaneStorage;
import arcanestorage.container.StorageTerminalContainer;
import arcanestorage.network.NetworkContents;
import arcanestorage.object.StorageConduitObject;
import arcanestorage.objectentity.StorageTerminalObjectEntity;
import arcanestorage.objectentity.StorageUnitObjectEntity;
import necesse.entity.objectEntity.ObjectEntity;
import necesse.inventory.InventoryItem;
import necesse.level.maps.Level;
import necessetestkit.TestKit;
import necessetestkit.command.TestContext;
import necessetestkit.command.TestVerb;

/**
 * Registers this mod's own vocabulary with the test kit.
 *
 * <p><strong>Nothing outside this package may reference it.</strong> The kit is an
 * {@code optionalDependencies} entry, so it may be absent at runtime -- and merely loading a class
 * that mentions a missing type throws {@code NoClassDefFoundError}. Keeping every kit reference
 * inside this one class means a player without the kit loses the test verbs and nothing else.
 * {@link #registerIfPresent()} is the only door in.
 *
 * <p>What lives here is what the kit cannot know: the shape of a storage network. The kit's own
 * {@code expect item} counts one tile's inventory, which is the right answer for a chest and the
 * wrong one for a terminal, so this registers a version that means "everything the network at this
 * tile can see". Registering over a built-in is supported by the kit for exactly this case.
 */
public final class ArcaneStorageVerbs {

   private ArcaneStorageVerbs() {
   }

   /**
    * Registers everything, or does nothing if the kit is not installed.
    *
    * <p>The catch is {@code Throwable} rather than {@code Exception} on purpose:
    * {@code NoClassDefFoundError} is an {@code Error}, and it is the exact failure being guarded
    * against.
    */
   public static void registerIfPresent() {
      try {
         register();
      } catch (Throwable kitAbsent) {
         // Deliberately quiet at info level: for a player, the kit being absent is the normal
         // case and not a problem worth a warning.
         System.out.println("Arcane Storage: test kit not present, test verbs not registered ("
               + kitAbsent.getClass().getSimpleName() + ")");
      }
   }

   private static void register() {
      // Lets scenarios read 'place unit 5 0' rather than naming full string IDs.
      TestKit.registerObjectAlias("terminal", ArcaneStorage.TERMINAL_STRING_ID);
      TestKit.registerObjectAlias("unit", ArcaneStorage.UNIT_STRING_ID);
      TestKit.registerObjectAlias("conduit", ArcaneStorage.CONDUIT_STRING_ID);

      TestKit.registerExpectation(new UnitsExpectation());
      TestKit.registerExpectation(new NetworkItemExpectation());
      TestKit.registerExpectation(new CapacityExpectation());
      TestKit.registerExpectation(new FitsExpectation());
      TestKit.registerExpectation(new MaskExpectation());
      TestKit.registerExpectation(new NetworkTotalExpectation());
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

   /** {@code expect units <dx> <dy> <n>} -- how many units the terminal's walk reaches. */
   private static final class UnitsExpectation implements TestVerb {
      public String name() {
         return "units";
      }

      public String usage() {
         return "expect units <dx> <dy> <count>";
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
    * <p>Replaces the kit's built-in {@code item}, which counts the inventory of the tile itself. A
    * terminal has no inventory of its own; the number that matters is what its network can see.
    */
   private static final class NetworkItemExpectation implements TestVerb {
      public String name() {
         return "item";
      }

      public String usage() {
         return "expect item <dx> <dy> <itemStringID> <count>";
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
   private static final class CapacityExpectation implements TestVerb {
      public String name() {
         return "capacity";
      }

      public String usage() {
         return "expect capacity <dx> <dy> <usedSlots> <totalSlots>";
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
   private static final class FitsExpectation implements TestVerb {
      public String name() {
         return "fits";
      }

      public String usage() {
         return "expect fits <dx> <dy> <itemStringID> <true|false>";
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
   private static final class MaskExpectation implements TestVerb {
      public String name() {
         return "mask";
      }

      public String usage() {
         return "expect mask <dx> <dy> <bitmask: north 1, east 2, south 4, west 8>";
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
    * <p>Replaces the kit's built-in {@code total}, which sums every inventory on the level. That
    * is the better default for a mod that does not own its containers, but here it would also
    * count vanilla chests and mask the thing being tested: whether an action conserved items
    * inside the network.
    */
   private static final class NetworkTotalExpectation implements TestVerb {
      public String name() {
         return "total";
      }

      public String usage() {
         return "expect total <itemStringID> <count>";
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
