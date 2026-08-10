package arcanestorage;

import necesse.engine.modLoader.ModSettings;
import necesse.engine.save.LoadData;
import necesse.engine.save.SaveData;

/**
 * The mod's own client settings, persisted by the game.
 *
 * <p>Returned from {@code ArcaneStorage.initSettings()}, which the loader calls by name; the game
 * then writes these to a per-mod settings file and loads them back, the same mechanism it uses for
 * mod key bindings. So this is not settings infrastructure invented here -- it is the engine's, and
 * it costs one class and one method.
 *
 * <p>Only genuinely optional presentation belongs here. Anything that changes what the terminal
 * <i>does</i> should be a decision, not a switch.
 */
public class ArcaneStorageSettings extends ModSettings {

   /**
    * Whether the crafting tab groups recipes into collapsible category sections, as a vanilla
    * crafting bench does, rather than one flat grid.
    *
    * <p>Defaults to grouped: that is how every crafting interface in the game already reads, and a
    * terminal with several benches installed lists more recipes than any single bench, which is
    * exactly when grouping earns its keep. Flat stays available because it is fewer clicks when the
    * list is short, and Elias asked for both rather than a decision between them.
    */
   public boolean groupCraftingByCategory = true;

   @Override
   public void addSaveData(SaveData save) {
      save.addBoolean("groupCraftingByCategory", this.groupCraftingByCategory);
   }

   @Override
   public void applyLoadData(LoadData save) {
      this.groupCraftingByCategory = save.getBoolean("groupCraftingByCategory", this.groupCraftingByCategory);
   }
}
