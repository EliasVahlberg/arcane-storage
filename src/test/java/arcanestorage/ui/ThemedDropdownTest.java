package arcanestorage.ui;

import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.Test;

/**
 * Every dropdown in the mod must be an {@link ArcaneDropdown}.
 *
 * <p>A plain {@code FormDropdownSelectionButton} looks correct: its own button is themed, because
 * {@code getInterfaceStyle()} decides the button art. Only the panel that opens below it is wrong, drawn in
 * base-game wood whichever theme the player chose, because the engine builds that panel with a style reading the
 * global {@code Settings.UI}. So the mistake is invisible in the source, invisible until a dropdown is opened, and
 * was in fact shipped and reported that way.
 *
 * <p>Hence a test rather than a note. It is a source scan for the same reason the other guards in this project
 * are: a form needs a client to instantiate, and what is being asserted is which type a call site chose.
 */
public class ThemedDropdownTest {

   private static final Path SOURCE = Path.of("src/main/java/arcanestorage");

   @Test
   public void noFormDropdownIsConstructedDirectly() throws IOException {
      List<String> offenders = new ArrayList<>();

      try (Stream<Path> files = Files.walk(SOURCE)) {
         for (Path file : files.filter(f -> f.toString().endsWith(".java")).toList()) {
            // ArcaneDropdown extends it, which is the one legitimate mention.
            if (file.endsWith("ArcaneDropdown.java")) {
               continue;
            }

            String source = Files.readString(file, StandardCharsets.UTF_8);
            if (source.contains("new FormDropdownSelectionButton") || source.contains("new FormDropdownButton")) {
               offenders.add(file.toString());
            }
         }
      }

      assertTrue(
         "these construct an engine dropdown, whose open panel ignores the mod's theme and draws base-game wood; "
            + "use ArcaneDropdown instead: " + offenders,
         offenders.isEmpty());
   }
}
