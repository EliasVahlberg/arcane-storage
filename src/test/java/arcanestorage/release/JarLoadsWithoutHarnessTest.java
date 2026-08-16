package arcanestorage.release;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.File;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

import org.junit.Test;

/**
 * Loads every class in the built jar the way the game's mod loader does, with no harness anywhere on the class path.
 *
 * <p><strong>Why this test exists.</strong> The failure it guards against is the worst one this mod can ship: a class
 * that cannot be defined without the harness installed makes {@code LoadedMod.loadClasses} throw, which the game turns
 * into a fatal {@code ModLoadException}, and the mod then refuses to load <em>at all</em> for every player. It cannot
 * be caught at runtime, because it happens before any of this mod's code runs.
 *
 * <p>It is also invisible on the development machine, where the harness is always installed, and invisible to the
 * scenario suite, where the harness is by definition present. Every one of the 250 scenarios could pass while the
 * released jar is unloadable. That gap is what this closes.
 *
 * <p><strong>Why it reproduces rather than approximates.</strong> The loader's per-class work is
 * {@code cl.loadClass(name)} followed by {@code isAnnotationPresent} for its four annotations, so that is what happens
 * here: defining a class resolves its superclass and interfaces, and reading its annotations parses them. The parent is
 * the platform class loader rather than the application one, because the application class loader has the harness on it
 * during a test run -- which would hide the very thing being tested.
 *
 * <p>Note what is deliberately <em>not</em> asserted: that no class mentions a harness type. Method bodies, signatures
 * and lambda target types all resolve lazily and are perfectly safe. Only inheritance is eager, and a check stricter
 * than the JVM's own rule would ban code that works.
 */
public class JarLoadsWithoutHarnessTest {

   @Test
   public void everyShippedClassDefinesWithNoHarnessPresent() throws Exception {
      File jar = builtJar();
      File gameJar = new File(gameDirectory(), "Necesse.jar");
      assertTrue("Necesse.jar not found at " + gameJar + "; this test needs the game's classes", gameJar.isFile());

      List<String> classNames = new ArrayList<>();
      try (JarFile contents = new JarFile(jar)) {
         Enumeration<JarEntry> entries = contents.entries();
         while (entries.hasMoreElements()) {
            JarEntry entry = entries.nextElement();
            String name = entry.getName();
            // The same test the mod loader applies: an entry is code if and only if it ends in .class. The bridge
            // resources end in .classdata and so are skipped here exactly as they are skipped there.
            if (!entry.isDirectory() && name.endsWith(".class")) {
               classNames.add(name.substring(0, name.length() - 6).replace('/', '.'));
            }
         }
      }

      assertTrue("no classes found in " + jar + "; run 'make build' first", classNames.size() > 10);

      URL[] path = {jar.toURI().toURL(), gameJar.toURI().toURL()};
      List<String> failures = new ArrayList<>();
      try (URLClassLoader loader = new URLClassLoader(path, ClassLoader.getPlatformClassLoader())) {
         for (String className : classNames) {
            try {
               Class<?> loaded = loader.loadClass(className);
               loaded.getAnnotations();
            } catch (Throwable t) {
               // Throwable: the failure this is looking for is a LinkageError, which is an Error.
               failures.add(className + " -> " + t);
            }
         }
      }

      if (!failures.isEmpty()) {
         fail("these classes cannot be defined without the harness, so the mod would refuse to load for players:\n  "
            + String.join("\n  ", failures));
      }
   }

   /** The bridge must still be in the jar, or the mod is unloadable in the other direction: no debugging at all. */
   @Test
   public void theBridgeShipsAsResourcesRatherThanCode() throws Exception {
      int bridgeResources = 0;
      try (JarFile contents = new JarFile(builtJar())) {
         assertNotNull("harnessbridge/bridge.txt is missing, so the harness cannot find the bridge",
            contents.getJarEntry("harnessbridge/bridge.txt"));

         Enumeration<JarEntry> entries = contents.entries();
         while (entries.hasMoreElements()) {
            String name = entries.nextElement().getName();
            if (name.startsWith("harnessbridge/") && name.endsWith(".classdata")) {
               bridgeResources++;
            }

            if (name.startsWith("arcanestorage/harness/") && name.endsWith(".class")) {
               fail("the harness bridge ships as code (" + name + "), which stops the mod loading for players");
            }
         }
      }

      assertTrue("no bridge resources in the jar; the harness would find nothing to load", bridgeResources > 0);
   }

   private static File builtJar() {
      File dir = new File("build/jar");
      File[] jars = dir.listFiles((d, name) -> name.endsWith(".jar"));
      assertTrue("no jar in " + dir.getAbsolutePath() + "; run 'make build' first", jars != null && jars.length > 0);
      return jars[0];
   }

   private static String gameDirectory() {
      // The same override the build reads, so this test follows the machine it runs on rather than a hardcoded path.
      String property = System.getProperty("necesseGameDir");
      if (property != null && !property.isEmpty()) {
         return property;
      }

      return System.getProperty("user.home") + "/.steam/debian-installation/steamapps/common/Necesse";
   }
}
