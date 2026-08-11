package arcanestorage.network;

import necesse.engine.save.LoadData;
import necesse.engine.save.SaveData;

/**
 * One transfer rule: which item, and how much of it may move.
 *
 * <p><b>This is the phase's whole primitive, and it is deliberately one thing read three ways.</b>
 * Overflow control, a defence against settler overproduction, and a reserve floor are the same idea
 * from different sides, and a player should have to learn it once:
 *
 * <ul>
 *   <li>"send iron bars to the shipping chest, but keep 200" — {@code keep = 200}, the floor applying
 *       to wherever the items are coming from;
 *   <li>"accept wheat, but never more than 500" — {@code limit = 500}, the ceiling applying to wherever
 *       they are going;
 *   <li>"never drain below 200" — the first one again, which is the point.
 * </ul>
 *
 * <p>Both bounds are optional and independent, so a rule can express "move it all" ({@code keep = 0},
 * no limit), "only the surplus" (a floor), "top it up" (a ceiling), or a band (both).
 *
 * <p><b>Amounts are counted over the whole side, not per slot.</b> A floor of 200 iron bars means 200
 * anywhere in the network, not 200 in each unit — the network is one pool to the player and a rule that
 * said otherwise would be unpredictable.
 *
 * <p>Immutable, because a rule is edited by replacing it. That keeps the tick free of any question
 * about a rule changing halfway through a transfer.
 */
public final class TransferRule {

   /** No ceiling. Chosen over {@code Integer.MAX_VALUE} so a saved rule reads honestly. */
   public static final int NO_LIMIT = -1;

   /** The item this rule governs, by string ID. Never null: a rule is always about one item. */
   public final String itemStringID;

   /** Never take the source below this many. Zero means "take it all". */
   public final int keep;

   /** Never take the destination above this many, or {@link #NO_LIMIT}. */
   public final int limit;

   public TransferRule(String itemStringID, int keep, int limit) {
      if (itemStringID == null || itemStringID.isEmpty()) {
         throw new IllegalArgumentException("a transfer rule needs an item");
      }

      this.itemStringID = itemStringID;
      this.keep = Math.max(0, keep);
      this.limit = limit < 0 ? NO_LIMIT : limit;
   }

   /** A rule that moves everything of one item, unconditionally. */
   public static TransferRule all(String itemStringID) {
      return new TransferRule(itemStringID, 0, NO_LIMIT);
   }

   public boolean matches(String itemStringID) {
      return this.itemStringID.equals(itemStringID);
   }

   /**
    * How many may move right now, given what each side already holds.
    *
    * <p>Never negative: a source below its floor or a destination above its ceiling yields zero rather
    * than a reverse transfer. A rule describes what may move, not what should be balanced, and the
    * difference matters — two buses pointing at each other with symmetric rules would otherwise pass a
    * stack back and forth forever.
    */
   public int allowed(int inSource, int inDestination) {
      int fromFloor = inSource - this.keep;
      if (fromFloor <= 0) {
         return 0;
      }

      if (this.limit == NO_LIMIT) {
         return fromFloor;
      }

      return Math.max(0, Math.min(fromFloor, this.limit - inDestination));
   }

   public void addSaveData(SaveData save) {
      SaveData rule = new SaveData("RULE");
      rule.addSafeString("item", this.itemStringID);
      rule.addInt("keep", this.keep);
      rule.addInt("limit", this.limit);
      save.addSaveData(rule);
   }

   /**
    * @return the rule, or null when the saved data names no item — which is the shape a rule saved by a
    *     future version with a different idea of "which items" would have, and dropping it is better
    *     than inventing one.
    */
   public static TransferRule fromLoadData(LoadData rule) {
      String item = rule.getSafeString("item", "");
      if (item.isEmpty()) {
         return null;
      }

      return new TransferRule(item, rule.getInt("keep", 0), rule.getInt("limit", NO_LIMIT));
   }

   @Override
   public String toString() {
      StringBuilder out = new StringBuilder(this.itemStringID);
      if (this.keep > 0) {
         out.append(" keep ").append(this.keep);
      }

      if (this.limit != NO_LIMIT) {
         out.append(" limit ").append(this.limit);
      }

      return out.toString();
   }
}
