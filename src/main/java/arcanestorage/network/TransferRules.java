package arcanestorage.network;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import necesse.engine.save.LoadData;
import necesse.engine.save.SaveData;

/**
 * The rules on one bus, in order, plus what an empty list means.
 *
 * <p><b>Empty means different things in the two directions, and that asymmetry is a safety property
 * rather than a setting.</b> An empty import bus moves everything, because importing only ever adds to
 * the network and "point it at a chest and it drains into storage" is the whole feature. An empty export
 * bus moves nothing, because exporting removes from the network, and a bus that emptied a player's
 * storage into a chest the moment it was placed would be a trap. So the safe default is the useful one
 * in each direction, and neither needs a knob.
 *
 * <p>Rules are a whitelist once any exist: the first rule matching an item decides, and an item no rule
 * matches does not move. Order therefore matters, which is why this is a list and not a map — a player
 * writing "keep 200 iron" before "move all iron" means the first one.
 */
public final class TransferRules {

   private final List<TransferRule> rules = new ArrayList<>();

   /** Whether an empty rule list moves everything. See the class note: import yes, export no. */
   private final boolean emptyMovesEverything;

   public TransferRules(boolean emptyMovesEverything) {
      this.emptyMovesEverything = emptyMovesEverything;
   }

   public List<TransferRule> all() {
      return Collections.unmodifiableList(this.rules);
   }

   public boolean isEmpty() {
      return this.rules.isEmpty();
   }

   public int size() {
      return this.rules.size();
   }

   public void add(TransferRule rule) {
      this.rules.add(rule);
   }

   public void clear() {
      this.rules.clear();
   }

   /**
    * How many of an item may move, given what each side holds.
    *
    * @return zero when nothing may move, which is the answer for an unmatched item on a bus that has
    *     rules, and for every item on an empty export bus
    */
   public int allowed(String itemStringID, int inSource, int inDestination) {
      if (this.rules.isEmpty()) {
         return this.emptyMovesEverything ? Math.max(0, inSource) : 0;
      }

      for (TransferRule rule : this.rules) {
         if (rule.matches(itemStringID)) {
            return rule.allowed(inSource, inDestination);
         }
      }

      return 0;
   }

   public void addSaveData(SaveData save) {
      SaveData list = new SaveData("RULES");
      for (TransferRule rule : this.rules) {
         rule.addSaveData(list);
      }

      save.addSaveData(list);
   }

   public void applyLoadData(LoadData save) {
      this.rules.clear();
      LoadData list = save.getFirstLoadDataByName("RULES");
      if (list == null) {
         return;
      }

      for (LoadData rule : list.getLoadDataByName("RULE")) {
         TransferRule loaded = TransferRule.fromLoadData(rule);
         if (loaded != null) {
            this.rules.add(loaded);
         }
      }
   }
}
