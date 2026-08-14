package arcanestorage.recipe;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;
import necesse.inventory.recipe.Ingredient;

/**
 * Every cost in the mod, read from {@code resources/recipes.properties} rather than written in Java.
 *
 * <p>The separation is not housekeeping. The unit materials used to live in an enum and were copied into a table in
 * the roadmap; the two disagreed for two commits, nothing failed, and the commit that claimed to have reconciled
 * them had checked one of the two files and asserted the other. Numbers that exist once cannot drift from
 * themselves, and this file is also read directly by the Python scenario tests, so the tests cannot drift either.
 *
 * <p>It is a jar resource and not a config file, which is a deliberate limit. The engine does offer per-mod config
 * at {@code <cfg>/mods/<id>.cfg} through {@code ModSettings}, but those are <i>client</i> settings, reloadable from
 * the settings menu. Recipe costs read from there would let a client hold different numbers than the server it is
 * connected to -- and since the server revalidates every upgrade while the client draws the button, the visible
 * result would be a button that offers what the server refuses. Shipping the data in the jar makes both sides read
 * the same bytes by construction. Exposing an override is a separate feature with that hazard to solve first.
 *
 * <h2>Failing loudly</h2>
 *
 * <p>Every accessor throws rather than returning a default. The failure mode being avoided is specific: an absent
 * key that yielded an empty {@code Ingredient[]} would register a recipe craftable out of nothing, and would do it
 * without a log line. A mod that will not load is a bug report; a mod that quietly gives away Fallen-tier units is
 * a broken save.
 *
 * <p>Item IDs are not validated here. This is read during {@code init}, before the item registry is populated, so
 * a misspelt ingredient cannot be caught until the recipe registers -- at load, in the server log, not at compile.
 */
public final class CostTable {

   /**
    * Where the file lands on the classpath.
    *
    * <p>Not the same as its path in the source tree: {@code buildModJar} copies {@code src/main/resources} to
    * {@code resources/} inside the jar, which is verifiable with {@code unzip -l} and was, rather than assumed.
    */
   private static final String RESOURCE = "/resources/recipes.properties";

   private static Map<String, Ingredient[]> materials;

   private static Map<String, Integer> counts;

   private CostTable() {
   }

   /**
    * Reads the file. Called once, early, from the mod's {@code init}.
    *
    * <p>Deliberately explicit rather than lazy on first access. Loading on demand would move a malformed file's
    * failure from startup to whenever a player first opened an upgrade panel, which is both later and harder to
    * attribute.
    */
   public static void load() {
      Properties properties = new Properties();

      try (InputStream stream = CostTable.class.getResourceAsStream(RESOURCE)) {
         if (stream == null) {
            throw new IllegalStateException(
               "Arcane Storage: " + RESOURCE + " is missing from the jar. Every recipe cost lives there, and "
                  + "guessing them would register recipes nobody chose."
            );
         }

         properties.load(new InputStreamReader(stream, StandardCharsets.UTF_8));
      } catch (IOException e) {
         throw new IllegalStateException("Arcane Storage: could not read " + RESOURCE, e);
      }

      Map<String, Ingredient[]> parsedMaterials = new LinkedHashMap<>();
      Map<String, Integer> parsedCounts = new LinkedHashMap<>();

      for (String name : properties.stringPropertyNames()) {
         String value = properties.getProperty(name).trim();

         if (name.endsWith(".materials")) {
            parsedMaterials.put(name, parseMaterials(name, value));
         } else if (name.endsWith(".count")) {
            parsedCounts.put(name, parsePositive(name, value));
         } else {
            // Not ignored. A key that is neither is far more likely to be a typo in a key that was meant to
            // matter -- and a silently ignored cost line reads, from the game, exactly like a cost that is free.
            throw new IllegalStateException(
               "Arcane Storage: " + RESOURCE + " has an entry '" + name + "' that ends in neither .materials nor "
                  + ".count, so nothing would ever read it."
            );
         }
      }

      materials = Collections.unmodifiableMap(parsedMaterials);
      counts = Collections.unmodifiableMap(parsedCounts);
   }

   /** {@code item 20, other 5} to ingredients, refusing anything it cannot make sense of. */
   private static Ingredient[] parseMaterials(String key, String value) {
      if (value.isEmpty()) {
         throw new IllegalStateException(
            "Arcane Storage: '" + key + "' in " + RESOURCE + " is empty. A recipe with no ingredients can be "
               + "crafted out of nothing, which is never what an empty line was meant to say."
         );
      }

      String[] parts = value.split(",");
      Ingredient[] result = new Ingredient[parts.length];

      for (int i = 0; i < parts.length; i++) {
         String[] pair = parts[i].trim().split("\\s+");

         if (pair.length != 2) {
            throw new IllegalStateException(
               "Arcane Storage: '" + key + "' in " + RESOURCE + " has the entry '" + parts[i].trim()
                  + "', which is not an item ID followed by an amount."
            );
         }

         result[i] = new Ingredient(pair[0], parsePositive(key, pair[1]));
      }

      return result;
   }

   private static int parsePositive(String key, String value) {
      int amount;

      try {
         amount = Integer.parseInt(value.trim());
      } catch (NumberFormatException e) {
         throw new IllegalStateException(
            "Arcane Storage: '" + key + "' in " + RESOURCE + " has the amount '" + value + "', which is not a number."
         );
      }

      if (amount <= 0) {
         throw new IllegalStateException(
            "Arcane Storage: '" + key + "' in " + RESOURCE + " has the amount " + amount
               + ". Zero or negative would make the recipe free or nonsensical."
         );
      }

      return amount;
   }

   /**
    * The materials under {@code <key>.materials}.
    *
    * <p>Returns fresh {@link Ingredient} objects on every call. The engine's recipe and tooltip code is given
    * these directly, and a shared array handed to several recipes is the kind of thing that works until something
    * mutates it.
    */
   public static Ingredient[] materials(String key) {
      requireLoaded();
      Ingredient[] found = materials.get(key + ".materials");

      if (found == null) {
         throw new IllegalStateException(
            "Arcane Storage: " + RESOURCE + " has no '" + key + ".materials'. Known keys: " + knownKeys()
         );
      }

      Ingredient[] copy = new Ingredient[found.length];

      for (int i = 0; i < found.length; i++) {
         copy[i] = new Ingredient(found[i].ingredientStringID, found[i].getIngredientAmount());
      }

      return copy;
   }

   /** How many the recipe yields; 1 unless the file says otherwise. */
   public static int count(String key) {
      requireLoaded();
      return counts.getOrDefault(key + ".count", 1);
   }

   /** The material keys present, without their suffix. For error messages and for the test that checks coverage. */
   public static Set<String> keys() {
      requireLoaded();
      return knownKeys();
   }

   private static Set<String> knownKeys() {
      Set<String> keys = new TreeSet<>();

      for (String name : materials.keySet()) {
         keys.add(name.substring(0, name.length() - ".materials".length()));
      }

      return keys;
   }

   private static void requireLoaded() {
      if (materials == null) {
         throw new IllegalStateException(
            "Arcane Storage: CostTable.load() has not run. It belongs at the top of init, before anything asks "
               + "what something costs."
         );
      }
   }
}
