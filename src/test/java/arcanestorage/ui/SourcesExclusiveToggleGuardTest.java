package arcanestorage.ui;

import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.Test;

/**
 * Sources right-click toggles: solo-select, then the same row again restores every tick.
 *
 * <p>Read from source — the dropdown needs a client to instantiate. Guards the restore path so a
 * later "simplify" cannot drop the second right-click without failing CI.
 */
public class SourcesExclusiveToggleGuardTest {

   private static final Path DROPDOWN =
      Path.of("src/main/java/arcanestorage/ui/ArcaneCheckDropdown.java");

   @Test
   public void rightClickTogglesBetweenExclusiveAndSelectAll() throws IOException {
      String src = Files.readString(DROPDOWN, StandardCharsets.UTF_8);

      assertTrue(
         "right-click must choose exclusive-or-restore, not only exclusiveSelect",
         src.contains("exclusiveSelectOrRestore("));
      assertTrue(
         "second right-click on the solo row must re-check every source",
         src.contains("selectAll(") && src.contains("isAlreadyExclusive("));
      assertTrue(
         "left-click path must still write the checkbox state into the Row model",
         src.contains("row.setChecked(e.from.checked)"));
   }
}
