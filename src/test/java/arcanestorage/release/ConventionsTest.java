package arcanestorage.release;

import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.Assume;
import org.junit.Test;

/**
 * The conventions the modding wiki documents, checked rather than eyeballed.
 *
 * <p>All of these are silent when broken, which is the only reason they are worth a test. A resource path that
 * collides with another mod's does not warn: the wiki's caution is mutual, so whichever mod loads later simply wins,
 * and the symptom is somebody else's art inside this interface. A string ID that collides with a vanilla one does not
 * warn either. A locale key that no code asks for costs a translator their time and nothing else, and a key the code
 * asks for that is absent shows a player the raw identifier.
 *
 * <p>One check needs the decompiled game data in {@code reference/}, which is proprietary and deliberately not part of
 * this repository. It skips itself when that is absent rather than failing, so a contributor without the data still
 * gets a green suite and this machine still gets the check.
 */
public class ConventionsTest {

   private static final Path RESOURCES = Path.of("src/main/resources");
   private static final Path SOURCE = Path.of("src/main/java");
   private static final Path LOCALE = Path.of("src/main/resources/locale/en.lang");
   private static final Path VANILLA_LOCALE = Path.of("../reference/grep/locale.tsv");

   private static final String PREFIX = "arcanestorage";

   /** Paths the game itself dictates the name of, or that are the mod's own by definition. */
   private static final Set<String> EXEMPT = Set.of("preview.png", "recipes.properties");

   @Test
   public void everyShippedResourcePathIsNamespaced() throws IOException {
      List<String> unnamespaced = new ArrayList<>();

      try (Stream<Path> files = Files.walk(RESOURCES)) {
         files.filter(Files::isRegularFile).forEach(file -> {
            String relative = RESOURCES.relativize(file).toString();
            if (EXEMPT.contains(relative) || relative.startsWith("locale/")) {
               return;
            }

            // Either the file is prefixed, or some directory on its way there is. Interface styles are the second
            // case and cannot be the first: GameInterfaceStyle looks for ui/<style>/<the engine's own file name>,
            // so the directory has to carry the namespace and the file names are not ours to choose.
            for (String part : relative.split("/")) {
               if (part.startsWith(PREFIX)) {
                  return;
               }
            }

            unnamespaced.add(relative);
         });
      }

      assertTrue(
         "these resource paths carry no arcanestorage namespace, so another mod using the same path silently "
            + "replaces them, or they silently replace another mod's: " + unnamespaced,
         unnamespaced.isEmpty());
   }

   @Test
   public void noStringIDCollidesWithVanilla() throws IOException {
      Assume.assumeTrue(
         "needs reference/grep/locale.tsv, which is generated from the game and not committed",
         Files.exists(VANILLA_LOCALE));

      // The keys in the game's own locale are its registry string IDs, which is what makes this checkable at all.
      Set<String> vanilla = new HashSet<>();
      for (String line : Files.readAllLines(VANILLA_LOCALE, StandardCharsets.UTF_8)) {
         String[] columns = line.split("\t");
         if (columns.length >= 2) {
            vanilla.add(columns[1]);
         }
      }

      List<String> collisions = new ArrayList<>();
      for (String id : shippedStringIDs()) {
         if (vanilla.contains(id)) {
            collisions.add(id);
         }
      }

      assertTrue("these string IDs already exist in the base game: " + collisions, collisions.isEmpty());
   }

   @Test
   public void everyLocaleKeyIsReachableAndEveryReachableKeyExists() throws IOException {
      Map<String, String> entries = localeEntries();
      String source = allSource();

      List<String> dead = new ArrayList<>();
      for (String key : entries.keySet()) {
         // Object and item names are keyed on registry IDs and are reached by the engine rather than by our code.
         if (key.startsWith(PREFIX + "_") && !isReachable(key, source)) {
            dead.add(key);
         }
      }

      assertTrue(
         "no code can reach these locale keys, so they are text a translator would work on for nothing: " + dead,
         dead.isEmpty());

      List<String> missing = new ArrayList<>();
      Matcher asked = Pattern.compile("\"(" + PREFIX + "_[a-z0-9_]+)\"").matcher(source);
      while (asked.find()) {
         String key = asked.group(1);

         // A key ending in an underscore is a prefix the code completes at runtime, so its own existence is not the
         // question -- whether every completion exists is, and that cannot be read off the source.
         if (!key.endsWith("_") && !entries.containsKey(key)) {
            missing.add(key);
         }
      }

      assertTrue("the code asks for these locale keys and en.lang does not have them: " + missing, missing.isEmpty());
   }

   @Test
   public void everyPlaceholderIsOneTheCodePasses() throws IOException {
      String source = allSource();
      List<String> unfilled = new ArrayList<>();

      for (Map.Entry<String, String> entry : localeEntries().entrySet()) {
         Matcher placeholder = Pattern.compile("<([a-zA-Z0-9_]+)>").matcher(entry.getValue());
         while (placeholder.find()) {
            if (!source.contains('"' + placeholder.group(1) + '"')) {
               unfilled.add(entry.getKey() + " -> <" + placeholder.group(1) + '>');
            }
         }
      }

      assertTrue(
         "nothing passes these placeholders, so the player is shown the angle brackets: " + unfilled,
         unfilled.isEmpty());
   }

   /**
    * Whether any literal in the source could produce this key.
    *
    * <p>Not simply a search for the whole key, because several are built by appending a tier or theme name to a
    * prefix. A key counts as reachable if it appears whole, or if a quoted prefix of it does.
    */
   private static boolean isReachable(String key, String source) {
      if (source.contains('"' + key + '"')) {
         return true;
      }

      for (int cut = key.length() - 1; cut > PREFIX.length(); cut--) {
         if (key.charAt(cut - 1) == '_' && source.contains('"' + key.substring(0, cut) + '"')) {
            return true;
         }
      }

      return false;
   }

   /** Registry string IDs this mod ships, taken from the resource files, which is what actually reaches a player. */
   private static Set<String> shippedStringIDs() throws IOException {
      Set<String> ids = new HashSet<>();

      for (String folder : new String[] {"objects", "items"}) {
         Path directory = RESOURCES.resolve(folder);
         if (!Files.isDirectory(directory)) {
            continue;
         }

         try (Stream<Path> files = Files.list(directory)) {
            files.filter(file -> file.toString().endsWith(".png")).forEach(file -> {
               String name = file.getFileName().toString().replace(".png", "");
               ids.add(name.endsWith("_inactive") ? name.substring(0, name.length() - "_inactive".length()) : name);
            });
         }
      }

      return ids;
   }

   private static Map<String, String> localeEntries() throws IOException {
      Map<String, String> entries = new LinkedHashMap<>();

      for (String line : Files.readAllLines(LOCALE, StandardCharsets.UTF_8)) {
         String trimmed = line.trim();
         if (trimmed.isEmpty() || trimmed.startsWith("[") || !trimmed.contains("=")) {
            continue;
         }

         String[] halves = trimmed.split("=", 2);
         entries.put(halves[0].trim(), halves[1]);
      }

      return entries;
   }

   private static String allSource() throws IOException {
      StringBuilder all = new StringBuilder();

      try (Stream<Path> files = Files.walk(SOURCE)) {
         for (Path file : files.filter(f -> f.toString().endsWith(".java")).toList()) {
            all.append(Files.readString(file, StandardCharsets.UTF_8)).append('\n');
         }
      }

      return all.toString();
   }
}
