package arcanestorage.upgrade;

import arcanestorage.network.NetworkConductor;
import arcanestorage.network.NetworkIndexes;
import arcanestorage.network.NetworkStorage;
import arcanestorage.network.UnitNetwork;
import arcanestorage.object.StationUnitObject;
import arcanestorage.object.StorageUnitObject;
import arcanestorage.object.UnitTier;
import arcanestorage.objectentity.StorageTerminalObjectEntity;
import java.util.ArrayList;
import java.util.List;
import necesse.engine.network.server.ServerClient;
import necesse.engine.registries.ItemRegistry;
import necesse.engine.registries.ObjectRegistry;
import necesse.entity.objectEntity.InventoryObjectEntity;
import necesse.entity.objectEntity.ObjectEntity;
import necesse.entity.mobs.PlayerMob;
import necesse.inventory.Inventory;
import necesse.inventory.InventoryItem;
import necesse.inventory.item.Item;
import necesse.inventory.recipe.Ingredient;
import necesse.level.gameObject.GameObject;
import necesse.level.maps.Level;

/**
 * Upgrading a placed unit to the next tier without picking it up.
 *
 * <p>The reason this exists as its own class rather than living on the object is that <b>the whole value of
 * it is the ordering</b>, and ordering is easier to defend when it is written down once, in one method, with
 * nothing else in the file to distract from it. Crafting the next tier already works; what it costs a player
 * is emptying a unit first, and a storage mod that asks the player to move forty stacks by hand to grow a
 * container has not solved the problem it exists for.
 *
 * <h2>Why the order is what it is</h2>
 *
 * <p>{@code Level.setObject} destroys the old object entity: it calls {@code replaceObjectEntity} internally
 * whenever the object ID changes, which builds a fresh entity from the new object and drops the old one from
 * the manager. The old inventory is <b>not</b> emptied onto the ground first -- verified by reading
 * {@code ObjectRegionLayer.setObjectByRegion}, which touches wires, lighting, settlement rooms and level jobs
 * but never the entity's contents. So the engine neither loses the items for us nor saves them for us: between
 * that call and the refill, the only copy of the player's belongings is the array on this stack.
 *
 * <p>Which fixes the sequence:
 *
 * <ol>
 *   <li><b>Validate everything first.</b> A missing material, a top-tier unit or an unregistered target
 *       returns before anything has been mutated, so the common failure costs the player nothing.
 *   <li><b>Consume the materials, then snapshot.</b> This order is not interchangeable -- materials may be
 *       stored <i>in the unit being upgraded</i>, so a snapshot taken first would restore the very bars that
 *       were just spent and hand out a free upgrade.
 *   <li><b>Swap the object.</b> One call, nothing between it and the refill.
 *   <li><b>Refill by exact slot index.</b> Not {@code addItem}: slot order is preserved so nothing appears to
 *       shuffle, and stacks are never merged or split, which is what keeps per-stack data intact.
 *   <li><b>Anything that cannot be placed is dropped as a pickup.</b>
 * </ol>
 *
 * <p>Capacity only ever grows, so step 5 is unreachable by construction -- a 40-slot inventory always fits in
 * an 80-slot one. It is written anyway because "unreachable" is a claim about today's tier table, and the cost
 * of being wrong is a player's chest evaporating. <b>Items on the floor are a bug report; items gone are an
 * uninstall.</b>
 *
 * <h2>Modded items</h2>
 *
 * <p>The refill moves the {@link InventoryItem} references themselves, not a description of them. Nothing is
 * reconstructed from a string ID and a count, so enchantments, durability, and any custom data another mod
 * hangs off an item survive because they were never examined in the first place. An upgrade cannot understand
 * a modded item wrongly if it never tries to understand it.
 */
public final class UnitUpgrade {

   /** Purpose string for inventory operations, so filters and hooks can tell this apart from a player action. */
   public static final String PURPOSE = "arcanestorageupgrade";

   /**
    * Which of a player's inventories count, matching what vanilla consumables do.
    *
    * <p>{@code PlayerInventoryManager} takes four independent flags -- inactive armour sets, cloud storage,
    * the trash slot, temporary slots -- and vanilla's scroll and shop code disagrees about them: a scroll is
    * spent from the bag alone, while a shop reaches into inactive sets and cloud storage for coins. The bag
    * alone is the right reading here, because it is the only one a player can see when the panel says how many
    * they have.
    *
    * <p>The reason this is a named constant rather than four literals at each call site is that <b>counting
    * and removing must agree exactly.</b> If the count included a store the removal did not, the panel would
    * enable its button and the upgrade would then fail the availability check it had already passed -- the one
    * path that ends in a partial consumption and a refund.
    */
   private static final boolean INACTIVE_SETS = false;

   private static final boolean CLOUD = false;

   private static final boolean TRASH = false;

   private static final boolean TEMP = false;

   private UnitUpgrade() {
   }

   /** What happened, in enough detail for the UI to explain itself and for a test to assert on. */
   public enum Outcome {
      /** Done. */
      UPGRADED,
      /** The tile holds something that is not a tiered unit. */
      NOT_A_UNIT,
      /** Already at the top of the ladder. */
      AT_TOP_TIER,
      /** The next tier's object is not registered, which would be a bug in this mod rather than a player error. */
      UNREGISTERED_TARGET,
      /** A cost names a global ingredient, which no inventory can be asked to count. Also a bug, not a player error. */
      UNCOUNTABLE_COST,
      /** Not enough materials across the player's inventory and the network. Nothing was consumed. */
      MISSING_MATERIALS,
      /** The swap did not produce a unit to refill. Contents were dropped at the tile rather than discarded. */
      TRANSFER_FAILED
   }

   /** The outcome plus the numbers worth reporting. */
   public static final class Result {

      public final Outcome outcome;
      public final int slotsBefore;
      public final int slotsAfter;
      /** Stacks moved into the new unit. */
      public final int carried;
      /** Stacks that had to be dropped on the floor instead. Any value above zero is a bug worth chasing. */
      public final int dropped;

      Result(Outcome outcome, int slotsBefore, int slotsAfter, int carried, int dropped) {
         this.outcome = outcome;
         this.slotsBefore = slotsBefore;
         this.slotsAfter = slotsAfter;
         this.carried = carried;
         this.dropped = dropped;
      }

      static Result refused(Outcome outcome) {
         return new Result(outcome, 0, 0, 0, 0);
      }

      public boolean ok() {
         return this.outcome == Outcome.UPGRADED;
      }
   }

   /** The tier of the unit on this tile, or null if it is not a tiered unit. */
   public static UnitTier tierAt(Level level, int x, int y) {
      if (level == null) {
         return null;
      }

      GameObject object = level.getObject(x, y);
      if (object instanceof StorageUnitObject) {
         return ((StorageUnitObject)object).tier;
      }

      return object instanceof StationUnitObject ? ((StationUnitObject)object).tier : null;
   }

   /** True when the tile holds a Station Unit rather than a Storage Unit, which decides the target's string ID. */
   public static boolean isStation(Level level, int x, int y) {
      return level != null && level.getObject(x, y) instanceof StationUnitObject;
   }

   /** The string ID this tile would become, or null if there is no tier above it. */
   public static String targetId(Level level, int x, int y) {
      UnitTier tier = tierAt(level, x, y);
      if (tier == null) {
         return null;
      }

      UnitTier next = tier.next();
      if (next == null) {
         return null;
      }

      return isStation(level, x, y) ? next.stationId() : next.storageId();
   }

   /**
    * What the in-place upgrade costs, or null if there is nothing to upgrade to.
    *
    * <p>The tier below is not part of it: the unit on the tile is that ingredient. See
    * {@link UnitTier#upgradeCost()}.
    */
   public static Ingredient[] cost(Level level, int x, int y) {
      UnitTier tier = tierAt(level, x, y);
      if (tier == null) {
         return null;
      }

      UnitTier next = tier.next();
      return next == null ? null : next.upgradeCost();
   }

   /**
    * The storage this upgrade may draw materials from: the unit itself, plus everything its network reaches.
    *
    * <p>The unit is included deliberately. Materials a player has already filed away are the materials they
    * think they have, and refusing to spend the bars sitting inside the very thing being upgraded would read
    * as the mod not being able to see its own contents. It is safe only because the snapshot is taken after
    * consumption.
    */
   public static List<NetworkStorage> pool(Level level, int x, int y) {
      List<NetworkStorage> pool = new ArrayList<>();
      if (level == null) {
         return pool;
      }

      ObjectEntity self = level.entityManager.getObjectEntity(x, y);
      if (self instanceof NetworkStorage) {
         pool.add((NetworkStorage)self);
      }

      pool.addAll(
         UnitNetwork.discover(x, y, (tx, ty) -> {
            ObjectEntity candidate = level.entityManager.getObjectEntity(tx, ty);
            if (candidate instanceof NetworkStorage) {
               NetworkStorage member = (NetworkStorage)candidate;
               return member.isOnNetwork() ? member : null;
            }

            return null;
         }, (tx, ty) -> level.getObject(tx, ty) instanceof NetworkConductor,
            StorageTerminalObjectEntity.MAX_UNITS, StorageTerminalObjectEntity.MAX_CONDUITS)
      );

      return pool;
   }

   /** How many of an ingredient sit in the player's own inventory. */
   public static int inPlayer(Level level, PlayerMob player, Ingredient ingredient) {
      Item item = itemOf(ingredient);
      if (item == null || player == null || player.getInv() == null) {
         return 0;
      }

      return player.getInv().getAmount(item, INACTIVE_SETS, CLOUD, TRASH, TEMP, PURPOSE);
   }

   /** How many of an ingredient sit in the network, counted by walking slots rather than trusting an index. */
   public static int inNetwork(List<NetworkStorage> pool, Ingredient ingredient) {
      Item item = itemOf(ingredient);
      if (item == null) {
         return 0;
      }

      int total = 0;

      for (NetworkStorage unit : pool) {
         Inventory inventory = unit.getInventory();
         if (inventory == null) {
            continue;
         }

         for (int slot = 0; slot < inventory.getSize(); slot++) {
            InventoryItem held = inventory.getItem(slot);
            if (held != null && held.item == item) {
               total += held.getAmount();
            }
         }
      }

      return total;
   }

   /**
    * The number the UI shows against a requirement: inventory plus network.
    *
    * <p>Counted by walking slots rather than reading the network's aggregate index, because the index is
    * maintained incrementally and a stale count here would let a player press a button that then fails. The
    * walk is bounded by the same unit cap the terminal uses and happens only while somebody is looking at the
    * panel.
    */
   public static int available(Level level, PlayerMob player, List<NetworkStorage> pool, Ingredient ingredient) {
      return inPlayer(level, player, ingredient) + inNetwork(pool, ingredient);
   }

   /** Whether every requirement is met, for greying out the button. */
   public static boolean affordable(Level level, PlayerMob player, int x, int y) {
      Ingredient[] cost = cost(level, x, y);
      if (cost == null) {
         return false;
      }

      List<NetworkStorage> pool = pool(level, x, y);

      for (Ingredient ingredient : cost) {
         if (available(level, player, pool, ingredient) < ingredient.getIngredientAmount()) {
            return false;
         }
      }

      return true;
   }

   private static Item itemOf(Ingredient ingredient) {
      if (ingredient == null || ingredient.isGlobalIngredient()) {
         return null;
      }

      return ItemRegistry.getItem(ingredient.ingredientStringID);
   }

   /**
    * Performs the upgrade, or refuses without touching anything.
    *
    * <p>Server-side only. Callers on the client have nothing to do here.
    */
   public static Result attempt(Level level, int x, int y, ServerClient client) {
      if (level == null || !level.isServer() || client == null) {
         return Result.refused(Outcome.NOT_A_UNIT);
      }

      UnitTier tier = tierAt(level, x, y);
      if (tier == null) {
         return Result.refused(Outcome.NOT_A_UNIT);
      }

      ObjectEntity existing = level.entityManager.getObjectEntity(x, y);
      if (!(existing instanceof InventoryObjectEntity)) {
         return Result.refused(Outcome.NOT_A_UNIT);
      }

      if (tier.next() == null) {
         return Result.refused(Outcome.AT_TOP_TIER);
      }

      String targetId = targetId(level, x, y);
      int targetObjectId = targetId == null ? -1 : ObjectRegistry.getObjectID(targetId);
      if (targetObjectId <= 0) {
         return Result.refused(Outcome.UNREGISTERED_TARGET);
      }

      Ingredient[] cost = cost(level, x, y);
      PlayerMob player = client.playerMob;
      List<NetworkStorage> pool = pool(level, x, y);

      // Every cost must be a real item, and every one must be affordable, before a single item moves.
      for (Ingredient ingredient : cost) {
         if (itemOf(ingredient) == null) {
            return Result.refused(Outcome.UNCOUNTABLE_COST);
         }

         if (available(level, player, pool, ingredient) < ingredient.getIngredientAmount()) {
            return Result.refused(Outcome.MISSING_MATERIALS);
         }
      }

      // Consume. Materials are fungible, so what is recorded for a rollback is amounts rather than slots --
      // unlike the stored contents below, where the exact stack is what matters.
      List<InventoryItem> taken = new ArrayList<>();

      for (Ingredient ingredient : cost) {
         int outstanding = ingredient.getIngredientAmount() - takeFromPlayer(level, player, ingredient, taken);
         if (outstanding > 0) {
            outstanding -= takeFromNetwork(pool, ingredient, outstanding, taken);
         }

         if (outstanding > 0) {
            // The count said yes and the removal disagreed. Give everything back and change nothing else.
            refund(level, player, x, y, taken);
            return Result.refused(Outcome.MISSING_MATERIALS);
         }
      }

      // Only now: the materials are gone, so anything still in this unit is genuinely the player's to keep.
      InventoryObjectEntity before = (InventoryObjectEntity)existing;
      Inventory old = before.getInventory();
      int slotsBefore = old.getSize();
      InventoryItem[] carried = new InventoryItem[slotsBefore];

      for (int slot = 0; slot < slotsBefore; slot++) {
         carried[slot] = old.getItem(slot);
      }

      // Destroys the old entity and builds the new one, at the new tier's slot count.
      level.setObject(x, y, targetObjectId);

      ObjectEntity replacement = level.entityManager.getObjectEntity(x, y);
      if (!(replacement instanceof InventoryObjectEntity)) {
         // Should be impossible: the object is registered and its entity is unconditional. Put the contents on
         // the floor anyway -- recoverable beats gone.
         int dropped = dropAll(level, x, y, carried, 0);
         NetworkIndexes.topologyChanged();
         return new Result(Outcome.TRANSFER_FAILED, slotsBefore, 0, 0, dropped);
      }

      Inventory now = ((InventoryObjectEntity)replacement).getInventory();
      int slotsAfter = now.getSize();
      int moved = 0;

      for (int slot = 0; slot < Math.min(slotsBefore, slotsAfter); slot++) {
         if (carried[slot] != null) {
            // Direct slot write on purpose: bypasses isItemValid so a Station Unit's installed benches
            // transfer, preserves socket order, which is how sockets are addressed, and never merges stacks.
            now.setItem(slot, carried[slot]);
            moved++;
         }
      }

      int dropped = dropAll(level, x, y, carried, slotsAfter);
      NetworkIndexes.topologyChanged();
      return new Result(Outcome.UPGRADED, slotsBefore, slotsAfter, moved, dropped);
   }

   private static int takeFromPlayer(Level level, PlayerMob player, Ingredient ingredient, List<InventoryItem> taken) {
      Item item = itemOf(ingredient);
      if (item == null || player == null || player.getInv() == null) {
         return 0;
      }

      int removed = player.getInv()
         .removeItems(item, ingredient.getIngredientAmount(), INACTIVE_SETS, CLOUD, TRASH, TEMP, PURPOSE);
      if (removed > 0) {
         taken.add(new InventoryItem(item, removed));
      }

      return removed;
   }

   /**
    * Takes the remainder from the network, walking slots directly.
    *
    * <p>Deliberately not {@code Inventory.removeItems}: that resolves items through filters and a player
    * perspective, which is right for a player reaching into a chest and wrong for a machine spending its own
    * contents. Slot arithmetic here means the amount removed is known exactly, which is what makes the refund
    * path trustworthy.
    */
   private static int takeFromNetwork(List<NetworkStorage> pool, Ingredient ingredient, int wanted, List<InventoryItem> taken) {
      Item item = itemOf(ingredient);
      if (item == null) {
         return 0;
      }

      int got = 0;

      for (NetworkStorage unit : pool) {
         Inventory inventory = unit.getInventory();
         if (inventory == null) {
            continue;
         }

         for (int slot = 0; slot < inventory.getSize() && got < wanted; slot++) {
            InventoryItem held = inventory.getItem(slot);
            if (held == null || held.item != item) {
               continue;
            }

            int take = Math.min(wanted - got, held.getAmount());
            taken.add(new InventoryItem(item, take));
            got += take;

            if (take >= held.getAmount()) {
               inventory.setItem(slot, null);
            } else {
               held.setAmount(held.getAmount() - take);
               inventory.updateSlot(slot);
            }
         }

         if (got >= wanted) {
            break;
         }
      }

      return got;
   }

   /** Undoes a partial consumption: back to the player where it fits, onto the floor where it does not. */
   private static void refund(Level level, PlayerMob player, int x, int y, List<InventoryItem> taken) {
      for (InventoryItem item : taken) {
         if (player != null && player.getInv() != null && player.getInv().addItem(item, false, PURPOSE)) {
            continue;
         }

         level.entityManager.pickups.add(item.getPickupEntity(level, x * 32 + 16, y * 32 + 16));
      }
   }

   /** Drops every stack from {@code from} onwards, and returns how many were dropped. */
   private static int dropAll(Level level, int x, int y, InventoryItem[] carried, int from) {
      int dropped = 0;

      for (int slot = from; slot < carried.length; slot++) {
         if (carried[slot] != null) {
            level.entityManager.pickups.add(carried[slot].getPickupEntity(level, x * 32 + 16, y * 32 + 16));
            dropped++;
         }
      }

      return dropped;
   }
}
