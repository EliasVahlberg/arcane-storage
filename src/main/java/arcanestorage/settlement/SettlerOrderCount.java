package arcanestorage.settlement;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.function.Predicate;
import java.util.function.ToIntFunction;

import arcanestorage.ArcaneStorage;
import arcanestorage.network.NetworkStorage;
import necesse.engine.GameLog;
import necesse.entity.mobs.Mob;
import necesse.entity.mobs.job.EntityJobWorker;
import necesse.inventory.Inventory;
import necesse.inventory.InventoryItem;
import necesse.level.maps.Level;
import necesse.level.maps.levelData.settlementData.ServerSettlementData;
import necesse.level.maps.levelData.settlementData.settler.SettlerMob;

/**
 * What a settler's "craft until N" order should be told the settlement already has.
 *
 * <p><b>The problem this solves, in one sentence:</b> the engine counts a settlement order against the storage the
 * settlement has <i>assigned</i>, so a player who guides a chest into settlement storage and puts an export bus beside
 * it -- sending the results into Storage Units -- has an order that crafts forever, because the count it compares
 * against never rises. The work is really being done; it is just being counted somewhere the engine does not look.
 *
 * <p><b>Only the output limit is changed, and that is the load-bearing half of the design.</b> Ingredients are
 * counted by the same engine method, and settlers cannot take items from a Storage Unit -- nothing in this mod gives
 * them a range into one. Counting units toward what an order has, in order to decide it is finished, is a ceiling and
 * the wrong direction to over-shoot from; counting them as materials an order can use would have a settler accept a
 * job it cannot finish. So the ingredients stay exactly as the engine has them.
 *
 * <p><b>What counts as "the settlement's" storage here:</b> a Storage Unit whose own tile is inside the settlement's
 * bounds -- the same rectangle the engine's own assignment check uses -- and which the engine is not already counting
 * itself. That last clause is what stops a unit the player <i>did</i> assign to settlement storage being added twice,
 * once by the engine and once here.
 *
 * <p>The seam is a method patch on the one engine call that makes this decision; see
 * {@code arcanestorage.patch.SettlerOrderCountPatch}. It has no overridable hook, which is why a patch rather than an
 * implementation was the only choice available.
 */
public final class SettlerOrderCount {

   /** A ceiling on failed attempts that are logged, matching the other patch in this mod. */
   private static final long LOG_LIMIT = 3L;

   private static volatile long calls;

   private static volatile long failures;

   private SettlerOrderCount() {
   }

   /**
    * The whole rule: the engine's own count, plus matching stock in the settlement's Storage Units, capped at the
    * order's number.
    *
    * <p>Never returns more than {@code max}, so a network holding a thousand iron bars cannot make an order of fifty
    * look unfinished -- and never returns less than {@code vanilla}, so a failure to find anything can only ever leave
    * the order behaving as it did before this existed.
    *
    * <p>Called from inlined advice, so everything it touches has to be reachable from the patched engine class:
    * public methods only, on public types.
    */
   public static int withUnits(EntityJobWorker worker, Predicate<InventoryItem> matches, int vanilla, int max) {
      calls++;

      try {
         if (vanilla >= max || !enabled()) {
            return vanilla;
         }

         List<Inventory> units = unitsFor(worker);
         if (units.isEmpty()) {
            return vanilla;
         }

         return towards(vanilla, max, new UnitContents(units), matches, InventoryItem::getAmount);
      } catch (Throwable t) {
         failed(t);
         return vanilla;
      }
   }

   /**
    * Adds up matching amounts until the order's number is reached.
    *
    * <p>Generic over the entry type rather than taking inventories directly, because an {@code InventoryItem} cannot be
    * constructed outside a running game -- it builds a GND data map, whose registry only exists once the game has
    * loaded -- so a rule written against items could not be unit-tested at all. Written this way the arithmetic, the
    * matching and the early-out are all exercised in a second by plain values, and the inventory half is only the walk
    * below.
    *
    * <p>The early-out is not an optimisation but the reason a large network is affordable here: this runs during
    * settler job selection, and an order of fifty must not walk two thousand slots to work out that it has enough. It
    * stops as soon as {@code max} is reached, and an {@link Iterable} that is abandoned stops being pulled from.
    */
   public static <T> int towards(int already, int max, Iterable<? extends T> entries, Predicate<? super T> matches,
         ToIntFunction<? super T> amount) {
      int total = Math.min(already, max);

      for (T entry : entries) {
         if (entry == null || !matches.test(entry)) {
            continue;
         }

         total += amount.applyAsInt(entry);
         if (total >= max) {
            return max;
         }
      }

      return total;
   }

   /**
    * The Storage Units that make up this worker's settlement stock, or an empty list.
    *
    * <p>Three conditions, each of which is a bug if dropped:
    *
    * <ul>
    *   <li><b>Inside the settlement's bounds</b>, tested on the unit's own tile with the engine's own rectangle. A
    *       unit in somebody else's base, or one the player placed outside the flag's reach, is not this settlement's
    *       stock and an order here must not be satisfied by it.
    *   <li><b>Not one the engine already counts.</b> A unit the player assigned to settlement storage is already
    *       counted by the engine's own pass over assigned inventories; adding it again would report twice the stock
    *       and stop the order at half the number asked for.
    *   <li><b>Not removed.</b> A broken unit's entity can still be reachable for a tick, and its inventory is no
    *       longer anything a player can see or use.
    * </ul>
    *
    * <p>A worker that is not a settler, or is not inside its own settlement at this moment, gets nothing: the engine
    * itself asks this question for settlers only, and adding stock for anybody else would be a second rule.
    *
    * <p>Public because the harness reports the set itself, not only the total: "the total is right" and "the right
    * units were gathered" are different claims, and a boundary mistake in one unit can hide behind a matching number
    * in another.
    */
   public static List<Inventory> unitsFor(EntityJobWorker worker) {
      List<Inventory> units = new ArrayList<>();

      Mob mob = worker == null ? null : worker.getMobWorker();
      if (!(mob instanceof SettlerMob)) {
         return units;
      }

      SettlerMob settler = (SettlerMob)mob;
      if (!settler.isSettler() || !settler.isSettlerWithinSettlement()) {
         return units;
      }

      Level level = mob.getLevel();
      if (level == null || !level.isServer() || level.getServer() == null) {
         return units;
      }

      ServerSettlementData settlement = necesse.engine.world.worldData.SettlementsWorldData
            .getSettlementsData(level.getServer())
            .getServerDataAtTile(level.getIdentifier(), mob.getTileX(), mob.getTileY());
      if (settlement == null) {
         return units;
      }

      for (necesse.entity.objectEntity.ObjectEntity entity : level.entityManager.objectEntities) {
         if (!(entity instanceof NetworkStorage) || entity.removed()) {
            continue;
         }

         if (!settlement.networkData.isTileWithinBounds(entity.tileX, entity.tileY)) {
            continue;
         }

         // Counted by the engine already. `hasInventoryAtTile` is exactly the set the engine's own pass walks, and
         // its size is the reason the whole question is worth asking: the reported loop is a chest that IS assigned
         // beside units that are not.
         if (settlement.storageManager.hasInventoryAtTile(entity.tileX, entity.tileY)) {
            continue;
         }

         Inventory inventory = ((NetworkStorage)entity).getInventory();
         if (inventory != null) {
            units.add(inventory);
         }
      }

      return units;
   }

   /** Whether the player has this behaviour switched on. Off means vanilla, exactly. */
   public static boolean enabled() {
      return ArcaneStorage.SETTINGS == null || ArcaneStorage.SETTINGS.settlerOrderCountsUnits;
   }

   /**
    * How many times the advice has run, including the times it gave up.
    *
    * <p>Read by the harness, because "the patch did not apply" and "the patch applied and found nothing" look
    * identical from every other angle, and the first of those means the fix does nothing at all.
    */
   public static long calls() {
      return calls;
   }

   /** How many of those ended in an exception, counted even once logging stops. */
   public static long failures() {
      return failures;
   }

   /**
    * Reports a failure that escaped {@link #withUnits}, rate-limited.
    *
    * <p>Public because ByteBuddy inlines the calling advice into the engine class, so anything the advice touches has
    * to be reachable from there. The three-line limit is because this runs during settler job selection: a fault that
    * repeats would fill the log faster than anything else in it and bury the first occurrence, which is the one worth
    * reading.
    */
   public static void failed(Throwable cause) {
      long seen = ++failures;
      if (seen <= LOG_LIMIT) {
         GameLog.warn.println("Arcane Storage: a settler's craft-until count could not include this mod's storage ("
               + cause + "). The order sees only settlement storage, as it did before, so it may keep crafting past"
               + " its number until this is fixed."
               + (seen == LOG_LIMIT ? " Further occurrences will not be logged." : ""));
         cause.printStackTrace();
      }
   }

   /**
    * Proves the patch is actually woven, by running the real engine method and watching this class be asked.
    *
    * <p>Same technique as the inventory index's own verification, and it exists for the same reason: a
    * {@code @ModMethodPatch} binds to one exact signature, so a game update that changes it leaves the advice silently
    * unwoven. The engine logs a line when a patch applies and a warning when one matches nothing, but neither is
    * something a test can read, and "the fix does nothing" is exactly the failure this feature cannot afford.
    *
    * <p>The worker is a stub: the engine's own body only asks it for a job restrict zone and a stream of jobs, so an
    * empty stream and a null zone let it run to completion, and this class only ever reads its mob, which is null. The
    * call is deliberately the real static method rather than this class's rule, because the question is whether the
    * bytecode was edited.
    */
   public static boolean verify() {
      long before = calls;

      try {
         EntityJobWorker probe = (EntityJobWorker)java.lang.reflect.Proxy.newProxyInstance(
               EntityJobWorker.class.getClassLoader(),
               new Class<?>[] {EntityJobWorker.class},
               (proxy, method, args) -> {
                  if ("streamValidJobsWithinRange".equals(method.getName())) {
                     return java.util.stream.Stream.empty();
                  }

                  if ("getJobRestrictZone".equals(method.getName())) {
                     return null;
                  }

                  return null;
               });

         necesse.level.maps.levelData.jobs.HasStorageLevelJob.getItemCount(probe, item -> false, 1, false, false);
      } catch (Throwable t) {
         GameLog.warn.println("Arcane Storage: the craft-until count patch could not be verified (" + t + ").");
         return false;
      }

      boolean woven = calls > before;
      if (!woven) {
         GameLog.warn.println("Arcane Storage: the HasStorageLevelJob.getItemCount patch did not apply, so stock in"
               + " Storage Units will not count toward a settler's craft-until order. This usually means the game"
               + " updated and the method's signature changed.");
      }

      return woven;
   }

   /**
    * Every stored stack across a set of inventories, in slot order, without copying anything.
    *
    * <p>Lazy on purpose: the caller stops at the order's number, and an eager list of every stack on a large network
    * would do the work the early-out exists to avoid.
    */
   private static final class UnitContents implements Iterable<InventoryItem> {

      private final Iterable<Inventory> inventories;

      UnitContents(Iterable<Inventory> inventories) {
         this.inventories = inventories;
      }

      @Override
      public Iterator<InventoryItem> iterator() {
         Iterator<Inventory> outer = this.inventories.iterator();

         return new Iterator<InventoryItem>() {
            private Inventory current;
            private int slot;
            private InventoryItem next;
            private boolean ready;

            @Override
            public boolean hasNext() {
               while (!this.ready) {
                  if (this.current != null && this.slot < this.current.getSize()) {
                     InventoryItem item = this.current.getItem(this.slot++);
                     if (item != null) {
                        this.next = item;
                        this.ready = true;
                     }

                     continue;
                  }

                  if (!outer.hasNext()) {
                     return false;
                  }

                  this.current = outer.next();
                  this.slot = 0;
               }

               return true;
            }

            @Override
            public InventoryItem next() {
               if (!this.hasNext()) {
                  throw new NoSuchElementException();
               }

               this.ready = false;
               return this.next;
            }
         };
      }
   }
}
