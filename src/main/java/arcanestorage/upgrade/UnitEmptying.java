package arcanestorage.upgrade;

import java.util.ArrayList;
import java.util.List;

import arcanestorage.network.NetworkStorage;
import arcanestorage.network.UnitNetwork;
import arcanestorage.objectentity.StorageTerminalObjectEntity;
import necesse.entity.mobs.PlayerMob;
import necesse.entity.objectEntity.ObjectEntity;
import necesse.engine.network.server.ServerClient;
import necesse.inventory.Inventory;
import necesse.inventory.InventoryItem;
import necesse.inventory.InventoryRange;
import necesse.level.maps.Level;

/**
 * Moves everything out of one storage unit into the rest of its network, so the unit can be picked up and moved.
 *
 * <p><b>Why this is a convenience rather than a rescue.</b> Breaking a full unit does not destroy anything:
 * {@code StorageUnitObjectEntity extends InventoryObjectEntity}, whose {@code getDroppedItems} returns every stack, so
 * the contents land on the floor. What that leaves is up to forty item entities to walk over, in a game where dropped
 * items can be picked up by anyone standing nearby and where a full inventory means walking away from the rest. Emptying
 * first turns relocating a unit into a two-click operation with nothing on the ground.
 *
 * <p>Lives beside {@link UnitUpgrade} because it is the same kind of thing: an operation on one unit's tile, driven by
 * the panel that opens when the unit is clicked, validated and executed entirely on the server.
 *
 * <p>Item movement goes through {@code Inventory.addItem} rather than any hand-rolled stack arithmetic, for the same
 * reason the terminal's deposit-all does: duplication bugs come from reimplementing transfers, and the engine's own
 * method already handles partial stacks, stack limits and the slot-update notification that keeps this mod's indexes
 * and every open container in step.
 */
public final class UnitEmptying {

   /** Purpose recorded on the inserts, so these movements are distinguishable from deposits and upgrades. */
   public static final String PURPOSE = "arcanestorageempty";

   private UnitEmptying() {
   }

   /** What happened, in enough detail for the panel to explain itself and for a test to assert on. */
   public enum Outcome {
      /** The unit is empty and everything went into the network. */
      EMPTIED,
      /** Some moved, some did not: the rest of the network filled up. Nothing was lost. */
      PARTIAL,
      /** The unit was already empty. */
      NOTHING_TO_MOVE,
      /** There is somewhere to put things in principle, but not one item fitted. */
      NO_ROOM,
      /** The tile does not hold a storage unit, or holds one that is not on a network. */
      NOT_A_UNIT,
      /** The unit is the only storage on its network, so there is nowhere for its contents to go. */
      NO_OTHER_UNITS
   }

   /** The outcome plus the numbers worth reporting. Counts are items, not stacks, because that is what a player reads. */
   public static final class Result {

      public final Outcome outcome;
      public final int moved;
      public final int remaining;

      Result(Outcome outcome, int moved, int remaining) {
         this.outcome = outcome;
         this.moved = moved;
         this.remaining = remaining;
      }

      static Result refused(Outcome outcome) {
         return new Result(outcome, 0, 0);
      }

      public boolean ok() {
         return this.outcome == Outcome.EMPTIED;
      }
   }

   /**
    * Empties the unit at this tile into the rest of its network.
    *
    * <p>Server-side only, and called from a container action, which the engine already runs on the server thread --
    * the one place level mutation is safe here. See {@code WORKFLOW.md} on why work marshalled off that thread
    * deadlocks the tick.
    */
   public static Result attempt(Level level, int x, int y, ServerClient client) {
      if (level == null || client == null) {
         return Result.refused(Outcome.NOT_A_UNIT);
      }

      ObjectEntity entity = level.entityManager.getObjectEntity(x, y);
      if (!(entity instanceof NetworkStorage)) {
         return Result.refused(Outcome.NOT_A_UNIT);
      }

      NetworkStorage self = (NetworkStorage)entity;
      Inventory source = self.getInventory();
      if (source == null) {
         return Result.refused(Outcome.NOT_A_UNIT);
      }

      int before = countItems(source);
      if (before == 0) {
         return Result.refused(Outcome.NOTHING_TO_MOVE);
      }

      // Excluded by identity rather than by coordinates: a unit must not be its own destination, or the whole
      // operation is a no-op that reads as a broken button, reporting everything moved while nothing did.
      //
      // This walk needed a conductor test of its own until terminals were made to conduct. Two units placed either
      // side of a terminal with no conduit between them were one network from the terminal's point of view and two
      // from a unit's, so emptying reported "no other units" in a layout every player builds. That is fixed at the
      // source now -- see NetworkConductor -- and this reads the network the same way everything else does.
      List<InventoryRange> targets = new ArrayList<>();
      for (NetworkStorage unit : reachableStorage(level, x, y)) {
         if (unit != self && unit.getInventory() != null) {
            targets.add(new InventoryRange(unit.getInventory()));
         }
      }

      if (targets.isEmpty()) {
         return new Result(Outcome.NO_OTHER_UNITS, 0, before);
      }

      PlayerMob player = client.playerMob;
      int moved = 0;

      for (int slot = 0; slot < source.getSize(); slot++) {
         if (source.isSlotClear(slot)) {
            continue;
         }

         for (InventoryRange target : targets) {
            InventoryItem item = source.getItem(slot);
            if (item == null) {
               break;
            }

            int amountBefore = item.getAmount();
            target.inventory.addItem(level, player, item, target.startSlot, target.endSlot, PURPOSE, null);
            int amountAfter = source.getAmount(slot);
            if (amountAfter != amountBefore) {
               source.markDirty(slot);
               moved += amountBefore - amountAfter;
            }

            if (amountAfter <= 0) {
               source.clearSlot(slot);
               break;
            }
         }
      }

      int remaining = countItems(source);
      if (remaining == 0) {
         return new Result(Outcome.EMPTIED, moved, 0);
      }

      // Partial and no-room are separated because they call for different words: one says how far it got, the other
      // says it could not start. Both leave every item where the player can still reach it.
      return new Result(moved > 0 ? Outcome.PARTIAL : Outcome.NO_ROOM, moved, remaining);
   }

   /** Items rather than stacks, since "moved 340" is what a player can check against the unit they were looking at. */
   private static int countItems(Inventory inventory) {
      int total = 0;

      for (int slot = 0; slot < inventory.getSize(); slot++) {
         if (!inventory.isSlotClear(slot)) {
            total += inventory.getAmount(slot);
         }
      }

      return total;
   }

   /**
    * Every storage on the same network as this tile, including the tile itself.
    *
    * <p>The limits are the terminal's own, so this walk cannot be more expensive than the one a terminal already runs
    * when a player opens it.
    */
   private static List<NetworkStorage> reachableStorage(Level level, int x, int y) {
      List<NetworkStorage> found = new ArrayList<>();

      ObjectEntity self = level.entityManager.getObjectEntity(x, y);
      if (self instanceof NetworkStorage) {
         found.add((NetworkStorage)self);
      }

      found.addAll(
         UnitNetwork.discover(x, y, (tx, ty) -> {
            ObjectEntity candidate = level.entityManager.getObjectEntity(tx, ty);
            if (candidate instanceof NetworkStorage) {
               NetworkStorage member = (NetworkStorage)candidate;
               return member.isOnNetwork() ? member : null;
            }

            return null;
         }, UnitNetwork.conductorsOn(level), arcanestorage.band.BandIndex.linksOn(level),
            StorageTerminalObjectEntity.MAX_UNITS, StorageTerminalObjectEntity.MAX_CONDUITS,
            StorageTerminalObjectEntity.MAX_LINKS)
      );

      return found;
   }
}
