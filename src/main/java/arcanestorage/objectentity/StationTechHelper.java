package arcanestorage.objectentity;

import java.util.Collection;

import necesse.engine.registries.RecipeTechRegistry;
import necesse.inventory.InventoryItem;
import necesse.inventory.item.placeableItem.objectItem.ObjectItem;
import necesse.inventory.recipe.Tech;
import necesse.level.gameObject.GameObject;
import necesse.level.gameObject.ProcessingForgeObject;
import necesse.level.gameObject.container.CraftingStationObject;
import necesse.level.gameObject.container.ForgeObject;

/**
 * Shared Station Unit socket rules and tech unlocks for the terminal crafting tab.
 *
 * <p>Most benches still follow the placement rule on {@link StorageTerminalObjectEntity}: if a station
 * needs its tile (fuel, processing inventory, settler workstation state), it cannot be reduced to an
 * item in a socket. The Forge is the deliberate exception. Vanilla registers it as
 * {@link ProcessingForgeObject}, which is <i>not</i> a {@link CraftingStationObject}, so the old check
 * rejected it outright. Installing one here does <b>not</b> run its fueled processor and does
 * <b>not</b> auto-smelt ores sitting in Storage Units — it only unlocks {@link RecipeTechRegistry#FORGE}
 * recipes on the terminal Crafting tab, which craft instantly from network materials with no wood.
 * Settlers keep using a placed forge in the world.
 */
public final class StationTechHelper {

   private StationTechHelper() {
   }

   public static GameObject getObject(InventoryItem item) {
      if (item == null || !(item.item instanceof ObjectItem)) {
         return null;
      }
      return ((ObjectItem) item.item).getObject();
   }

   /** True for the live Processing Forge and the legacy fueled crafting Forge item. */
   public static boolean isForgeStation(GameObject object) {
      return object instanceof ProcessingForgeObject || object instanceof ForgeObject;
   }

   /**
    * Whether this item may sit in a Station Unit / terminal station socket.
    */
   public static boolean isValidStationItem(InventoryItem item) {
      GameObject object = getObject(item);
      if (object == null) {
         return false;
      }
      if (isForgeStation(object)) {
         return true;
      }
      if (object instanceof CraftingStationObject) {
         return !StorageTerminalObjectEntity.needsItsPlacement((CraftingStationObject) object);
      }
      return false;
   }

   /**
    * Techs this installed item unlocks on the terminal, or null if it unlocks none.
    */
   public static Tech[] getStationTechs(InventoryItem item) {
      GameObject object = getObject(item);
      if (object == null) {
         return null;
      }
      if (isForgeStation(object)) {
         return new Tech[]{RecipeTechRegistry.FORGE};
      }
      if (object instanceof CraftingStationObject) {
         return ((CraftingStationObject) object).getCraftingTechs();
      }
      return null;
   }

   public static void addStationTechs(InventoryItem item, Collection<Tech> techs) {
      Tech[] found = getStationTechs(item);
      if (found == null) {
         return;
      }
      for (Tech tech : found) {
         if (tech != null) {
            techs.add(tech);
         }
      }
   }
}
