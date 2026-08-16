package arcanestorage.container;

import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.Test;

/**
 * The terminal's item grid must tell the engine not to sort it.
 *
 * <p>{@code FormItemList} defaults its {@code sorted} field to true, and in {@code WAIT_FULl} mode it builds its
 * elements with {@code insertSortedList} and then sorts them again, both by {@code Comparator.comparing(i -> i.item)}
 * -- {@code InventoryItem.compareTo}, category then display name. It does that to whatever {@code addAllItems}
 * produced. So the form's own comparator ran, and its result was then discarded.
 *
 * <p>That shipped, and it was reported as a button that did not refresh the view. It was not: the view rebuilt
 * correctly every time and the order was overwritten afterwards. GROUP hid it, because GROUP is
 * {@code naturalOrder()} -- the very comparator the engine was applying -- so two of the three modes did nothing and
 * the default looked perfect.
 *
 * <p>A single call fixes it and nothing observable depends on that call being there, which is precisely the kind of
 * line that gets removed during a cleanup. Hence a test. It reads the source rather than the running form because
 * the fault is a call being absent, and because a form needs a client to instantiate.
 */
public class ListSortGuardTest {

   private static final Path FORM =
      Path.of("src/main/java/arcanestorage/container/StorageTerminalContainerForm.java");

   @Test
   public void theItemListDoesNotSortItself() throws IOException {
      String source = Files.readString(FORM, StandardCharsets.UTF_8);

      assertTrue(
         "StorageTerminalContainerForm must call itemList.setSorted(false), or FormItemList re-sorts the grid by "
            + "InventoryItem.compareTo and the sort button silently does nothing for NAME and AMOUNT",
         source.contains("setSorted(false)"));
   }

   @Test
   public void everySortModeStillHasABadge() throws IOException {
      String source = Files.readString(FORM, StandardCharsets.UTF_8);

      // The button shows one character for the active mode. A mode added without one would draw nothing there and
      // be indistinguishable from whichever mode preceded it.
      for (String mode : new String[] {"GROUP(", "NAME(", "AMOUNT("}) {
         int at = source.indexOf(mode);
         assertTrue("SortMode." + mode + " should still be declared", at >= 0);

         String declaration = source.substring(at, source.indexOf(')', at));
         assertTrue(mode + " needs a badge character as its second argument", declaration.contains(","));
      }
   }
}
