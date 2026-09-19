package arcanestorage.objectentity;

import java.util.Collection;

import necesse.engine.registries.RecipeTechRegistry;
import necesse.inventory.InventoryItem;
import necesse.inventory.item.placeableItem.objectItem.ObjectItem;
import necesse.inventory.recipe.Tech;
import necesse.level.gameObject.GameObject;
import necesse.level.gameObject.ProcessingForgeObject;
import necesse.level.gameObject.container.CraftingStationObject;
import necesse.level.gameObject.container.FueledCraftingStationObject;
import necesse.level.gameObject.container.ForgeObject;
import necesse.level.gameObject.container.GrainMillBaseObject;

/**
 * Shared Station Unit socket rules and tech unlocks for the terminal crafting tab.
 *
 * <p>Most benches still follow the placement rule on {@link StorageTerminalObjectEntity}: if a station
 * needs its tile (fuel, processing inventory, settler workstation state), it cannot be reduced to an
 * item in a socket. Four families are deliberate exceptions:
 *
 * <ul>
 *   <li>The {@linkplain ProcessingForgeObject processing forge} (and the legacy {@link ForgeObject}),
 *       which are not installable as ordinary crafting stations.</li>
 *   <li>{@link FueledCraftingStationObject}s — cooking station, roasting station, cooking pot, and
 *       any other fueled craft bench — which normally refuse install because they need a fuel OE on
 *       the tile.</li>
 *   <li>{@link GrainMillBaseObject} — vanilla's grain mill is a processing settler workstation, not a
 *       {@link CraftingStationObject}. There is no tiered upgrade; multi-tile helper pieces share this
 *       base class.</li>
 *   <li>Necesse Expanded's <b>Keg</b> ({@code "keg"} object / recipe tech) — another processing
 *       workstation that is not a {@link CraftingStationObject}. Detected by string id so this mod
 *       does not hard-depend on Expanded.</li>
 * </ul>
 *
 * <p>Installing any of these does <b>not</b> run the fueled/processing machine and does <b>not</b>
 * consume wood or auto-cook/smelt/mill/ferment items sitting in Storage Units. It only unlocks that
 * station's techs ({@link CraftingStationObject#getCraftingTechs()}, {@link RecipeTechRegistry#FORGE},
 * {@link RecipeTechRegistry#GRAIN_MILL}, or Expanded's {@code keg} tech) on the terminal Crafting tab,
 * which craft instantly from network materials. Settlers keep using a placed station in the world.
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
    * Fueled craft benches (cooking station, roasting station, cooking pot, legacy forge, …).
    *
    * <p>These are {@link CraftingStationObject}s that {@link StorageTerminalObjectEntity#needsItsPlacement}
    * rejects because they create a fuel object-entity. Allowed here as recipe-tech unlocks only.
    */
   public static boolean isFueledCraftStation(GameObject object) {
      return object instanceof FueledCraftingStationObject;
   }

   /**
    * Grain mill (and its multi-tile helper pieces). Not a {@link CraftingStationObject}; unlocks
    * {@link RecipeTechRegistry#GRAIN_MILL} only — no vanilla tier upgrades exist.
    */
   public static boolean isGrainMillStation(GameObject object) {
      return object instanceof GrainMillBaseObject;
   }

   /**
    * Necesse Expanded Keg ({@code object id "keg"}). Soft-detected so Arcane Storage stays
    * playable without Expanded; when the mod is present, unlocks its {@code keg} fermenting tech.
    */
   public static boolean isKegStation(GameObject object) {
      return object != null && "keg".equals(object.getStringID());
   }

   /** {@code RecipeTechRegistry.getTech("keg")}, or null if Expanded (or the tech) is absent. */
   public static Tech getKegTechOrNull() {
      try {
         return RecipeTechRegistry.getTech("keg");
      } catch (java.util.NoSuchElementException ignored) {
         return null;
      }
   }

   /**
    * Whether this item may sit in a Station Unit / terminal station socket.
    */
   public static boolean isValidStationItem(InventoryItem item) {
      GameObject object = getObject(item);
      if (object == null) {
         return false;
      }
      // Processing forge is not a CraftingStationObject at all.
      if (object instanceof ProcessingForgeObject) {
         return true;
      }
      // Grain mill is a processing settler workstation, not a crafting station.
      if (isGrainMillStation(object)) {
         return true;
      }
      // Necesse Expanded keg — same shape as the mill (processing OE, not CraftingStationObject).
      if (isKegStation(object)) {
         return true;
      }
      // Cooking / roasting / cooking pot / legacy forge: recipe unlock without fuel OE.
      if (isFueledCraftStation(object)) {
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
      if (object instanceof ProcessingForgeObject) {
         return new Tech[]{RecipeTechRegistry.FORGE};
      }
      if (isGrainMillStation(object)) {
         return new Tech[]{RecipeTechRegistry.GRAIN_MILL};
      }
      if (isKegStation(object)) {
         Tech keg = getKegTechOrNull();
         return keg == null ? null : new Tech[]{keg};
      }
      if (object instanceof CraftingStationObject) {
         // Cooking station returns cooking + pot + roasting; pot/roaster return their own tech.
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
