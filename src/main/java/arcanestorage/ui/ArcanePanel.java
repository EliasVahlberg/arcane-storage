package arcanestorage.ui;

import necesse.gfx.GameBackground;
import necesse.gfx.drawOptions.texture.SharedTextureDrawOptions;
import necesse.engine.util.PointSetAbstract;
import necesse.gfx.ui.GameInterfaceStyle;

/**
 * The panel behind the mod's windows: a {@link GameBackground} that reads the mod's own interface style.
 *
 * <h2>Why this class still exists once there is a style</h2>
 *
 * {@link ArcaneStyles#apply} reaches every component in a window, because each one asks
 * {@code getInterfaceStyle()} for its art. A form's own panel is the one thing it does not reach:
 * {@code Form}'s default background is the static {@code GameBackground.form}, and every method on that object
 * reads {@code Settings.UI.form} — the player's global style, not the component's. So a window given the mod's
 * style would draw mod buttons and mod slots on a vanilla panel.
 *
 * <p>This is that missing link, and it is thin: a {@code GameBackground} whose nine methods forward to the
 * current style's own {@code form} textures. {@code GameInterfaceStyle.form} is a {@code GameBackgroundTextures},
 * which implements all nine but is **not** a {@code GameBackground}, so the forwarding cannot be avoided by
 * handing the field over directly.
 *
 * <p>The earlier version of this class pointed at a single purple sheet of the mod's own and carried the
 * nine-slice geometry — slice 12, edge margin 8 — as constants, because it was reading a texture the engine knew
 * nothing about. Those numbers now live where they belong: the style's own {@code form} field declares them, and
 * the mod's sheets are authored to the same grid as the game's because they are recolours of it.
 */
public final class ArcanePanel {

   private static final GameBackground DELEGATE = new StylePanel();

   private ArcanePanel() {
   }

   /**
    * The background to give a form.
    *
    * <p>Returns {@code GameBackground.form} under the vanilla theme — which is exactly what the form would have
    * used untouched, rather than a degraded copy of it.
    */
   public static GameBackground of() {
      return ArcaneStyles.current() == null ? GameBackground.form : DELEGATE;
   }

   /** Resolved per call rather than captured, so a theme change needs no window to be rebuilt. */
   private static GameInterfaceStyle style() {
      GameInterfaceStyle current = ArcaneStyles.current();
      return current == null ? necesse.engine.Settings.UI : current;
   }

   private static final class StylePanel extends GameBackground {

      @Override
      public SharedTextureDrawOptions getOutlineDrawOptions(int x, int y, int width, int height) {
         return style().form.getOutlineDrawOptions(x, y, width, height);
      }

      @Override
      public SharedTextureDrawOptions getCenterDrawOptions(int x, int y, int width, int height) {
         return style().form.getCenterDrawOptions(x, y, width, height);
      }

      @Override
      public SharedTextureDrawOptions getDrawOptions(int x, int y, int width, int height) {
         return style().form.getDrawOptions(x, y, width, height);
      }

      @Override
      public SharedTextureDrawOptions getOutlineEdgeDrawOptions(int x, int y, int width, int height) {
         return style().form.getOutlineEdgeDrawOptions(x, y, width, height);
      }

      @Override
      public SharedTextureDrawOptions getCenterEdgeDrawOptions(int x, int y, int width, int height) {
         return style().form.getCenterEdgeDrawOptions(x, y, width, height);
      }

      @Override
      public SharedTextureDrawOptions getEdgeDrawOptions(int x, int y, int width, int height) {
         return style().form.getEdgeDrawOptions(x, y, width, height);
      }

      @Override
      public SharedTextureDrawOptions getTiledDrawOptions(
            int x, int y, int xPadding, int yPadding, PointSetAbstract<?> tiles, int tileWidth, int tileHeight) {
         return style().form.getTiledDrawOptions(x, y, xPadding, yPadding, tiles, tileWidth, tileHeight);
      }

      @Override
      public SharedTextureDrawOptions getTiledEdgeDrawOptions(
            int x, int y, int xPadding, int yPadding, PointSetAbstract<?> tiles, int tileWidth, int tileHeight) {
         return style().form.getTiledEdgeDrawOptions(x, y, xPadding, yPadding, tiles, tileWidth, tileHeight);
      }

      @Override
      public java.awt.Color getCenterColor() {
         return style().form.getCenterColor();
      }

      @Override
      public int getContentPadding() {
         return style().form.contentPadding;
      }
   }
}
