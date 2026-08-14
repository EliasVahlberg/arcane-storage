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

   /**
    * Whether the mod's interfaces draw on the mod's own purple panel rather than the player's chosen
    * interface style.
    *
    * <p>Defaults on, because the panel is the difference between a storage terminal that looks like
    * part of this mod and one that looks like a chest with more buttons. It is a setting rather than a
    * decision because the cost falls on a specific player: Necesse ships several interface styles and
    * shows a selector when it has more than one, so anyone who deliberately themed their game has that
    * choice quietly overridden for our forms. Off restores {@code GameBackground.form}, which is the
    * panel a form would have used had this mod never touched it.
    */
   public boolean useCustomPanel = true;

   @Override
   public void addSaveData(SaveData save) {
      save.addBoolean("groupCraftingByCategory", this.groupCraftingByCategory);
      save.addBoolean("useCustomPanel", this.useCustomPanel);
   }

   @Override
   public void applyLoadData(LoadData save) {
      this.groupCraftingByCategory = save.getBoolean("groupCraftingByCategory", this.groupCraftingByCategory);
      this.useCustomPanel = save.getBoolean("useCustomPanel", this.useCustomPanel);
   }
}
