package arcanestorage.recipe;

import java.util.HashSet;
import java.util.Set;

import necesse.engine.registries.ItemRegistry;
import necesse.engine.registries.ObjectRegistry;
import necesse.engine.registries.RecipeTechRegistry;
import necesse.inventory.item.ItemCategory;
import necesse.inventory.recipe.Ingredient;
import necesse.inventory.recipe.Recipe;
import necesse.inventory.recipe.Recipes;
import necesse.level.gameObject.GameObject;
import necesse.level.gameObject.container.CraftingStationObject;
import necesse.level.gameObject.container.CraftingStationUpgrade;

/**
 * Turns vanilla {@link CraftingStationUpgrade} ladders into Crafting-tab recipes.
 *
 * <p>Vanilla upgrades a <i>placed</i> station in the world (spend bars, replace the tile). Inside a
 * Storage Terminal the station is an item, so the same ladder has to be a craft: consume the current
 * tier item plus the upgrade cost, produce the next tier item. The base station may come from the
 * player's inventory, a Storage Unit on the network, or a socket in the terminal / a Station Unit —
 * all of those sit in the terminal's craft pool, so the ingredient line counts every copy (e.g. one
 * installed and one in the bag shows as having two toward a need of one).
 *
 * <p>Recipes land under {@code Crafting Stations → Station Upgrades} via
 * {@link Recipe#setCraftingCategory}, use {@link RecipeTechRegistry#NONE} so they are not gated on a
 * bench being installed (the base station <i>is</i> the ingredient), and are recognised later by
 * {@link #isUpgradeRecipe} so the terminal can treat socketed benches as craft materials for them.
 */
public final class StationUpgradeRecipes {

   /** Locale / category id under {@code itemcategory.stationupgrades}. */
   public static final String CATEGORY_ID = "stationupgrades";

   private static final Set<Integer> RECIPE_HASHES = new HashSet<>();

   private StationUpgradeRecipes() {
   }

   /**
    * Creates the subgroup and one recipe per upgradable crafting station object.
    *
    * <p>Must run in {@code postInit}: every station object (vanilla and modded) is registered by then,
    * and {@link Recipes#registerModRecipe} is what the rest of this mod already uses for its own crafts.
    */
   public static void register() {
      // Sort string keeps the subgroup under Crafting Stations without shoving past vanilla's own children.
      ItemCategory.craftingManager.createCategory("U-A-A", "craftingstations", CATEGORY_ID);

      for (GameObject object : ObjectRegistry.getObjects()) {
         if (!(object instanceof CraftingStationObject)) {
            continue;
         }

         CraftingStationUpgrade upgrade = ((CraftingStationObject) object).getStationUpgrade();
         if (upgrade == null || upgrade.upgradeObject == null) {
            continue;
         }

         String baseId = object.getStringID();
         String resultId = upgrade.upgradeObject.getStringID();
         if (baseId == null || resultId == null || baseId.equals(resultId)) {
            continue;
         }

         // Multi-tile helper pieces sometimes share getStationUpgrade with the main object; skip ids that
         // are not real items (ObjectRegistry still lists them).
         if (ItemRegistry.getItemID(baseId) == -1 || ItemRegistry.getItemID(resultId) == -1) {
            continue;
         }

         Ingredient[] cost = upgrade.cost == null ? new Ingredient[0] : upgrade.cost;
         Ingredient[] ingredients = new Ingredient[cost.length + 1];
         ingredients[0] = new Ingredient(baseId, 1);
         System.arraycopy(cost, 0, ingredients, 1, cost.length);

         Recipe recipe = new Recipe(resultId, 1, RecipeTechRegistry.NONE, ingredients)
               .setCraftingCategory("craftingstations", CATEGORY_ID);
         Recipes.registerModRecipe(recipe);
         RECIPE_HASHES.add(recipe.getRecipeHash());
      }
   }

   /** True for recipes this class registered — used to open station sockets as ingredient sources. */
   public static boolean isUpgradeRecipe(Recipe recipe) {
      return recipe != null && RECIPE_HASHES.contains(recipe.getRecipeHash());
   }

   /** How many upgrade recipes were registered this load (tests / diagnostics). */
   public static int registeredCount() {
      return RECIPE_HASHES.size();
   }
}
