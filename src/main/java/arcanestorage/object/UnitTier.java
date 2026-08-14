package arcanestorage.object;

import necesse.engine.registries.RecipeTechRegistry;
import necesse.inventory.recipe.Ingredient;
import necesse.inventory.recipe.Tech;

/**
 * The four rungs both unit ladders climb, and everything that differs between them.
 *
 * <p><b>Four tiers, not five or eight, because Necesse already has exactly four.</b> Crafting stations upgrade
 * base -> Demonic -> Tungsten -> Fallen, and aligning to that means every upgrade lands where the player is
 * already upgrading, paid for with materials they are already gathering. Magic Storage's eight capacity tiers
 * are not a target: seven of its eleven storage units are the same thing with a bigger number, and a ladder
 * whose rungs exist only to be climbed is what this one is trying not to be.
 *
 * <p>Both numbers double per rung -- 40/80/160/320 stacks and 1/2/4/8 sockets. Doubling is chosen over a
 * gentler curve because it is the only shape a player can predict without reading a table, and because the
 * top rung has to feel worth a Fallen-era cost. The base 40 is not arbitrary either: it is exactly vanilla's
 * container ceiling, so a first unit is worth precisely one chest and the ladder starts from parity rather
 * than from an advantage.
 *
 * <p><b>The material costs are five times what the equivalent vanilla station charges at the same era</b> --
 * a unit is a serious investment rather than a bit of furniture, and forty stacks of storage should not be
 * cheaper than the bench that fills it. Vanilla charges demonicbar x5 for a Demonic Workstation Duo,
 * tungstenbar x8 with quartz x4 for a Tungsten Workstation, and upgradeshard x15 with alchemyshard x15 for a
 * Fallen Workstation.
 *
 * <p><b>The tier below is consumed at one, and is deliberately not scaled with the materials.</b> Scaling it
 * would compound down the ladder -- five Tungsten units means twenty-five Demonic and one hundred and
 * twenty-five base units -- which is not a cost curve but an accident. One keeps the upgrade readable: exactly
 * one unit goes in and one comes out, and only the materials grow.
 *
 * <p><b>Each tier's recipe consumes the tier below it.</b> That is the difference between an upgrade path and
 * four unrelated objects: nothing is stranded, a player's investment carries forward, and no one ends up with
 * a chest of obsolete units. It also means the string IDs of the lower tiers can never be retired, since a
 * recipe names them.
 *
 * <p>The tier does <b>not</b> decide which mechanics exist. Filters, search, sorting and the buses are
 * ungated on purpose -- gating scope is fair, gating usability reads as broken -- so what grows here is
 * capacity and socket count only.
 */
public enum UnitTier {

   /** Vanilla's container ceiling, and one crafting socket. Craftable the moment a Workstation exists. */
   BASE("", 40, 1, null, new String[]{"anylog", "8", "ironbar", "2"}),

   /** Demonic era. */
   DEMONIC("demonic", 80, 2, "demonicbar", new String[]{"demonicbar", "5"}),

   /** Tungsten era. */
   TUNGSTEN("tungsten", 160, 4, "tungstenbar", new String[]{"tungstenbar", "8", "quartz", "4"}),

   /** Fallen era, the top of the ladder. */
   FALLEN("fallen", 320, 8, "upgradeshard", new String[]{"upgradeshard", "10", "alchemyshard", "10"});

   /** Appended to the base string IDs. Empty for {@link #BASE}, whose IDs must never change. */
   public final String suffix;

   /** Stacks a Storage Unit of this tier holds. */
   public final int storageSlots;

   /** Crafting sockets a Station Unit of this tier offers. */
   public final int stationSockets;

   /** The signature material of the era, for tooltips and for describing the tier. Null for {@link #BASE}. */
   public final String material;

   private final String[] costs;

   UnitTier(String suffix, int storageSlots, int stationSockets, String material, String[] costs) {
      this.suffix = suffix;
      this.storageSlots = storageSlots;
      this.stationSockets = stationSockets;
      this.material = material;
      this.costs = costs;
   }

   /** The Storage Unit string ID at this tier. */
   public String storageId() {
      return "arcanestorageunit" + this.suffix;
   }

   /** The Station Unit string ID at this tier. */
   public String stationId() {
      return "arcanestoragestationunit" + this.suffix;
   }

   /** The tier below, or null at the bottom. */
   public UnitTier below() {
      return this.ordinal() == 0 ? null : values()[this.ordinal() - 1];
   }

   /**
    * The crafting station this tier is made at.
    *
    * <p>Resolved on call rather than stored in the enum, because {@link RecipeTechRegistry}'s fields are
    * populated during registration and an enum constant is initialised the first time the class is touched --
    * which, for anything read during {@code init}, is early enough to capture nulls.
    */
   public Tech tech() {
      switch (this) {
         case DEMONIC:
            return RecipeTechRegistry.DEMONIC_WORKSTATION;
         case TUNGSTEN:
            return RecipeTechRegistry.TUNGSTEN_WORKSTATION;
         case FALLEN:
            return RecipeTechRegistry.FALLEN_WORKSTATION;
         default:
            return RecipeTechRegistry.WORKSTATION;
      }
   }

   /**
    * What one unit of this tier costs, including the tier below it where there is one.
    *
    * @param previousId the lower tier's string ID for this ladder, or null at the bottom
    */
   public Ingredient[] ingredients(String previousId) {
      int extra = previousId == null ? 0 : 1;
      Ingredient[] all = new Ingredient[this.costs.length / 2 + extra];

      if (previousId != null) {
         all[0] = new Ingredient(previousId, 1);
      }

      for (int i = 0; i < this.costs.length; i += 2) {
         all[i / 2 + extra] = new Ingredient(this.costs[i], Integer.parseInt(this.costs[i + 1]));
      }

      return all;
   }
}
