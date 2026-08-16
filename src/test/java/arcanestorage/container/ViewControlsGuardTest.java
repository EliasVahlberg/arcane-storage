package arcanestorage.container;

import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.Test;

/**
 * The terminal's two view controls, in the two ways each can silently stop working.
 *
 * <p>Both are read from source rather than exercised, for the reason {@code ListSortGuardTest} gives: the enums are
 * private members of a form, and a form needs a client to instantiate.
 *
 * <h2>Sorting persists, filtering does not</h2>
 *
 * <p>That asymmetry is deliberate and easy to undo by accident, so it is asserted in both directions. A sort order is
 * a preference: a player who chose largest-first meant it, and having to choose again every time is the complaint that
 * produced the feature. A filter is a question being asked right now, and a terminal that reopens still filtered looks
 * like a terminal that has lost its contents.
 */
public class ViewControlsGuardTest {

   private static final Path FORM =
      Path.of("src/main/java/arcanestorage/container/StorageTerminalContainerForm.java");
   private static final Path SETTINGS = Path.of("src/main/java/arcanestorage/ArcaneStorageSettings.java");
   private static final Path LOCALE = Path.of("src/main/resources/locale/en.lang");

   @Test
   public void theSortModeIsRestoredAndWrittenBack() throws IOException {
      String form = Files.readString(FORM, StandardCharsets.UTF_8);
      String settings = Files.readString(SETTINGS, StandardCharsets.UTF_8);

      assertTrue(
         "the sort mode must start from the config rather than from the enum's first value",
         form.contains("SortMode.of(ArcaneStorage.SETTINGS.sortMode)"));
      assertTrue(
         "changing the sort mode must write it back",
         form.contains("SETTINGS.sortMode = this.sortMode.settingValue()"));
      assertTrue(
         "and must ask the game to save, which is what vanilla's own crafting checkboxes do",
         form.contains("Settings.saveClientSettings()"));
      assertTrue("the config must declare the key", settings.contains("addSafeString(\"sortMode\""));
      assertTrue("and read it back", settings.contains("getSafeString(\"sortMode\""));
   }

   @Test
   public void theStackFilterIsNotPersisted() throws IOException {
      String form = Files.readString(FORM, StandardCharsets.UTF_8);
      String settings = Files.readString(SETTINGS, StandardCharsets.UTF_8);

      assertTrue(
         "the stack filter must start from ALL every time the terminal opens",
         form.contains("stackFilter = StackFilter.ALL"));
      assertTrue(
         "the stack filter must not reach the config file: a terminal reopening filtered reads as broken",
         !settings.contains("stackFilter") && !settings.contains("stackMode"));
   }

   @Test
   public void theStackFilterIsActuallyApplied() throws IOException {
      String form = Files.readString(FORM, StandardCharsets.UTF_8);

      // A toggle that cycles and badges correctly while the grid ignores it is exactly the fault the sort button
      // shipped with, so the call that does the work is asserted rather than the button that triggers it.
      assertTrue(
         "the grid build must consult the stack filter",
         form.contains("stackFilter.accepts(item)"));
   }

   @Test
   public void everyStackFilterStateIsNamedAndMarked() throws IOException {
      String form = Files.readString(FORM, StandardCharsets.UTF_8);
      String locale = Files.readString(LOCALE, StandardCharsets.UTF_8);

      for (String key : new String[] {"arcanestorage_stack_all", "arcanestorage_stack_stackable",
            "arcanestorage_stack_single"}) {
         assertTrue("en.lang needs " + key + ", or the tooltip shows a raw ID", locale.contains(key + "="));
      }

      assertTrue("the tooltip template is needed too", locale.contains("arcanestorage_stacktip="));

      // ALL carries no badge on purpose, so only the two active states are checked for one.
      assertTrue("STACKABLE needs its mark", form.contains("STACKABLE(\"arcanestorage_stack_stackable\", \"+\")"));
      assertTrue("SINGLE needs its mark", form.contains("SINGLE(\"arcanestorage_stack_single\", \"1\")"));
   }

   @Test
   public void gearIsTestedByStackSizeRatherThanEquipmentCategory() throws IOException {
      String form = Files.readString(FORM, StandardCharsets.UTF_8);

      // Vanilla only ever puts weapons and armor in equipmentManager, so a pickaxe and a trinket are absent from it.
      // Switching this test to that category would quietly reclassify tools and trinkets as materials.
      assertTrue("the filter must test stack size", form.contains("item.item.getStackSize()"));
      // Narrowed to a call rather than the word, because the enum's javadoc names equipmentManager in order to
      // explain why it is the wrong test. A guard that forbids discussing the alternative is a guard that gets
      // deleted along with the explanation.
      assertTrue(
         "equipmentManager omits tools and trinkets and must not become the test for gear",
         !form.contains("equipmentManager.getItemsCategory") && !form.contains("equipmentManager.setItemCategory"));
   }
}
