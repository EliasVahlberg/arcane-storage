package arcanestorage.network;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

import necesse.engine.GameLog;
import necesse.entity.objectEntity.ObjectEntity;
import necesse.inventory.Inventory;
import necesse.inventory.InventoryItem;
import necesse.inventory.item.Item;
import necesse.level.maps.Level;

/**
 * Decides what one network should move, and how much of it per tick.
 *
 * <p><b>What this replaces.</b> Each bus used to run its own timer, wake once a second, and move a single stack
 * of a single item. Two faults followed from that and neither was a tuning problem. A chest holding eight kinds
 * took eight seconds to drain because a bus could only think about one kind per wake-up. And two buses with
 * contradictory rules moved the same items back and forth forever, because a device acting on its own rule
 * cannot see that another device is undoing it: measured, twelve moves in a hundred and twenty ticks while the
 * network's total never left twenty. Rate limiting could hide the churn but not resolve it.
 *
 * <p><b>The shape.</b> Constraints, not instructions. An import bus says the network should hold up to C of an
 * item; an export bus says it should hold at least F. The scheduler holds a set of {@code (network, item)} pairs
 * that something has disturbed, and for each one works out the difference between what the network holds and
 * what the rules say it should, then asks the buses to close that difference. Nothing is planned ahead: the set
 * records <i>what to look at</i>, never <i>what to do</i>.
 *
 * <p>That last point is a deliberate departure from the original specification, which had the queue carry
 * computed deltas tagged with the index version they were planned under, revalidated at drain and recomputed on
 * mismatch. Storing items instead of deltas reaches the same property -- no action is ever applied against a
 * state it was not computed for -- by construction rather than by checking, because computation and application
 * are never separated in time. It is less machinery and it cannot be got wrong.
 *
 * <p><b>Nothing polls.</b> Work arrives from four places: a change to a member inventory or to a bus's
 * container, reported by the {@code updateSlot} hook; a rule edited; the layout changing; and our own moves,
 * which is what keeps a large transfer going over several ticks. When none of those happen the set is empty and
 * the network does nothing at all. The one exception is honest and small: a slow heartbeat re-checks device
 * states, because some things no trigger covers -- a vanilla chest placed or broken beside a bus tells us
 * nothing, and a bus with nowhere to put items must still be able to say so.
 *
 * <p><b>Determinism.</b> Items are resolved in the order they became dirty, and the buses that act on each are
 * ordered by tile. The same state must always produce the same moves, or none of this is testable.
 */
public final class NetworkScheduler {

   /**
    * How many moves one network may make per tick.
    *
    * <p>This is the whole of the rate policy, and it lives here rather than in a device because rate is a
    * property of a network and correctness is a property of the rules. Eight is enough that a chest of mixed
    * items empties in a tick or two instead of a second per kind, and small enough that a big transfer is still
    * spread over ticks rather than done in one lurch. Nothing about correctness depends on the number, which is
    * the point of separating them: it can be tuned, or made an upgrade, without touching the resolver.
    */
   public static final int MOVES_PER_TICK = 8;

   /**
    * How often device states are re-checked when nothing has triggered a check.
    *
    * <p>One second, and this is the honest exception to "nothing polls". The triggers cover rules, layout and
    * contents; what they cannot cover is a vanilla container appearing or disappearing next to a bus, because a
    * chest is not ours and tells us nothing when it is placed or broken. So the states are re-derived on a
    * heartbeat -- one per network rather than one per bus, and cheap: four tile lookups and a walk over a
    * handful of devices. The expensive part, the item-by-item conflict comparison, runs only when two opposed
    * buses actually share a container.
    *
    * <p>It is deliberately not slower than a second. A stale state is only a display problem now that a stale
    * state cannot stop a device working, but a player who has just placed a chest should not read "no
    * container" for three seconds either.
    */
   public static final int HEARTBEAT_TICKS = 20;

   /** The window the churn detector measures over, in ticks. */
   private static final int CHURN_WINDOW = 100;

   /**
    * How many moves of one item within a window count as churn, if the network is no closer to resting.
    *
    * <p>Forty is comfortably above any legitimate transfer this budget can produce for a single item over five
    * seconds while the count is actually converging, and comfortably below what a genuine loop generates.
    */
   private static final int CHURN_MOVES = 40;

   private final NetworkIndex index;

   /** Items something has disturbed, in the order they were disturbed. */
   private final LinkedHashSet<Item> dirty = new LinkedHashSet<>();

   /** Items the churn detector has given up on. Cleared when the rules or the layout change. */
   private final LinkedHashSet<Item> stalled = new LinkedHashSet<>();

   private final Map<Item, NetworkScheduler.Churn> churn = new HashMap<>();

   private boolean everythingDirty = true;

   private boolean validationDue = true;

   private long lastHeartbeat;

   private long lastRun;

   /** Moves made, and moves the budget deferred to a later tick. Diagnostic. */
   public static long scheduled;

   public static long deferred;

   public static long resolves;

   NetworkScheduler(NetworkIndex index) {
      this.index = index;
   }

   /** Something changed one item on this network. */
   public void markDirty(Item item) {
      if (item != null && !this.stalled.contains(item)) {
         this.dirty.add(item);
      }
   }

   /**
    * Everything has to be reconsidered and every device revalidated.
    *
    * <p>Called when the rules change, when the layout changes, and when a slot went empty in a watched
    * container -- in that last case because an emptied slot does not say what left it.
    *
    * <p>Also clears the churn record: a player who has just changed a rule deserves a fresh judgement, and the
    * change they made may be exactly the fix.
    */
   public void reconsiderEverything() {
      this.everythingDirty = true;
      this.validationDue = true;
   }

   /**
    * Membership changed, so every device's state must be re-derived before the next heartbeat.
    *
    * <p>Called when a walking device adopts its own view of membership into a shared index. Without it, a
    * device joining an existing network is not evaluated until that network's heartbeat next fires -- which is
    * up to {@link #HEARTBEAT_TICKS} ticks away, because the heartbeat's phase belongs to the network the device
    * has just joined and not to the device.
    *
    * <p>That delay is not cosmetic. A device's state starts out {@code ACTIVE}, and {@code ACTIVE} permits
    * work, so a bus placed onto an established network could move items for up to a second before anything
    * checked whether it should -- long enough to act against a rule conflict it would have been stopped for.
    * The permissive default is deliberate elsewhere (a device should not refuse to work merely because it has
    * not been looked at yet), which is precisely why joining has to trigger the look.
    */
   void membershipChanged() {
      this.validationDue = true;
   }

   /**
    * The rules changed: reconsider everything, and forget what was learned about churn.
    *
    * <p>Kept separate from {@link #reconsiderEverything()} deliberately, and the distinction cost a real bug.
    * Emptying a slot in a watched chest also asks the network to reconsider, and while that shared the clearing
    * it wiped the churn history several times a second -- so the detector could never accumulate the evidence it
    * needs and a genuine loop ran forever. Forgetting is right only when the <i>rules</i> change, because the
    * player deserves a fresh judgement and the change they just made may be the fix.
    */
   public void rulesChanged() {
      this.reconsiderEverything();
      this.stalled.clear();
      this.churn.clear();
   }

   /** Whether an item has been given up on, so a device can explain itself. */
   public boolean isStalled(Item item) {
      return this.stalled.contains(item);
   }

   /**
    * The first given-up item a device's rules concern, or null.
    *
    * <p>Exists because the heartbeat re-derives every device's state, and without this it would cheerfully
    * overwrite a churn stop with "active" a second after declaring it -- the device would go back to work, the
    * loop would resume, and the only evidence would be a line in the log. A stop has to be recomputable from
    * the same state as everything else, or it does not survive.
    */
   public Item stalledItemFor(DeviceOnNetwork device) {
      for (Item item : this.stalled) {
         if (device.wants(item)) {
            return item;
         }
      }

      return null;
   }

   /**
    * Runs one tick for this network. Called by the leader device only.
    *
    * @return how many moves were made
    */
   int tick(Level level, long now) {
      if (now == this.lastRun) {
         // Two devices both believing they lead is not supposed to happen, but if it ever did, the network
         // would silently get twice its budget. Cheaper to make it impossible here than to rely on it.
         return 0;
      }

      this.lastRun = now;

      if (this.validationDue || now - this.lastHeartbeat >= HEARTBEAT_TICKS) {
         this.lastHeartbeat = now;
         this.validationDue = false;
         this.validate();
      }

      if (this.everythingDirty) {
         this.everythingDirty = false;
         this.dirtyEverything();
      }

      if (this.dirty.isEmpty()) {
         return 0;
      }

      resolves++;
      return this.drain(level, now);
   }

   /** Asks every device to work out whether it should be running. */
   private void validate() {
      for (DeviceOnNetwork device : this.devices()) {
         device.revalidate(this.index);
      }
   }

   /**
    * Everything the network holds, plus everything its devices can see, becomes dirty.
    *
    * <p>Used when the rules or the layout change, since either can alter the answer for an item that is not in
    * the network at all -- an import rule for stone matters when the network holds none.
    */
   private void dirtyEverything() {
      for (Item item : this.index.kindsHeld()) {
         this.markDirty(item);
      }

      for (DeviceOnNetwork device : this.devices()) {
         Inventory container = device.container();
         if (container == null) {
            continue;
         }

         for (int slot = 0; slot < container.getSize(); slot++) {
            InventoryItem item = container.getItem(slot);
            if (item != null) {
               this.markDirty(item.item);
            }
         }
      }
   }

   /**
    * Works through the dirty items under the tick's budget.
    *
    * <p>An item is resolved at the moment it is acted on, never earlier, so there is no such thing as a plan
    * that has gone stale. An item the budget could not reach stays dirty and is reconsidered next tick against
    * whatever is true then.
    */
   private int drain(Level level, long now) {
      int budget = MOVES_PER_TICK;
      List<Item> considering = new ArrayList<>(this.dirty);

      for (Item item : considering) {
         if (budget <= 0) {
            deferred++;
            break;
         }

         this.dirty.remove(item);
         int moved = this.resolve(level, item, budget, now);
         budget -= moved;

         if (moved > 0) {
            // Still work to do on this item, so it stays on the list rather than waiting for the next
            // inventory change to remind us. This is what carries a large transfer across ticks.
            this.dirty.add(item);
         }
      }

      return MOVES_PER_TICK - budget;
   }

   /**
    * Closes the gap between what the network holds of one item and what the rules say it should.
    *
    * <p>Ceiling and floor are read from the devices rather than stored: they are the rules, and the rules are
    * the player's. The count comes from the index. Nothing else is consulted, which is what makes the decision
    * reproducible from the world alone.
    */
   private int resolve(Level level, Item item, int budget, long now) {
      List<DeviceOnNetwork> devices = this.devices();
      int held = this.index.of(item);

      // The highest ceiling any filling device asks for, and the lowest floor any emptying device insists on.
      // Highest and lowest because a device's number is what it wants the network to hold, so the network must
      // satisfy the most demanding one in each direction -- and a filling device with no number at all is
      // unbounded, which beats every finite ceiling.
      boolean unbounded = false;
      int ceiling = NONE;
      int floor = NONE;

      for (DeviceOnNetwork device : devices) {
         if (!device.wants(item)) {
            continue;
         }

         int target = device.targetFor(item, this.index);

         if (device.fillsNetwork()) {
            if (target == NONE) {
               unbounded = true;
            } else {
               ceiling = ceiling == NONE ? target : Math.max(ceiling, target);
            }
         } else {
            int asFloor = Math.max(target, 0);
            floor = floor == NONE ? asFloor : Math.min(floor, asFloor);
         }
      }

      if (unbounded) {
         ceiling = Integer.MAX_VALUE;
      }

      int moved = 0;

      if (ceiling != NONE && held < ceiling) {
         moved += this.pull(level, item, ceiling - held, budget, devices, now);
      }

      if (floor != NONE && this.index.of(item) > floor && moved < budget) {
         moved += this.push(level, item, this.index.of(item) - floor, budget - moved, devices, now);
      }

      return moved;
   }

   /** Asks the import buses to bring an item in, up to the room the ceiling leaves. */
   private int pull(Level level, Item item, int room, int budget, List<DeviceOnNetwork> devices, long now) {
      int moved = 0;

      for (DeviceOnNetwork device : devices) {
         if (moved >= budget || room <= 0) {
            break;
         }

         if (!device.fillsNetwork() || !device.wants(item)) {
            continue;
         }

         int did = device.moveItem(level, this.index, item, room);
         if (did > 0) {
            moved++;
            room -= did;
            this.recordMove(item, now);
         }
      }

      return moved;
   }

   /** Asks the export buses to take an item out, down to the floor. */
   private int push(Level level, Item item, int surplus, int budget, List<DeviceOnNetwork> devices, long now) {
      int moved = 0;

      for (DeviceOnNetwork device : devices) {
         if (moved >= budget || surplus <= 0) {
            break;
         }

         if (device.fillsNetwork() || !device.wants(item)) {
            continue;
         }

         int did = device.moveItem(level, this.index, item, surplus);
         if (did > 0) {
            moved++;
            surplus -= did;
            this.recordMove(item, now);
         }
      }

      return moved;
   }

   /**
    * Watches for an item that keeps moving without the network getting any closer to resting.
    *
    * <p>The static check catches a contradiction it can see -- two buses with incompatible numbers on one
    * container. It cannot see a loop closed outside the network: a settler hauling items back, a hopper, another
    * mod's pipe. This is the backstop for those, and it works on evidence rather than on structure: many moves,
    * no net progress, so stop and say so. Failing closed is right for a storage mod, since moving items wrongly
    * forever is worse than not moving them.
    */
   private void recordMove(Item item, long now) {
      scheduled++;

      NetworkScheduler.Churn record = this.churn.get(item);
      if (record == null || now - record.windowStart > CHURN_WINDOW) {
         this.churn.put(item, new NetworkScheduler.Churn(now, this.index.of(item)));
         return;
      }

      record.moves++;
      if (record.moves < CHURN_MOVES) {
         return;
      }

      int progress = Math.abs(this.index.of(item) - record.countAtStart);
      if (progress > 0) {
         // Moving and getting somewhere. Start a fresh window from where it has got to.
         this.churn.put(item, new NetworkScheduler.Churn(now, this.index.of(item)));
         return;
      }

      this.stalled.add(item);
      this.dirty.remove(item);
      GameLog.warn.println("Arcane Storage: " + item.getStringID() + " moved " + record.moves
         + " times in " + CHURN_WINDOW + " ticks without the network getting closer to its rules. "
         + "Something outside the network is undoing the work, so it has been stopped.");

      for (DeviceOnNetwork device : this.devices()) {
         if (device.wants(item)) {
            device.reportChurn(item);
         }
      }
   }

   /**
    * The devices on this network that can act.
    *
    * <p><b>Devices that are no longer what their tile holds are excluded, and that is load-bearing rather
    * than tidy.</b> Two things put a stale device in this list: the engine defers entity removal to the end of
    * a tick, so a broken device lingers; and a displaced entity can still report {@code removed() == false}
    * while no longer being what the level serves at its tile. {@link NetworkIndexes#isCurrent} covers both,
    * because only one of the two is detectable from the entity's own flags.
    *
    * <p>The worst thing a stale device gets wrong is leader election. Leadership is the lowest tile order, so a
    * stale device can elect itself, and nothing drives a scheduler on behalf of an entity that either does not
    * tick or that no reader can see: no heartbeat, no revalidation, no transfers. The network recovers only at
    * the next topology change, which for a player looks like a network that quietly died and then healed
    * itself when they touched something unrelated.
    *
    * <p>The other two are milder. {@code validate} would re-derive the state of an entity nobody reads -- and
    * that is exactly the failure which exposed this: a ghost bus's state was maintained perfectly on every
    * heartbeat while the bus actually standing on the tile never got evaluated once and sat in its initial
    * state indefinitely. {@code dirtyEverything} would walk a container the device no longer has, scheduling
    * work for items that are not there.
    *
    * <p>This is the second bug of this shape -- {@code BusObjectEntity.assignOrdinal} counted removed peers
    * and misnumbered buses. <b>Any code that iterates entities and concludes something from the set must first
    * ask whether each one is still current.</b>
    */
   private List<DeviceOnNetwork> devices() {
      List<DeviceOnNetwork> found = new ArrayList<>();

      for (ObjectEntity device : this.index.devices()) {
         if (device instanceof DeviceOnNetwork && NetworkIndexes.isCurrent(device)) {
            found.add((DeviceOnNetwork)device);
         }
      }

      // Tile order, so the same state always produces the same moves in the same order. Discovery order would
      // depend on which device happened to walk the network first.
      found.sort((a, b) -> Long.compare(a.tileOrder(), b.tileOrder()));
      return found;
   }

   /**
    * Whether a device leads this network's scheduling.
    *
    * <p>The lowest tile order wins. It needs no election and no state: every device computes the same answer
    * from the same list, and when the leader is broken the next one takes over as soon as the layout change
    * rebuilds the list. Deliberately not {@code LevelData} or {@code WorldData}, which only exist when loaded
    * from save data and so are absent on a freshly generated world.
    */
   boolean leads(DeviceOnNetwork device) {
      long lowest = Long.MAX_VALUE;
      DeviceOnNetwork leader = null;

      for (DeviceOnNetwork candidate : this.devices()) {
         long order = candidate.tileOrder();
         if (order < lowest) {
            lowest = order;
            leader = candidate;
         }
      }

      return leader == device;
   }

   /** Whether any device on this network is leading it. For diagnosis: an unled network does nothing. */
   public boolean hasLeader() {
      return !this.devices().isEmpty();
   }

   /** How many items are waiting to be looked at. For diagnosis. */
   public int pending() {
      return this.dirty.size();
   }

   /** How many items have been given up on. For diagnosis. */
   public int stalledCount() {
      return this.stalled.size();
   }

   /** No ceiling or no floor, as distinct from a limit of zero. */
   public static final int NONE = -1;

   /** One item's recent history, for the churn detector. */
   private static final class Churn {
      private final long windowStart;
      private final int countAtStart;
      private int moves = 1;

      private Churn(long windowStart, int countAtStart) {
         this.windowStart = windowStart;
         this.countAtStart = countAtStart;
      }
   }
}
