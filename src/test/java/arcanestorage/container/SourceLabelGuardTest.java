package arcanestorage.container;

import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.Test;

/**
 * Crafting Sources must name the installed station, not a tech's fake item id.
 *
 * <p>Read from source for the same reason {@link ViewControlsGuardTest} does: the form needs a client
 * to instantiate, and the bug this guards against is a preference order that is easy to "simplify"
 * back to {@code tech.itemStringID} alone. Summoner Expansion's Summoning Bookshelf registers tech
 * {@code summonbookcraft} with item id {@code summonbookcraftitem} (not a real item) and no
 * {@code tech.summonbookcraft} locale, so that simplified path shows the raw key. Preferring the
 * socketed object's string id ({@code summoningbookshelf}) is the fix; a Demonic Workstation still
 * contributes multiple tech rows, each labelled from the same installed item.
 */
public class SourceLabelGuardTest {

   private static final Path FORM =
      Path.of("src/main/java/arcanestorage/container/StorageTerminalContainerForm.java");

   @Test
   public void sourceLabelPrefersTheInstalledStationItem() throws IOException {
      String form = Files.readString(FORM, StandardCharsets.UTF_8);

      assertTrue(
         "sourceLabel must look up which installed station provides the tech",
         form.contains("stationProviding(tech)"));
      assertTrue(
         "and label from that item's string id, not the tech's possibly-fake itemStringID",
         form.contains("ItemRegistry.getLocalization(station.item.getStringID())"));
      assertTrue(
         "multi-tech stations still walk getCraftingTechs so each unlocked tech finds the socket",
         form.contains("station.getCraftingTechs()"));
   }

   @Test
   public void sourceLabelFallsBackWhenNoStationIsInstalled() throws IOException {
      String form = Files.readString(FORM, StandardCharsets.UTF_8);

      // Hand recipes (Inventory) and techs with no socketed provider still need a name.
      assertTrue(
         "a real tech.itemStringID remains the first fallback",
         form.contains("ItemRegistry.getItemID(tech.itemStringID) != -1"));
      assertTrue(
         "and the tech display name is last (Inventory, missing locales)",
         form.contains("tech.displayName.translate()"));
   }
}
