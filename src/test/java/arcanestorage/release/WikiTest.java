package arcanestorage.release;

import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.Test;

/**
 * The player wiki, checked for the things that make it wrong rather than merely imperfect.
 *
 * <p>The pages live in this repository rather than in the GitHub wiki, and that decision is what these checks
 * protect. A separate wiki repository cannot reference this one's sprites by relative path and versions
 * independently of the code it describes, so a page there could describe a release that does not exist. Here, a
 * page points at the texture that actually ships and moves with the release it documents. The cost is that
 * nothing enforces any of it except a test.
 *
 * <p>Two style rules are Elias's, and they are not decoration: no em dashes and no semicolons in the prose. Both
 * read as machine-written, in documentation whose whole purpose is to sound like a person explaining something.
 */
public class WikiTest {

   private static final Path WIKI = Path.of("docs/wiki");

   @Test
   public void noEmDashesAndNoSemicolons() throws IOException {
      List<String> offences = new ArrayList<>();

      for (Path page : pages()) {
         String text = Files.readString(page, StandardCharsets.UTF_8);
         int line = 0;

         for (String each : text.split("\n", -1)) {
            line++;
            if (each.contains("\u2014")) {
               offences.add(page.getFileName() + ":" + line + " em dash");
            }

            if (each.contains(";")) {
               offences.add(page.getFileName() + ":" + line + " semicolon");
            }
         }
      }

      assertTrue("the player wiki avoids both of these deliberately: " + offences, offences.isEmpty());
   }

   @Test
   public void everyImageAndInternalLinkResolves() throws IOException {
      List<String> broken = new ArrayList<>();

      for (Path page : pages()) {
         String text = Files.readString(page, StandardCharsets.UTF_8);

         Matcher image = Pattern.compile("src=\"([^\"]+)\"").matcher(text);
         while (image.find()) {
            if (!Files.exists(page.getParent().resolve(image.group(1)))) {
               broken.add(page.getFileName() + " -> " + image.group(1));
            }
         }

         Matcher link = Pattern.compile("]\\((?!https?:)([^)#]+)").matcher(text);
         while (link.find()) {
            if (!Files.exists(page.getParent().resolve(link.group(1)))) {
               broken.add(page.getFileName() + " -> " + link.group(1));
            }
         }
      }

      assertTrue("these images or page links point at nothing: " + broken, broken.isEmpty());
   }

   @Test
   public void everySpriteShownIsOneTheModActuallyShips() throws IOException {
      // The images are scaled copies rather than the originals, because a browser blurs a 32 pixel sprite and
      // GitHub markdown offers no way to ask for nearest-neighbour scaling. A copy can therefore go stale: the
      // item's real texture can change and the wiki keep showing the old one. This catches a copy of something
      // that no longer exists at all, which is the version of that problem a test can see.
      List<String> orphans = new ArrayList<>();

      Path images = WIKI.resolve("images");
      if (!Files.isDirectory(images)) {
         return;
      }

      try (Stream<Path> copies = Files.list(images)) {
         for (Path copy : copies.toList()) {
            Path original = Path.of("src/main/resources/items").resolve(copy.getFileName());
            if (!Files.exists(original)) {
               orphans.add(copy.getFileName().toString());
            }
         }
      }

      assertTrue(
         "these wiki images have no matching item texture, so they show something the mod no longer ships: "
            + orphans,
         orphans.isEmpty());
   }

   @Test
   public void theIndexLinksEveryPage() throws IOException {
      String index = Files.readString(WIKI.resolve("README.md"), StandardCharsets.UTF_8);
      List<String> unlinked = new ArrayList<>();

      for (Path page : pages()) {
         String name = page.getFileName().toString();
         if (!name.equals("README.md") && !index.contains(name)) {
            unlinked.add(name);
         }
      }

      assertTrue("a page nothing links to will not be found by a player: " + unlinked, unlinked.isEmpty());
   }

   private static List<Path> pages() throws IOException {
      try (Stream<Path> files = Files.list(WIKI)) {
         return files.filter(f -> f.toString().endsWith(".md")).sorted().toList();
      }
   }
}
