package arcanestorage.objectentity;

import arcanestorage.network.NetworkIndexes;
import arcanestorage.network.NetworkStations;
import necesse.entity.objectEntity.InventoryObjectEntity;
import necesse.entity.objectEntity.ObjectEntity;
import necesse.inventory.InventoryItem;
import necesse.level.gameObject.container.CraftingStationObject;
import necesse.level.maps.Level;

/**
 * Object entity behind a Station Unit — sockets for crafting benches, on the network.
 *
 * <p>The slots are the same shape as the ones that used to live on the terminal, including the rule
 * about what may go in them, which is reused from {@link StorageTerminalObjectEntity} rather than
 * restated. That rule is worth remembering: a bench is accepted only if it does not override any of
 * the hooks a station uses to ask where it is, because a station that needs its tile cannot be reduced
 * to an item in a slot.
 *
 * <p>What changed is where they live and how many there are. Ten slots on the terminal was a number
 * chosen to fill a row; a Station Unit makes station capacity a thing the player places and pays for,
 * the same way capacity already comes from Storage Units and reach already comes from conduits. Free
 * station access was the one capability that broke that grammar.
 */
public class StationUnitObjectEntity extends InventoryObjectEntity implements NetworkStations {

   /** Must never change between versions, or saved sockets load as invalid and come back empty. */
   public static final String TYPE = "arcanestoragestationunit";

   public StationUnitObjectEntity(Level level, int x, int y, int slots) {
      super(level, x, y, slots);
      this.type = TYPE;

      // A unit appearing changes what networks exist, and this catches the paths the object's placement
      // hook does not: a world loading, and a test placing one directly.
      NetworkIndexes.topologyChanged();
   }

   /**
    * Only crafting stations, and only ones that do not need their own tile.
    *
    * <p>Delegates to the terminal's rule deliberately. The two must agree exactly -- a bench installable
    * in a socket but unusable by the terminal, or the reverse, is a bug the player experiences as the
    * interface lying -- and the cheapest guarantee of agreement is one implementation.
    */
   @Override
   public boolean isItemValid(int slot, InventoryItem item) {
      CraftingStationObject station = StorageTerminalObjectEntity.getCraftingStation(item);
      return station != null && !StorageTerminalObjectEntity.needsItsPlacement(station);
   }

   /** How many sockets hold a bench, for the interact readout. */
   public int getUsedSlots() {
      return this.inventory.getUsedSlots();
   }

   /**
    * A socket is not storage, so the three bulk-transfer conventions are refused.
    *
    * <p>{@code OEInventory} defaults all of these to true, which is right for a chest and wrong here.
    * Left alone, the terminal's own quick-stack would try to file loose items into sockets, and sorting
    * would reorder installed benches -- which matters because socket order is what addresses them.
    * {@code isItemValid} would reject the items, so nothing would be corrupted, but the buttons would
    * appear to do nothing, and a control that silently fails is worse than one that is absent.
    */
   @Override
   public boolean canQuickStackInventory() {
      return false;
   }

   @Override
   public boolean canRestockInventory() {
      return false;
   }

   @Override
   public boolean canSortInventory() {
      return false;
   }

   /**
    * And settlers are told there is nothing here for them.
    *
    * <p>The default derives a settlement storage range from the inventory, so adding a Station Unit to
    * settlement storage would advertise the installed benches as items to be hauled away. A hauler
    * dismantling the network's crafting capability while tidying up is exactly the kind of emergent
    * behaviour that is funny once and then a bug report.
    */
   @Override
   public necesse.inventory.InventoryRange getSettlementStorage() {
      return null;
   }

   @Override
   public ObjectEntity getObjectEntity() {
      return this;
   }
}
