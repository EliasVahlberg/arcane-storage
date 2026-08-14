package arcanestorage.object;

import necesse.engine.registries.RecipeTechRegistry;
import arcanestorage.recipe.CostTable;
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
 * <p><b>The material costs are not here.</b> They live in {@code resources/recipes.properties}, read through
 * {@link arcanestorage.recipe.CostTable}, together with the reasoning for the numbers chosen. This enum holds
 * the ladder's identity -- how many rungs there are, what each is called, how much it holds, and which station
 * builds it -- and nothing about price.
 *
 * <p>The split is there because of what happened when it was not. The costs were fields on these constants and
 * were also written into a table in the roadmap; the two disagreed for two commits while every test passed, and
 * the commit that set out to reconcile them checked one file and asserted the other. An earlier version of this
 * paragraph confidently described a five-times-vanilla curve that the code had never contained.
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
   BASE("", 40, 1, null),

   /** Demonic era. */
   DEMONIC("demonic", 80, 2, "demonicbar"),

   /** Tungsten era. */
   TUNGSTEN("tungsten", 160, 4, "tungstenbar"),

   /** Fallen era, the top of the ladder. */
   FALLEN("fallen", 320, 8, "upgradeshard");

   /** Appended to the base string IDs. Empty for {@link #BASE}, whose IDs must never change. */
   public final String suffix;

   /** Stacks a Storage Unit of this tier holds. */
   public final int storageSlots;

   /** Crafting sockets a Station Unit of this tier offers. */
   public final int stationSockets;

   /** The signature material of the era, for tooltips and for describing the tier. Null for {@link #BASE}. */
   public final String material;

   UnitTier(String suffix, int storageSlots, int stationSockets, String material) {
      this.suffix = suffix;
      this.storageSlots = storageSlots;
      this.stationSockets = stationSockets;
      this.material = material;
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

   /** The tier above, or null at the top. */
   public UnitTier next() {
      return this.ordinal() == values().length - 1 ? null : values()[this.ordinal() + 1];
   }

   /**
    * What an <b>in-place</b> upgrade to this tier costs: the era materials, and nothing else.
    *
    * <p>The difference from {@link #ingredients(String)} is the tier below. A crafting recipe consumes one,
    * because it builds a new unit from scratch; an in-place upgrade does not, because <b>the unit standing
    * on the tile is that ingredient</b>. Charging for it twice would mean feeding a second unit into the one
    * being upgraded, which is not what the ladder means.
    *
    * <p>Every tier above {@link #BASE} costs only real item IDs, which this relies on: {@code anylog} is a
    * {@code GlobalIngredientRegistry} entry rather than an item, so no inventory can be asked how many it
    * holds. Only {@code BASE} uses it, and {@code BASE} is never an upgrade target -- there is nothing below
    * it to upgrade from. If a future tier is given a global ingredient, this returns something uncountable
    * and the availability check has to learn about global ingredients first.
    */
   public Ingredient[] upgradeCost() {
      return CostTable.materials(this.costKey());
   }

   /** This tier's key in {@code recipes.properties}. */
   public String costKey() {
      return "tier." + this.name().toLowerCase(java.util.Locale.ROOT);
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
      Ingredient[] materials = this.upgradeCost();

      if (previousId == null) {
         return materials;
      }

      Ingredient[] all = new Ingredient[materials.length + 1];
      all[0] = new Ingredient(previousId, 1);
      System.arraycopy(materials, 0, all, 1, materials.length);

      return all;
   }
}
