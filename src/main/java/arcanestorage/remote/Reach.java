package arcanestorage.remote;

import arcanestorage.ArcaneStorage;
import arcanestorage.object.UnitTier;
import necesse.entity.mobs.PlayerMob;

/**
 * Whether a player may reach a network through a transceiver, and if not, what would fix it.
 *
 * <h2>Why this is one class and not a method on the item</h2>
 *
 * <p>The same question is asked twice per open and then once per tick: at the right-click, so the answer can be
 * explained in chat, and inside the container's own validity check, so walking out of range closes what walking
 * into range opened. A range that were only checked at open time would be a range in the tooltip and nowhere else.
 * Keeping the rule in one place is what makes those two answers the same answer.
 *
 * <h2>The rule</h2>
 *
 * <p>Both ends carry a tier, and <b>the lower of the two decides</b>. Pairing a Demonic terminal to a Fallen
 * transceiver is allowed and gives Demonic reach: refusing the pairing would be a worse experience for no gain,
 * and silently granting the higher tier would make one of the two upgrades pointless. So:
 *
 * <ul>
 *   <li><b>Demonic</b> -- the transceiver's own level, within a configured number of tiles.</li>
 *   <li><b>Tungsten</b> -- the transceiver's own level, at any distance by default.</li>
 *   <li><b>Fallen</b> -- any level, at any distance.</li>
 * </ul>
 *
 * <p>Every distance is configurable and the tier that crosses levels is not, because a range is a number to tune
 * in play while "which rung stops being local" is the shape of the ladder.
 *
 * <h2>Server-side only</h2>
 *
 * <p>Not because a client could not do the arithmetic, but because it must not be the one that decides. The client
 * knows only the level it stands on, so it cannot even ask the question when the answer is interesting.
 */
public final class Reach {

   private Reach() {
   }

   /** Why access was refused, or {@link #OK}. */
   public enum Verdict {
      OK,
      /** Right level, too many tiles. */
      TOO_FAR,
      /** A different level, and the pairing does not cross levels. */
      WRONG_LEVEL
   }

   /** The verdict plus everything needed to explain it without asking again. */
   public static final class Decision {

      public final Verdict verdict;

      /** Tiles between the player and the transceiver, or -1 when they are not on the same level. */
      public final int distance;

      /** The tile limit that was applied, or -1 when the limit is not a distance. */
      public final int limit;

      /** The lowest tier that would have allowed this, or null when no tier would. */
      public final UnitTier required;

      public final boolean upgradeTerminal;

      public final boolean upgradeTransceiver;

      private Decision(Verdict verdict, int distance, int limit, UnitTier required,
            boolean upgradeTerminal, boolean upgradeTransceiver) {
         this.verdict = verdict;
         this.distance = distance;
         this.limit = limit;
         this.required = required;
         this.upgradeTerminal = upgradeTerminal;
         this.upgradeTransceiver = upgradeTransceiver;
      }

      public boolean ok() {
         return this.verdict == Verdict.OK;
      }
   }

   private static final Decision ALLOWED = new Decision(Verdict.OK, 0, -1, null, false, false);

   /** The tier that governs a pairing: the lower of the two ends. */
   public static UnitTier effective(UnitTier terminal, UnitTier transceiver) {
      if (terminal == null || transceiver == null) {
         return null;
      }

      return terminal.ordinal() <= transceiver.ordinal() ? terminal : transceiver;
   }

   /**
    * Tiles a tier reaches on the transceiver's own level, or -1 for no limit.
    *
    * <p>Read from the mod's config file every time rather than captured once, so editing it takes effect on the
    * next open instead of the next launch.
    */
   public static int sameLevelRange(UnitTier tier) {
      switch (tier) {
         case DEMONIC:
            return ArcaneStorage.SETTINGS.wirelessRangeDemonic;
         case TUNGSTEN:
            return ArcaneStorage.SETTINGS.wirelessRangeTungsten;
         case FALLEN:
            return ArcaneStorage.SETTINGS.wirelessRangeFallen;
         default:
            return 0;
      }
   }

   /** Whether a tier reaches a level the player is not standing on. */
   public static boolean crossesLevels(UnitTier tier) {
      return tier == UnitTier.FALLEN;
   }

   /**
    * Whether a tier covers a distance on its own level. An unlimited range covers everything, including the
    * negative distance that stands for "not measurable".
    */
   private static boolean covers(UnitTier tier, int distance) {
      int range = sameLevelRange(tier);
      return range < 0 || distance <= range;
   }

   /**
    * Decides access.
    *
    * @param player          the player holding the terminal
    * @param terminalTier    the item's tier
    * @param transceiverTier the placed transceiver's tier
    * @param remoteLevelID   the string ID of the level the transceiver is on
    * @param tileX           the transceiver's tile
    * @param tileY           the transceiver's tile
    */
   public static Decision check(PlayerMob player, UnitTier terminalTier, UnitTier transceiverTier,
         String remoteLevelID, int tileX, int tileY) {
      UnitTier tier = effective(terminalTier, transceiverTier);
      if (player == null || player.getLevel() == null || tier == null) {
         return ALLOWED;
      }

      boolean sameLevel = player.getLevel().getIdentifier().stringID.equals(remoteLevelID);

      if (!sameLevel) {
         if (crossesLevels(tier)) {
            return ALLOWED;
         }

         // The required tier is the first that crosses levels at all; distance does not enter into it, because a
         // player on another level has no distance to the transceiver that means anything.
         UnitTier required = firstCrossingTier();
         return new Decision(Verdict.WRONG_LEVEL, -1, -1, required,
               needsUpgrade(terminalTier, required), needsUpgrade(transceiverTier, required));
      }

      int distance = distanceInTiles(player, tileX, tileY);
      if (covers(tier, distance)) {
         return ALLOWED;
      }

      UnitTier required = firstTierCovering(distance);
      return new Decision(Verdict.TOO_FAR, distance, sameLevelRange(tier), required,
            needsUpgrade(terminalTier, required), needsUpgrade(transceiverTier, required));
   }

   /**
    * Straight-line tiles, rounded down.
    *
    * <p>Euclidean rather than the larger of the two axes, because a range that reached further diagonally would
    * be a square drawn around the transceiver and would read as a bug the first time a player noticed it.
    */
   public static int distanceInTiles(PlayerMob player, int tileX, int tileY) {
      int dx = player.getTileX() - tileX;
      int dy = player.getTileY() - tileY;
      return (int)Math.sqrt((double)dx * dx + (double)dy * dy);
   }

   /** The lowest wireless tier that reaches another level, or null if none does. */
   private static UnitTier firstCrossingTier() {
      for (UnitTier tier : UnitTier.values()) {
         if (tier.hasWireless() && crossesLevels(tier)) {
            return tier;
         }
      }

      return null;
   }

   /** The lowest wireless tier whose own-level range covers a distance, or null if none does. */
   private static UnitTier firstTierCovering(int distance) {
      for (UnitTier tier : UnitTier.values()) {
         if (tier.hasWireless() && covers(tier, distance)) {
            return tier;
         }
      }

      return null;
   }

   /** Whether an end has to be upgraded to reach a required tier. Null required means nothing would help. */
   private static boolean needsUpgrade(UnitTier held, UnitTier required) {
      return required != null && held != null && held.ordinal() < required.ordinal();
   }
}
