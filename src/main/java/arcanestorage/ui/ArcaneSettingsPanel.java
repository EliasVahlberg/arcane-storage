package arcanestorage.ui;

import arcanestorage.ArcaneStorage;
import arcanestorage.object.UnitTier;
import arcanestorage.remote.Reach;
import necesse.engine.Settings;
import necesse.engine.localization.Localization;
import necesse.engine.localization.message.LocalMessage;
import necesse.gfx.forms.Form;
import necesse.gfx.forms.components.FormFlow;
import necesse.gfx.forms.components.FormInputSize;
import necesse.gfx.forms.components.FormLabel;
import necesse.gfx.forms.components.localComponents.FormLocalLabel;
import necesse.gfx.ui.ButtonColor;

/**
 * The mod's own settings panel, because the engine offers a mod nowhere to put one.
 *
 * <p>That absence was checked rather than assumed. {@code ModSettings} declares exactly two methods,
 * {@code addSaveData} and {@code applyLoadData}, and no UI of any kind. A mod's row in {@code ModsForm} carries enable
 * and reorder buttons only. {@code SettingsForm} is a {@code FormSwitcher} holding a fixed set of private {@code Form}
 * fields -- world, general, language, controls, interface, graphics, sound, mods -- with no registry and no hook, so
 * there is no supported way to add a page to it. A mod gets a config file and nothing else.
 *
 * <p>What the engine does provide is the other half: {@link Settings#saveClientSettings()} is public and calls its
 * private {@code saveModSettings} for every enabled mod. So a setting changed here persists through the game's own
 * mechanism rather than through a file this mod writes itself.
 *
 * <h2>Why only the theme is editable</h2>
 *
 * <p>The config holds more than this panel offers, and each omission has a reason. Reach and band numbers are read
 * only where access is decided, which is the server -- a client editing them would change nothing while appearing to
 * change something. Recipe costs are read independently by both sides and the engine sends no recipe data, so a
 * client-side edit produces ingredient lists the server refuses. Presentation is the only category that is a given
 * player's to decide, and of the two presentation settings the crafting layout already has a checkbox on the crafting
 * tab, beside the list it changes. That leaves the theme, which had no in-game control at all.
 */
public final class ArcaneSettingsPanel {

   private static final int PADDING = 10;

   private ArcaneSettingsPanel() {
   }

   /**
    * Fills {@code form} with the settings controls.
    *
    * <p>Takes a form rather than making one, so the caller keeps ownership of size, style and placement. The terminal
    * builds and styles its own tabs, and a panel that created its own form would have to be told all three anyway.
    */
   public static void build(Form form, int width) {
      FormFlow flow = new FormFlow(PADDING);
      int textWidth = width - PADDING * 2;

      form.addComponent(flow.nextY(new FormLocalLabel("ui", "arcanestorage_settings_presentation",
            ArcaneText.body(form, 20), -1, PADDING, 0, textWidth), 8));

      form.addComponent(flow.nextY(new FormLocalLabel("ui", "arcanestorage_settings_themelabel",
            ArcaneText.body(form, 16), -1, PADDING, 0, textWidth), 4));

      ArcaneDropdown<ArcaneStyles.Theme> theme = form.addComponent(
            new ArcaneDropdown<ArcaneStyles.Theme>(PADDING, flow.next(FormInputSize.SIZE_24.height + 6),
                  FormInputSize.SIZE_24, ButtonColor.BASE, 220));

      // Listed in this order rather than the enum's, which declares VANILLA first because it is the identity case.
      // A player opening this wants the two themes the mod ships at the top and the opt-out at the bottom.
      for (ArcaneStyles.Theme option : new ArcaneStyles.Theme[] {
            ArcaneStyles.Theme.SLATE, ArcaneStyles.Theme.DARK, ArcaneStyles.Theme.VANILLA }) {
         theme.choices.add(option, label(option));
      }

      theme.setSelected(ArcaneStyles.theme(), label(ArcaneStyles.theme()));
      theme.onSelected(event -> {
         ArcaneStorage.SETTINGS.theme = event.value.settingValue();
         // The engine's own save path, which writes every enabled mod's config alongside the client's own settings.
         // The same call the crafting tab's checkbox already makes.
         Settings.saveClientSettings();
      });

      // Stated plainly rather than left to be discovered, because it is the first thing a player will notice.
      // ArcanePanel resolves the style per draw, so panels change at once; labels and buttons took their colour or
      // their style object at construction and a component keeps what it was given. Rebuilding every open window in
      // place would be a large change for a setting touched once.
      form.addComponent(flow.nextY(new FormLocalLabel("ui", "arcanestorage_settings_reopen",
            ArcaneText.dim(form, 12), -1, PADDING, 0, textWidth), 6));

      form.addComponent(flow.nextY(new FormLocalLabel("ui", "arcanestorage_settings_craftinghint",
            ArcaneText.dim(form, 12), -1, PADDING, 0, textWidth), 18));

      form.addComponent(flow.nextY(new FormLocalLabel("ui", "arcanestorage_settings_worldtitle",
            ArcaneText.body(form, 20), -1, PADDING, 0, textWidth), 6));

      form.addComponent(flow.nextY(new FormLocalLabel("ui", "arcanestorage_settings_worldnote",
            ArcaneText.dim(form, 12), -1, PADDING, 0, textWidth), 8));

      // Read from this client's own copy of the config, which is what the note above them says. In singleplayer that
      // copy is the authority; against a dedicated server the numbers actually enforced are that server's, and
      // implying otherwise would be worse than showing nothing.
      //
      // Asked per tier rather than per number. The number alone cannot answer this: Tungsten and Fallen are both
      // unlimited on their own level, and what separates them is that Fallen also leaves it, which is a property of
      // the tier and not of the range. Rendering the range alone made the Fallen row identical to the Tungsten one,
      // so the upgrade read as buying nothing.
      value(form, flow, width, "arcanestorage_settings_reachdemonic", Reach.summarise(UnitTier.DEMONIC));
      value(form, flow, width, "arcanestorage_settings_reachtungsten", Reach.summarise(UnitTier.TUNGSTEN));
      value(form, flow, width, "arcanestorage_settings_reachfallen", Reach.summarise(UnitTier.FALLEN));
      value(form, flow, width, "arcanestorage_settings_bandrange",
            tiles(ArcaneStorage.SETTINGS.bandRange));
      value(form, flow, width, "arcanestorage_settings_channels",
            ArcaneStorage.SETTINGS.bandChannelsDemonic + " / " + ArcaneStorage.SETTINGS.bandChannelsTungsten
                  + " / " + ArcaneStorage.SETTINGS.bandChannelsFallen);

      form.addComponent(flow.nextY(new FormLocalLabel("ui", "arcanestorage_settings_filehint",
            ArcaneText.dim(form, 12), -1, PADDING, 0, textWidth), 4));
   }

   private static void value(Form form, FormFlow flow, int width, String key, String text) {
      form.addComponent(flow.nextY(new FormLabel(
            Localization.translate("ui", key) + "   " + text,
            ArcaneText.body(form, 14), -1, PADDING * 2, 0, width - PADDING * 3), 2));
   }

   /** Negative means unlimited in the config, which is worth saying rather than printing a minus sign. */
   private static String tiles(int value) {
      return value < 0
            ? Localization.translate("ui", "arcanestorage_settings_unlimited")
            : Localization.translate("ui", "arcanestorage_settings_tiles", "n", String.valueOf(value));
   }

   private static LocalMessage label(ArcaneStyles.Theme theme) {
      return new LocalMessage("ui", "arcanestorage_theme_" + theme.settingValue());
   }
}
