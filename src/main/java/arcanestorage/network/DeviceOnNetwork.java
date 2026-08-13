package arcanestorage.network;

import necesse.inventory.Inventory;
import necesse.inventory.item.Item;
import necesse.level.maps.Level;

/**
 * What the scheduler needs from a device that can move items for a network.
 *
 * <p>An interface rather than a reference to the bus class, for the reason the rest of the network is built on
 * interfaces: another mod's device, or a later one of ours, should be able to join without this package knowing
 * it exists. It also keeps the scheduler honest about how little it needs to know -- a direction, a filter, a
 * number, a container, and the ability to move a given amount of a given item.
 *
 * <p><b>Constraints, not instructions.</b> Nothing here tells a device what to do. The device says what its
 * rules imply about the network, and the scheduler works out the difference. That inversion is the whole reason
 * two devices can no longer fight each other: a contradiction is visible in the constraints before anything
 * moves, whereas two devices each obeying their own instruction cannot see it at all.
 */
public interface DeviceOnNetwork {

   /** Where the device is, as one comparable number. Used for deterministic ordering and leader election. */
   long tileOrder();

   /** True if this device brings items into the network, false if it takes them out. */
   boolean fillsNetwork();

   /** Whether the device's rules concern this item at all. */
   boolean wants(Item item);

   /**
    * How much of an item this device's rules say the network should hold, or {@link NetworkScheduler#NONE}.
    *
    * <p>Read as a ceiling from a device that fills the network and as a floor from one that empties it -- the
    * same number seen from the two sides, which is what lets one panel serve both.
    */
   int targetFor(Item item, NetworkIndex index);

   /** The container the device is attached to, or null when it has none. */
   Inventory container();

   /**
    * Moves up to {@code amount} of an item in this device's own direction.
    *
    * @return how many actually moved, which may be less if the source ran out or the destination was full
    */
   int moveItem(Level level, NetworkIndex index, Item item, int amount);

   /** Recheck whether the device should be running at all, and record why not. */
   void revalidate(NetworkIndex index);

   /** Report that an item keeps moving without the network getting closer to its rules. */
   void reportChurn(Item item);
}
