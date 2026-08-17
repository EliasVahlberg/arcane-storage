package arcanestorage.release;

import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.Test;

/**
 * Guards the locale files against the mistakes that the game does not report.
 *
 * <p>{@link ConventionsTest} already covers English on its own: that every key is reachable from code, that
 * every key the code asks for exists, and that every placeholder is one the code passes. This class covers
 * what that cannot -- the file's own syntax, and translations, which have their own failure modes.
 *
 * <p>The reason these need a test at all is that the game's parser is silent. It reports no error for a
 * malformed line, no error for a duplicate key, and no error for a translation that has lost a placeholder.
 * A translated build simply shows the wrong thing to players who cannot easily be asked.
 *
 * <p>Two checks live in {@code tools/translations.py} instead of here, because they need the game's
 * installation to know which language codes exist: whether a file's name is a language the game will ever
 * look for, and how much of a file is still English. Run {@code tools/translations.py check} before a
 * release for those.
 */
public class TranslationTest {

   private static final Path LOCALE_DIR = Path.of("src/main/resources/locale");
   private static final Pattern CATEGORY = Pattern.compile("^\\[([a-zA-Z0-9_]+)]$");
   private static final Pattern ENTRY = Pattern.compile("^([a-zA-Z0-9_]+)=(.*)$");
   private static final Pattern PLACEHOLDER = Pattern.compile("<[a-zA-Z][a-zA-Z0-9_]*>");

   /**
    * The parser treats {@code //} as a comment and nothing else.
    *
    * <p>A {@code #} line is not a comment to it. Having no {@code =}, it becomes an UNKNOWN line and is
    * discarded without a word, so the note reads fine in an editor and does not exist at runtime. Worse, a
    * {@code #} line that happens to contain an {@code =} is parsed as a translation, which registers a key
    * named after the start of a sentence.
    *
    * <p>Three such lines shipped in 1.0.1 before this test existed.
    */
   @Test
   public void everyCommentUsesTheSyntaxTheParserKnows() throws IOException {
      List<String> wrong = new ArrayList<>();

      for (Path file : localeFiles()) {
         List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
         for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i).trim();
            if (line.isEmpty() || line.startsWith("//")) {
               continue;
            }
            if (CATEGORY.matcher(line).matches() || ENTRY.matcher(line).matches()) {
               continue;
            }
            wrong.add(file.getFileName() + ":" + (i + 1) + " " + line);
         }
      }

      assertTrue(
         "these lines are neither a category, a // comment, nor key=value, so the game discards them "
            + "silently: " + wrong,
         wrong.isEmpty());
   }

   /**
    * A key defined twice keeps its last value, and the earlier line does nothing.
    *
    * <p>This is invisible on inspection: both lines look correct, and editing the wrong one appears to have
    * no effect at all, which is a long way to chase for a typo.
    */
   @Test
   public void noKeyIsDefinedTwiceInACategory() throws IOException {
      List<String> duplicates = new ArrayList<>();

      for (Path file : localeFiles()) {
         Map<String, Integer> seen = new LinkedHashMap<>();
         String category = "null";
         List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);

         for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i).trim();
            Matcher cat = CATEGORY.matcher(line);
            if (cat.matches()) {
               category = cat.group(1);
               continue;
            }
            Matcher entry = ENTRY.matcher(line);
            if (!entry.matches()) {
               continue;
            }
            String ident = category + "." + entry.group(1);
            Integer first = seen.put(ident, i + 1);
            if (first != null) {
               duplicates.add(file.getFileName() + " " + ident + " on lines " + first + " and " + (i + 1));
            }
         }
      }

      assertTrue("these keys are defined more than once, so the earlier line is dead: " + duplicates,
         duplicates.isEmpty());
   }

   /**
    * A translation must carry exactly English's placeholders for that key.
    *
    * <p>This is the one failure worth a test more than any other here. Placeholders are substituted at
    * runtime, so a translator who drops {@code <count>} does not produce awkward wording -- they produce a
    * sentence with the number gone, and the count it was reporting is simply not shown. Adding one is as
    * bad in the other direction: nothing fills it, so the player reads the angle brackets.
    *
    * <p>Missing keys are deliberately not an error. The game falls back to English per key, so a partial
    * translation is a working translation, and treating incompleteness as a failure would mean no
    * translation could ever be merged until it was finished.
    *
    * <p>Vacuous until a second language ships, which is the point: it is here so the first one cannot land
    * broken.
    */
   @Test
   public void everyTranslationKeepsEnglishsPlaceholders() throws IOException {
      Map<String, String> english = entries(LOCALE_DIR.resolve("en.lang"));
      List<String> broken = new ArrayList<>();
      List<String> unknown = new ArrayList<>();

      for (Path file : localeFiles()) {
         if (file.getFileName().toString().equals("en.lang")) {
            continue;
         }

         for (Map.Entry<String, String> entry : entries(file).entrySet()) {
            String reference = english.get(entry.getKey());
            if (reference == null) {
               unknown.add(file.getFileName() + " " + entry.getKey());
               continue;
            }

            List<String> want = placeholders(reference);
            List<String> got = placeholders(entry.getValue());
            if (!want.equals(got)) {
               broken.add(file.getFileName() + " " + entry.getKey() + " has " + got + " but English has " + want);
            }
         }
      }

      assertTrue("a substitution would be lost or shown raw: " + broken, broken.isEmpty());
      assertTrue("these keys are not in en.lang, so nothing reads them: " + unknown, unknown.isEmpty());
   }

   /**
    * No locale file's name may end with another's.
    *
    * <p>The loader finds a mod's file by scanning the jar for an entry under {@code resources/} whose path
    * <i>ends with</i> the language's own file name, then stops at the first match. So {@code broken.lang}
    * ends with {@code en.lang} and is a candidate to be loaded as English, and which of the two wins
    * depends on the order entries happen to sit in the jar.
    *
    * <p>That makes it worse than a plain mistake: it is a mistake whose effect can differ between builds of
    * the same source.
    */
   @Test
   public void noLocaleFileNameShadowsAnother() throws IOException {
      List<String> names = localeFiles().stream()
         .map(path -> path.getFileName().toString())
         .collect(Collectors.toList());
      List<String> clashes = new ArrayList<>();

      for (String name : names) {
         for (String other : names) {
            if (!name.equals(other) && name.endsWith(other)) {
               clashes.add(name + " ends with " + other + ", so it is a candidate to load as " + other);
            }
         }
      }

      assertTrue("the loader matches by path suffix, so these names are ambiguous: " + clashes,
         clashes.isEmpty());
   }

   private static List<Path> localeFiles() throws IOException {
      assertTrue("the locale folder is missing: " + LOCALE_DIR.toAbsolutePath(), Files.isDirectory(LOCALE_DIR));
      try (Stream<Path> files = Files.list(LOCALE_DIR)) {
         return files.filter(Files::isRegularFile)
            .filter(path -> path.getFileName().toString().endsWith(".lang"))
            .sorted()
            .collect(Collectors.toList());
      }
   }

   /** Every entry in a file as {@code category.key -> value}, last definition winning as the game does. */
   private static Map<String, String> entries(Path file) throws IOException {
      Map<String, String> out = new LinkedHashMap<>();
      String category = "null";

      for (String raw : Files.readAllLines(file, StandardCharsets.UTF_8)) {
         String line = raw.trim();
         Matcher cat = CATEGORY.matcher(line);
         if (cat.matches()) {
            category = cat.group(1);
            continue;
         }
         Matcher entry = ENTRY.matcher(line);
         if (entry.matches()) {
            out.put(category + "." + entry.group(1), entry.group(2));
         }
      }

      return out;
   }

   private static List<String> placeholders(String value) {
      List<String> found = new ArrayList<>();
      Matcher matcher = PLACEHOLDER.matcher(value);
      while (matcher.find()) {
         found.add(matcher.group());
      }
      found.sort(String::compareTo);
      return found;
   }
}
