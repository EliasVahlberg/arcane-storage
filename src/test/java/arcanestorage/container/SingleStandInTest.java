package arcanestorage.container;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.Test;

/**
 * There is exactly one stand-in class, because the container finds stand-ins by type.
 *
 * <p>{@code StorageTerminalContainer.requestMirroring} asks which members are {@link MirroredMember} and tells the
 * server to mirror those slots. A second class implementing the same pair of interfaces is therefore not a duplicate
 * of some code -- it is invisible to that check, so its slots are never requested, never mirrored and stay empty
 * forever while everything around them looks correct.
 *
 * <p>This is not hypothetical. The wireless terminal shipped that way for one commit: its client half built stand-ins
 * of a second, identical class that predated the shared one, and the result was a terminal showing the right capacity,
 * a working Logistics tab -- buses travel on their own event -- and not one item or station. Nothing in the scenario
 * suite could see it, because the suite drives a server and the fault is entirely client-side.
 *
 * <p>Scanning source rather than checking instances because the failure is a class *existing*, which no instance can
 * report. The same technique as {@code TerminalNullGuardTest}, for the same reason.
 */
public class SingleStandInTest {

   private static final Path SOURCE = Paths.get("src/main/java");

   /** A class declaring both member interfaces, in either order, with anything between them. */
   private static final Pattern BOTH = Pattern.compile(
      "(?:class|interface)\\s+(\\w+)[^{]*?implements[^{]*?"
         + "(?:NetworkStorage[^{]*?NetworkStations|NetworkStations[^{]*?NetworkStorage)");

   @Test
   public void onlyMirroredMemberStandsInForANetworkMember() throws IOException {
      assertTrue("the source tree moved -- this test is looking in the wrong place", Files.isDirectory(SOURCE));

      List<String> found = new ArrayList<>();
      try (Stream<Path> files = Files.walk(SOURCE)) {
         for (Path file : (Iterable<Path>)files.filter(p -> p.toString().endsWith(".java"))::iterator) {
            String text = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
            Matcher matcher = BOTH.matcher(text);
            while (matcher.find()) {
               found.add(matcher.group(1) + " in " + file.getFileName());
            }
         }
      }

      assertEquals(
         "a class other than MirroredMember implements both member interfaces. requestMirroring finds stand-ins "
            + "with instanceof MirroredMember, so this one's slots will never be requested and will stay empty on "
            + "every client that cannot resolve them -- which looks like a terminal with correct capacity and no "
            + "contents. Use MirroredMember, or change requestMirroring to stop asking by type. Found: " + found,
         1, found.size());
      assertTrue("the one match should be MirroredMember itself, found " + found,
         found.get(0).startsWith("MirroredMember "));
   }
}
