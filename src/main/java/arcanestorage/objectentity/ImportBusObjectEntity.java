package arcanestorage.objectentity;

import java.util.Collections;
import java.util.List;

import arcanestorage.network.NetworkStorage;
import necesse.inventory.Inventory;
import necesse.inventory.item.Item;
import necesse.level.maps.Level;

/**
 * Moves a neighbouring container's contents into the network.
 *
 * <p>The answer to "can ordinary chests join the network", and deliberately an indirect one. Settlers go
 * on using a chest they understand and can reach; the bus carries what they deposit across; the network
 * itself is never exposed to settler access. A player who wants their hunters' meat in the terminal
 * places a bus, not a permission.
 *
 * <p>An unconfigured import bus moves everything, because importing only ever adds to the network. The
 * export bus is the opposite, and that asymmetry is a safety property rather than a setting.
 */
public class ImportBusObjectEntity extends BusObjectEntity {

   public ImportBusObjectEntity(Level level, int x, int y) {
      super(level, "arcanestorageimportbus", x, y, true);
   }

   /**
    * The network is the destination here, so a number means "fill up to this much and stop" -- which is
    * what the same number means on the same panel when a player configures a settlement chest.
    */
   @Override
   protected int allowedToMove(Item item, int inSource, int inDestination, BusObjectEntity.Holdings network) {
      if (!this.filter.isItemAllowed(item)) {
         return 0;
      }

      int target = this.networkShouldHold(item, network);
      return target == NO_TARGET ? inSource : Math.max(0, Math.min(inSource, target - inDestination));
   }

   /** Into the network. */
   @Override
   protected boolean movesIntoNetwork() {
      return true;
   }

   @Override
   protected List<Inventory> sources(List<NetworkStorage> network, Inventory container) {
      return Collections.singletonList(container);
   }

   @Override
   protected List<Inventory> destinations(List<NetworkStorage> network, Inventory container) {
      return inventoriesOf(network);
   }
}
