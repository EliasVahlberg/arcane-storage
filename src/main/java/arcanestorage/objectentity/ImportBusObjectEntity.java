package arcanestorage.objectentity;

import java.util.Collections;
import java.util.List;

import arcanestorage.network.NetworkStorage;
import necesse.inventory.Inventory;
import necesse.level.maps.Level;

/**
 * Moves a neighbouring container's contents into the network.
 *
 * <p>The answer to "can ordinary chests join the network", and deliberately an indirect one. Settlers go
 * on using a chest they understand and can reach; the bus carries what they deposit across; the network
 * itself is never exposed to settler access. A player who wants their hunters' meat in the terminal
 * places a bus, not a permission.
 *
 * <p>Empty rules move everything, because importing only ever adds to the network. See
 * {@link arcanestorage.network.TransferRules} for why the export bus is the opposite.
 */
public class ImportBusObjectEntity extends BusObjectEntity {

   public ImportBusObjectEntity(Level level, int x, int y) {
      super(level, "arcanestorageimportbus", x, y, true);
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
