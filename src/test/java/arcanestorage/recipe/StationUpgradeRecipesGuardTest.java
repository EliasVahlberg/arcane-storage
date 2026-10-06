package arcanestorage.recipe;

import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.Test;

/**
 * Station Upgrades must stay wired: category, registration site, and the terminal's NONE-tech exception.
 *
 * <p>Read from source — same shape as {@code ViewControlsGuardTest} — because exercising
 * {@link StationUpgradeRecipes#register()} needs a live ObjectRegistry.
 */
public class StationUpgradeRecipesGuardTest {

   private static final Path RECIPE_CLASS =
      Path.of("src/main/java/arcanestorage/recipe/StationUpgradeRecipes.java");
   private static final Path MOD = Path.of("src/main/java/arcanestorage/ArcaneStorage.java");
   private static final Path CONTAINER =
      Path.of("src/main/java/arcanestorage/container/StorageTerminalContainer.java");
   private static final Path LOCALE = Path.of("src/main/resources/locale/en.lang");

   @Test
   public void upgradesRegisterUnderCraftingStationsSubgroup() throws IOException {
      String recipe = Files.readString(RECIPE_CLASS, StandardCharsets.UTF_8);
      String locale = Files.readString(LOCALE, StandardCharsets.UTF_8);

      assertTrue("category id must be stationupgrades", recipe.contains("\"stationupgrades\""));
      assertTrue(
         "must nest under craftingstations so Fine grouping shows Crafting Stations → Station Upgrades",
         recipe.contains("createCategory(\"U-A-A\", \"craftingstations\", CATEGORY_ID)")
            || recipe.contains("createCategory(\"U-A-A\", \"craftingstations\", \"stationupgrades\")"));
      assertTrue("en.lang must name the subgroup", locale.contains("stationupgrades=Station Upgrades"));
      int itemCategory = locale.indexOf("[itemcategory]");
      int stationUpgrades = locale.indexOf("stationupgrades=Station Upgrades");
      assertTrue(
         "stationupgrades must live under [itemcategory] (ItemCategory display names), not [ui]",
         itemCategory >= 0 && stationUpgrades > itemCategory);
   }

   @Test
   public void upgradesAreRegisteredFromPostInit() throws IOException {
      String mod = Files.readString(MOD, StandardCharsets.UTF_8);
      assertTrue(
         "postInit must call StationUpgradeRecipes.register after objects exist",
         mod.contains("StationUpgradeRecipes.register()"));
   }

   @Test
   public void terminalKeepsUpgradeRecipesDespiteExcludingHandCrafts() throws IOException {
      String container = Files.readString(CONTAINER, StandardCharsets.UTF_8);

      assertTrue(
         "streamRecipes filter must keep StationUpgradeRecipes through the NONE exclusion",
         container.contains("StationUpgradeRecipes.isUpgradeRecipe(recipe)"));
      assertTrue(
         "station sockets must stay in the craft pool so an installed bench can be spent",
         container.contains("this.craftPool = new LinkedHashSet<>(this.craftInventories)")
            && !container.contains("this.craftPool.remove(terminal.inventory)"));
   }
}
