package arcanestorage.container;

import necesse.engine.localization.Localization;
import necesse.entity.objectEntity.interfaces.OEInventory;
import necesse.inventory.InventoryItem;
import necesse.inventory.container.slots.OEInventoryContainerSlot;

/**
 * A socket on a Station Unit, as the terminal shows it.
 *
 * <p>Exists for one reason: a refused item is otherwise refused <b>silently</b>. {@code ContainerSlot}'s own
 * answer to "why not" is an empty string, which the interface draws as a floating line of no width and no
 * words -- so dropping a forge into a socket left the slot empty with nothing said, and a player reasonably
 * read that as a bug. The engine already has the mechanism for saying why: a click that refuses returns a
 * message, and the slot component floats it over the cursor and plays the failure sound. Nothing else has to
 * know.
 *
 * <p>One message for every refusal, because the two reasons are one rule from where the player stands: a
 * socket takes a crafting station that works from any tile, and a forge, a cooking station or a lump of stone
 * are each not that. Naming the exception would leave the general case unexplained, and two messages would
 * mean deciding which of them an unrecognised modded item deserves.
 *
 * <p>The socket's own rule stays where it belongs, in {@code isItemValid} on the Station Unit, which is asked
 * through {@code super} here so this class cannot disagree with it.
 */
public class StationSocketSlot extends OEInventoryContainerSlot {

   public StationSocketSlot(OEInventory inventory, int inventorySlot) {
      super(inventory, inventorySlot);
   }

   /**
    * The reason to show, or null if the socket takes the item.
    *
    * <p>Delegating the yes/no to the superclass keeps the single source of truth; only the wording is added.
    */
   @Override
   public String getItemInvalidError(InventoryItem item) {
      String error = super.getItemInvalidError(item);
      return error == null ? null : Localization.translate("ui", "arcanestorage_station_refused");
   }
}
