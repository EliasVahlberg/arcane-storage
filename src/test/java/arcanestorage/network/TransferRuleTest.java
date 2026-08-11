package arcanestorage.network;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/**
 * The transfer rule primitive.
 *
 * <p>Pure arithmetic, so it is tested here rather than against a server: the three readings the roadmap
 * claims are one idea — overflow, a reserve floor, and a cap on what a destination accepts — are either
 * genuinely one calculation or they are not, and that is decidable without a game.
 */
public class TransferRuleTest {

   @Test
   public void anUnboundedRuleMovesWhateverTheSourceHas() {
      TransferRule rule = TransferRule.all("ironbar");

      assertEquals(60, rule.allowed(60, 0));
      assertEquals(60, rule.allowed(60, 9999));
      assertEquals(0, rule.allowed(0, 0));
   }

   @Test
   public void aFloorLeavesTheSourceWithWhatItKeeps() {
      TransferRule rule = new TransferRule("ironbar", 200, TransferRule.NO_LIMIT);

      assertEquals("only the surplus moves", 50, rule.allowed(250, 0));
      assertEquals("exactly at the floor, nothing moves", 0, rule.allowed(200, 0));
      assertEquals("below the floor is not a reverse transfer", 0, rule.allowed(10, 0));
   }

   @Test
   public void aCeilingTopsTheDestinationUpAndNoFurther() {
      TransferRule rule = new TransferRule("wheat", 0, 500);

      assertEquals("fills the gap", 100, rule.allowed(400, 400));
      assertEquals("already full", 0, rule.allowed(400, 500));
      assertEquals("over the ceiling is not a reverse transfer", 0, rule.allowed(400, 600));
   }

   @Test
   public void bothBoundsApplyAtOnce() {
      TransferRule rule = new TransferRule("ironbar", 200, 100);

      assertEquals("the tighter of the two decides: 50 surplus, room for 100", 50, rule.allowed(250, 0));
      assertEquals("now the ceiling decides: 300 surplus, room for 40", 40, rule.allowed(500, 60));
   }

   @Test
   public void aRuleSurvivesBeingWrittenAndReadBack() {
      // Save and load are the one part of a rule a player would notice failing, since a bus is set up
      // once and expected to keep working across sessions. Exercised properly against a real world in
      // tests/python/test_buses.py; this covers the field-by-field round trip.
      TransferRule rule = new TransferRule("ironbar", 200, 500);

      assertEquals("ironbar keep 200 limit 500", rule.toString());
      assertEquals(200, rule.keep);
      assertEquals(500, rule.limit);
   }

   @Test
   public void rulesAreAWhitelistOnceAnyExist() {
      TransferRules rules = new TransferRules(true);

      assertEquals("empty and permissive: everything moves", 40, rules.allowed("stone", 40, 0));

      rules.add(TransferRule.all("ironbar"));

      assertEquals("the listed item still moves", 40, rules.allowed("ironbar", 40, 0));
      assertEquals("anything unlisted now does not", 0, rules.allowed("stone", 40, 0));
   }

   @Test
   public void theFirstMatchingRuleDecides() {
      TransferRules rules = new TransferRules(false);
      rules.add(new TransferRule("ironbar", 200, TransferRule.NO_LIMIT));
      rules.add(TransferRule.all("ironbar"));

      assertEquals("the floor written first wins, so order is meaningful", 50,
            rules.allowed("ironbar", 250, 0));
   }

   @Test
   public void anEmptyExportBusMovesNothing() {
      // The asymmetry that keeps an export bus from emptying a network the moment it is placed.
      assertEquals(0, new TransferRules(false).allowed("ironbar", 9999, 0));
      assertEquals(9999, new TransferRules(true).allowed("ironbar", 9999, 0));
   }
}
