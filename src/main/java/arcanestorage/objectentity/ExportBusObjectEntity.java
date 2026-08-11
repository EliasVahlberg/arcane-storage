package arcanestorage.objectentity;

import java.util.Collections;
import java.util.List;

import arcanestorage.network.NetworkStorage;
import necesse.inventory.Inventory;
import necesse.level.maps.Level;

/**
 * Pushes items out of the network into a neighbouring container, on a rule.
 *
 * <p>The natural target is a Shipping Chest, which already sells what it holds through trader missions —
 * so "sell my surplus" is a rule plus a chest the game already ships, and needs no selling machinery
 * here. An ordinary chest works too, which is how a player feeds settlers from the network without giving
 * settlers the network.
 *
 * <p><b>Inert until configured, on purpose.</b> With no rules this moves nothing, unlike the import bus.
 * Exporting removes from storage, and a bus that emptied the network into a chest the moment it was
 * placed would be a trap rather than a feature.
 */
public class ExportBusObjectEntity extends BusObjectEntity {

   public ExportBusObjectEntity(Level level, int x, int y) {
      super(level, "arcanestorageexportbus", x, y, false);
   }

   @Override
   protected List<Inventory> sources(List<NetworkStorage> network, Inventory container) {
      return inventoriesOf(network);
   }

   @Override
   protected List<Inventory> destinations(List<NetworkStorage> network, Inventory container) {
      return Collections.singletonList(container);
   }
}
