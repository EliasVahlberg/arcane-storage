package arcanestorage.recipe;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import arcanestorage.object.UnitTier;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import necesse.inventory.recipe.Ingredient;
import org.junit.BeforeClass;
import org.junit.Test;

/**
 * The cost data file, checked against the code that reads it.
 *
 * <p>This is the test that was missing. The unit materials lived in an enum and were copied into a table in the
 * roadmap; the two disagreed for two commits, every test passed throughout, and the commit written to reconcile
 * them checked one file and asserted the other. Moving the numbers into one file removes the copy, and this removes
 * the other half of the problem: nothing previously failed when the code and its data stopped lining up.
 *
 * <p>So the assertions here are about agreement rather than about values. Asserting that Demonic costs ten bars
 * would just be a third copy of the number, and would have to be edited every time the balance is tuned -- which
 * is how a test starts being updated to match whatever the code now does. What is worth pinning is that every tier
 * the enum defines has an entry, that every key the mod asks for exists, and that nothing in the file is ignored.
 */
public class CostTableTest {

   @BeforeClass
   public static void loadTheFile() {
      CostTable.load();
   }

   /**
    * Every rung has materials.
    *
    * <p>The case this exists for is adding a fifth tier. The enum would compile, the ladder would register, and
    * the missing cost would only be discovered by a player at a workstation -- or worse, not discovered, if a
    * lookup ever returned an empty ingredient list instead of throwing.
    */
   @Test
   public void everyTierInTheEnumHasCostsInTheFile() {
      for (UnitTier tier : UnitTier.values()) {
         Ingredient[] materials = CostTable.materials(tier.costKey());

         assertTrue(
            tier + " has no materials in recipes.properties, which would make it free to craft",
            materials.length > 0
         );

         for (Ingredient ingredient : materials) {
            assertTrue(
               tier + " has a non-positive amount for " + ingredient.ingredientStringID,
               ingredient.getIngredientAmount() > 0
            );
            assertFalse(
               tier + " has an empty item ID",
               ingredient.ingredientStringID.trim().isEmpty()
            );
         }
      }
   }

   /** The keys {@code ArcaneStorage.postInit} asks for by name, which throw rather than defaulting if absent. */
   @Test
   public void everyRecipeTheModRegistersHasCosts() {
      for (String key : Arrays.asList("recipe.terminal", "recipe.conduit", "recipe.importbus", "recipe.exportbus",
            "recipe.wirelessterminal")) {
         assertTrue(key + " is missing from recipes.properties", CostTable.materials(key).length > 0);
         assertTrue(key + " yields a non-positive count", CostTable.count(key) > 0);
      }
   }

   /**
    * Nothing in the file is unread.
    *
    * <p>A key nobody reads is almost always a typo in a key that was supposed to be read, and the consequence of
    * that particular typo is a cost silently reverting to whatever the code does without it.
    */
   @Test
   public void theFileContainsNothingUnused() {
      Set<String> expected = new HashSet<>(
         Arrays.asList("recipe.terminal", "recipe.conduit", "recipe.importbus", "recipe.exportbus",
            "recipe.wirelessterminal")
      );

      for (UnitTier tier : UnitTier.values()) {
         expected.add(tier.costKey());
      }

      assertEquals("recipes.properties and the code disagree about which costs exist", expected, CostTable.keys());
   }

   /**
    * No rung above the base may be priced in a global ingredient.
    *
    * <p>Not style. {@code anylog} is a {@code GlobalIngredientRegistry} entry rather than an item, so no inventory
    * can be asked how many it holds -- which is exactly what an in-place upgrade must do before it will offer a
    * button. Pricing a higher rung that way would not rebalance the upgrade, it would break the affordability
    * check, and the diff would look like a pricing change.
    *
    * <p><b>This checks the name, not the registry, and the first version of it checked the registry and was
    * wrong.</b> {@code Ingredient.isGlobalIngredient()} is decided in the constructor by asking
    * {@code ItemRegistry.getItemID}, and in a JUnit run no game has loaded, so the registry is empty and every
    * ingredient reports itself as global -- the test failed on {@code demonicbar}, which is an ordinary item. The
    * naming convention is reliable in its place: every global the game registers begins with {@code any}
    * ({@code anylog}, {@code anycoolingfuel}, {@code anycommonfish}, {@code anytier<N>essence}).
    *
    * <p>The real check is behavioural and already exists in the Python suite, where a game is loaded and the
    * registry is real: those tests hand over the exact materials and require the upgrade to be affordable, which
    * a global ingredient would make impossible.
    */
   @Test
   public void noTierAboveTheBaseIsPricedInAGlobalIngredient() {
      for (UnitTier tier : UnitTier.values()) {
         if (tier == UnitTier.BASE) {
            continue;
         }

         for (Ingredient ingredient : CostTable.materials(tier.costKey())) {
            assertFalse(
               tier + " is priced in " + ingredient.ingredientStringID + ", whose name marks it as a global "
                  + "ingredient. An in-place upgrade cannot count those, so the panel could never enable its button.",
               ingredient.ingredientStringID.startsWith("any")
            );
         }
      }
   }
}
