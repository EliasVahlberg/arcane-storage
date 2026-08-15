package arcanestorage.ui;

import java.awt.Color;

import arcanestorage.ArcaneStorage;
import necesse.engine.localization.message.LocalMessage;
import necesse.gfx.forms.components.FormComponent;
import necesse.gfx.ui.GameInterfaceStyle;

/**
 * The mod's own interface styles: one light, one dark, applied to this mod's windows and nowhere else.
 *
 * <h2>Why this is a style rather than a pile of overridden textures</h2>
 *
 * Every form component asks {@link FormComponent#getInterfaceStyle()} for its art, and that returns the
 * component's own {@code style} field when one is set, falling back to the player's global {@code Settings.UI}.
 * {@link FormComponent#overrideStyle} sets it, and {@code ComponentList.add} calls {@code inheritStyle(parent)}
 * on every component as it is added — so setting it once on a root form reaches the whole tree. The game does
 * exactly this itself: {@code CreditsDisplayAndControlsFormComponent} scopes the ghost style to one sub-form.
 *
 * <p>So one call per window buys every button, tab, slot, text input, scroll bar, check box and dropdown, and
 * the player's own choice of interface style is untouched everywhere else in the game. Neither style is added to
 * {@link GameInterfaceStyle#styles}, which is what the settings menu lists — these are not offered as global
 * skins, because they are drawn to sit behind this mod's windows and nothing else.
 *
 * <p>A style may be partial. {@code GameInterfaceStyle.fromFile} falls back to {@code ui/primal/<name>} per
 * file, so the 22 textures each set ships are a complete style and everything else is inherited.
 *
 * <h2>Two things overrideStyle does not do, both found by reading rather than by testing</h2>
 *
 * <ul>
 *   <li><b>It does not change a form's panel.</b> {@code Form}'s default background is the static
 *       {@code GameBackground.form}, whose every method reads {@code Settings.UI.form} — the global style, not
 *       the component's. {@link ArcanePanel} exists to close that gap and must still be set per form.
 *   <li><b>It does not colour labels.</b> {@code FormLabel}'s constructor takes
 *       {@code getInterfaceStyle().activeTextColor} as its default colour, and a component is constructed
 *       before it is added, so {@code inheritStyle} has not run yet and the label reads the *global* style. That
 *       is why {@link ArcaneText} still decides label colours, and why {@link #DARK_TEXT} is a constant here
 *       rather than only a field on the dark style.
 * </ul>
 */
public final class ArcaneStyles {

   /** Which set a window is drawn with. */
   public enum Theme {
      /** The player's own choice, untouched. */
      VANILLA,
      /** Light slate: the vanilla palette's lightness with a cool hue. */
      SLATE,
      /** The same palette compressed into a dark band. */
      DARK;

      public static Theme of(String name) {
         for (Theme t : values()) {
            if (t.name().equalsIgnoreCase(name)) {
               return t;
            }
         }

         return SLATE;
      }

      public String settingValue() {
         return this.name().toLowerCase(java.util.Locale.ROOT);
      }
   }

   /** Text on the dark theme's panel. Off-white with a trace of the panel's own hue, not pure white. */
   public static final Color DARK_TEXT = new Color(214, 224, 232);

   private static GameInterfaceStyle slate;

   private static GameInterfaceStyle dark;

   private ArcaneStyles() {
   }

   /**
    * Builds both styles and loads their textures. Client-only — call from {@code initResources}.
    *
    * <p>A dedicated server never builds a form and must never hold a UI texture, and {@code loadTextures}
    * reaches OpenGL through {@code GameTexture.fromFile}, so calling this server-side would fail rather than
    * merely waste memory.
    */
   public static void load() {
      slate = new GameInterfaceStyle(new LocalMessage("ui", "arcanestorage_theme_slate"), "arcane");
      dark = new GameInterfaceStyle(new LocalMessage("ui", "arcanestorage_theme_dark"), "arcanedark");

      // The dark set's panel sits at lightness 0.27, and the inherited colours are built for parchment: near-black
      // on that is the bug this mod already shipped once. Set before loadTextures only because it reads better
      // here; the colours are plain fields and the order does not matter to the engine.
      dark.activeTextColor = DARK_TEXT;
      dark.highlightTextColor = new Color(255, 255, 255);
      dark.inactiveTextColor = new Color(140, 152, 163);
      dark.activeFadedTextColor = new Color(170, 182, 194);
      dark.inactiveFadedTextColor = new Color(120, 132, 143);
      dark.textBoxTextColor = DARK_TEXT;
      dark.activeButtonTextColor = DARK_TEXT;
      dark.highlightButtonTextColor = new Color(255, 255, 255);
      dark.inactiveButtonTextColor = new Color(140, 152, 163);

      // The engine's success and error greens and reds are (0,125,0) and (150,0,0) — chosen against parchment and
      // close to unreadable on a dark panel. Lifted, not hue-shifted: which colour means what is a convention the
      // player already knows from the rest of the game.
      dark.successTextColor = new Color(120, 214, 130);
      dark.warningTextColor = new Color(232, 196, 96);
      dark.errorTextColor = new Color(240, 124, 124);

      slate.loadTextures();
      dark.loadTextures();
   }

   /** The configured theme, or {@link Theme#VANILLA} when the mod should keep out of the way. */
   public static Theme theme() {
      return ArcaneStorage.SETTINGS == null ? Theme.SLATE : Theme.of(ArcaneStorage.SETTINGS.theme);
   }

   /** The style to draw with, or null under {@link Theme#VANILLA} — meaning "leave the component alone". */
   public static GameInterfaceStyle current() {
      switch (theme()) {
         case SLATE:
            return slate;
         case DARK:
            return dark;
         default:
            return null;
      }
   }

   /**
    * Point a root form and everything later added to it at the mod's style.
    *
    * <p><b>Call this before adding components.</b> {@code inheritStyle} copies the parent's style at add time, so
    * a component added first keeps the global style and a window ends up half in each — which looks like a
    * texture problem and is an ordering one.
    */
   public static <T extends FormComponent> T apply(T component) {
      GameInterfaceStyle style = current();
      if (style != null) {
         component.overrideStyle(style);
      }

      return component;
   }
}
