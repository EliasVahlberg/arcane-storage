package arcanestorage;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.Test;

/**
 * Everything this mod registers belongs to its own item and crafting category.
 *
 * <p>Three separate things can break this and only one of them is loud.
 *
 * <p>The loud one is order. {@code ItemCategoryManager.getCategory} throws {@code IllegalStateException} for a tree
 * it has not been told about, and an object's category tree is resolved when its item is generated during
 * registration, so creating the category after the first registration is a crash at load. That failure announces
 * itself and needs no test.
 *
 * <p>The quiet ones are what this guards. A new object registered straight through {@code ObjectRegistry} keeps the
 * default {@code objects} tree and lands in vanilla Furniture, which looks like nothing went wrong. And a missing
 * {@code [itemcategory]} locale entry shows the player the raw string ID as a heading.
 */
public class CategoryGuardTest {

   private static final Path ENTRY = Path.of("src/main/java/arcanestorage/ArcaneStorage.java");
   private static final Path LOCALE = Path.of("src/main/resources/locale/en.lang");

   @Test
   public void bothCategoriesAreCreatedBeforeAnythingIsRegistered() throws IOException {
      String source = Files.readString(ENTRY, StandardCharsets.UTF_8);

      assertTrue("the item category must be created", source.contains("masterManager.createCategory"));
      assertTrue("the crafting category must be created", source.contains("craftingManager.createCategory"));

      int created = source.indexOf("createCategories()");
      int firstRegistration = source.indexOf("registerObject(");
      assertTrue("createCategories() must be called", created >= 0);
      assertTrue(
         "createCategories() must run before the first registration, or resolving the tree throws at load",
         created < firstRegistration);
   }

   @Test
   public void nothingBypassesTheCategorisingHelpers() throws IOException {
      String source = Files.readString(ENTRY, StandardCharsets.UTF_8);

      // The helpers themselves are the only permitted callers, and each contains exactly one call. Anything else
      // registering directly would silently keep the default category.
      assertEquals("ObjectRegistry.registerObject", 1, count(source, "ObjectRegistry.registerObject("));
      assertEquals("ItemRegistry.registerItem", 1, count(source, "ItemRegistry.registerItem("));
   }

   @Test
   public void theCategoryHasADisplayName() throws IOException {
      String locale = Files.readString(LOCALE, StandardCharsets.UTF_8);

      assertTrue("en.lang needs an [itemcategory] section", locale.contains("[itemcategory]"));
      assertTrue(
         "the category needs a display name, or its heading reads as the raw string ID",
         locale.contains("arcanestorage=Arcane Storage"));
   }

   @Test
   public void theSortStringsDoNotCollideWithVanilla() throws IOException {
      String source = Files.readString(ENTRY, StandardCharsets.UTF_8);

      // Vanilla's crafting tree pins its station list at Z-Z-Z. Reusing that would fight it for position.
      assertFalse(
         "Z-Z-Z is vanilla's pinned crafting station block",
         source.contains("craftingManager.createCategory(\"Z-Z-Z\""));
   }

   private static int count(String haystack, String needle) {
      int total = 0;
      for (int at = haystack.indexOf(needle); at >= 0; at = haystack.indexOf(needle, at + 1)) {
         total++;
      }

      return total;
   }

   private static void assertEquals(String what, int expected, int actual) {
      assertTrue(
         what + ": expected " + expected + " direct call inside the helper but found " + actual
            + ". Register through ArcaneStorage's own registerObject/registerItem so the category is set.",
         expected == actual);
   }
}
