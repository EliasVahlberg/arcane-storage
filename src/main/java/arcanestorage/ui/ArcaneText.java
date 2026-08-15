package arcanestorage.ui;

import java.awt.Color;

import arcanestorage.ArcaneStorage;
import necesse.gfx.forms.components.FormComponent;
import necesse.gfx.gameFont.FontOptions;

/**
 * Text colours for the mod's own panel.
 *
 * <p>Necesse's interface is parchment, so its style sheet is built for dark text on light: {@code activeTextColor} is
 * (20, 20, 20) and {@code errorTextColor} is (150, 0, 0). {@link FormLabel} applies the former as its default, which
 * is right everywhere in the game and wrong on {@link ArcanePanel} -- whose centre is (46, 34, 71). Near-black on dark
 * purple is what "the access point has black text when it should be white" was: not a mistake in one label but the
 * default being read against a background the default has never seen.
 *
 * <p>So every label the mod draws straight onto its own panel takes its options from here. The colours flip with the
 * panel: with the custom panel off, a form is parchment again and the style's own colours are the correct ones, and
 * returning them rather than a hardcoded light grey is what keeps that path a real path instead of a worse copy.
 *
 * <p>Labels inside a nested {@link necesse.gfx.forms.Form} do not need this -- a nested form draws the vanilla panel
 * behind itself, so the default is already right there. Using it anyway would make the text wrong in a second way.
 */
public final class ArcaneText {

   /** Bright enough to read on the panel, tinted rather than pure white so it sits in the palette. */
   private static final Color LIGHT = new Color(232, 226, 244);

   /** For a second line that explains the first. Dimmer, not smaller: the hierarchy is contrast, not size. */
   private static final Color LIGHT_DIM = new Color(176, 168, 196);

   /** Lifted well off the style's (150, 0, 0), which on dark purple reads as a smudge rather than as a warning. */
   private static final Color LIGHT_ERROR = new Color(255, 130, 130);

   private static final Color LIGHT_SUCCESS = new Color(140, 230, 150);

   private ArcaneText() {
   }

   private static boolean onOwnPanel() {
      return ArcaneStorage.SETTINGS.useCustomPanel;
   }

   /** The colour for anything that is simply text. */
   public static Color body(FormComponent component) {
      return onOwnPanel() ? LIGHT : component.getInterfaceStyle().activeTextColor;
   }

   public static FontOptions body(FormComponent component, int size) {
      return new FontOptions(size).color(body(component));
   }

   /** A hint, a position, a distance: true but not what the player came to read. */
   public static FontOptions dim(FormComponent component, int size) {
      return new FontOptions(size).color(
            onOwnPanel() ? LIGHT_DIM : component.getInterfaceStyle().inactiveTextColor);
   }

   /**
    * For text on one of the mod's own filled backings rather than on a panel.
    *
    * <p>No component and no setting: what is behind it is a colour this mod chose, so what is right on top of it does
    * not depend on which interface style the player is using.
    */
   public static FontOptions onFill(int size) {
      return new FontOptions(size).color(LIGHT);
   }

   public static Color errorColor(FormComponent component) {
      return onOwnPanel() ? LIGHT_ERROR : component.getInterfaceStyle().errorTextColor;
   }

   public static Color successColor(FormComponent component) {
      return onOwnPanel() ? LIGHT_SUCCESS : component.getInterfaceStyle().successTextColor;
   }

   public static FontOptions error(FormComponent component, int size) {
      return new FontOptions(size).color(errorColor(component));
   }

   public static FontOptions success(FormComponent component, int size) {
      return new FontOptions(size).color(successColor(component));
   }
}
