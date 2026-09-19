package arcanestorage.objectentity;

import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.Test;

/**
 * Soft-detect Necesse Expanded's keg so Station Units can unlock fermenting recipes without a
 * hard mod dependency.
 */
public class KegStationGuardTest {

   private static final Path HELPER =
      Path.of("src/main/java/arcanestorage/objectentity/StationTechHelper.java");

   @Test
   public void kegDetectedByStringIdAndOptionalTechLookup() throws IOException {
      String src = Files.readString(HELPER, StandardCharsets.UTF_8);
      assertTrue("must recognize object id keg", src.contains("\"keg\".equals(object.getStringID())"));
      assertTrue("must look up Expanded keg tech softly", src.contains("getTech(\"keg\")"));
      assertTrue("valid-station path must accept kegs", src.contains("isKegStation(object)"));
   }
}
