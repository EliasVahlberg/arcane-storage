package arcanestorage.ui;

import java.awt.Color;

import arcanestorage.ArcaneStorage;
import necesse.engine.util.PointSetAbstract;
import necesse.gfx.GameBackground;
import necesse.gfx.GameBackgroundTextures;
import necesse.gfx.drawOptions.texture.SharedTextureDrawOptions;
import necesse.gfx.gameTexture.GameTexture;

/**
 * The mod's own form panel: the purple frame the terminal and bus interfaces are drawn on.
 *
 * <p>A form's panel is a {@link GameBackground}, which is not one image but nine draw options over a
 * nine-slice sheet -- outline, centre, edges, tiled variants and a content padding. {@link
 * GameBackgroundTextures} implements all nine over a pair of textures, and every {@code
 * GameBackground} the game ships is a thin subclass delegating to one of those. So is this. The only
 * difference is where the textures come from: vanilla's delegate to {@code Settings.UI.form}, which
 * is whichever interface style the player selected, and this one delegates to ours.
 *
 * <p><b>Which is the whole trade-off, and why it is a setting.</b> Necesse ships more than one
 * interface style and shows a selector when it has several. Overriding the panel makes the mod feel
 * like its own thing; it also quietly ignores a choice the player made deliberately. Neither answer
 * is right for everybody, so {@code ArcaneStorageSettings.useCustomPanel} decides, and the
 * unstyled path is a real path rather than a fallback -- {@link #of} returns {@link
 * GameBackground#form} when it is off, which is exactly what a form would have used anyway.
 *
 * <h2>The geometry, which is not guessable and was measured</h2>
 *
 * {@code edgeResolution} 12 and {@code edgeMargin} 8 are not free parameters -- they describe how the
 * engine reads the sheet, and the art was authored against them:
 *
 * <ul>
 *   <li>The slice is <b>12 px</b>. Only {@code x 0..4E-1} is read above {@code y = 4E}; width beyond
 *       48 exists purely to enlarge the centre tile, which is why the sheet is 64 wide and not 48.
 *   <li>The corner 2x2 block is <b>point-mirrored</b>: the panel's top-left corner lives at texture
 *       cell (1,1), so at x 12-23, y 12-23 rather than at the sheet's own corner.
 *   <li>Every slice is opaque only in its <b>inner third</b> -- columns 8-11 of the left edge, rows
 *       8-11 of the top. Measured on {@code ui/primal/formbackground.png} rather than assumed. That
 *       4 px band is the visible frame and the outer 8 px is transparent, which cancels exactly
 *       against {@code edgeMargin = 8} and lands the frame on the form's own edge. Drawing a full
 *       12 px frame instead would bleed 8 px outside the panel on all four sides.
 * </ul>
 *
 * <p>{@code contentPadding} is <b>0</b>, matching the vanilla form. It is tempting to add a few
 * pixels of breathing room, but every component in the terminal is already positioned against 0, so
 * a nonzero value here would shift the entire layout -- and the layout is the one thing that had to
 * stop moving before this panel could be drawn at all.
 */
public final class ArcanePanel {

   /** Nine-slice cell size. The art is authored to it; changing it invalidates the sheet. */
   private static final int SLICE = 12;

   /** How far outside the form the sheet is drawn, cancelling the transparent outer 8 px. */
   private static final int EDGE_MARGIN = 8;

   /** Zero, to match the vanilla form. See the class note: anything else moves every component. */
   private static final int CONTENT_PADDING = 0;

   /**
    * The panel, or null until {@code initResources} has run.
    *
    * <p>Null on a dedicated server for as long as the process lives, and that is correct rather than
    * unfortunate: {@code initResources} is client-only, forms are client-only, and a server that
    * loaded UI textures would be doing work for nobody.
    */
   private static GameBackground panel;

   private ArcanePanel() {
   }

   /**
    * Loads the sheet. Call from {@code initResources}, which is client-only.
    *
    * <p>The textures are loaded lazily by {@link GameBackgroundTextures#loadTextures()} rather than
    * here, and its loaders swallow {@link java.io.IOException} into a null texture, which the draw
    * options then render as the pink error texture. So a missing file shows up as visibly wrong art
    * rather than as a crash -- worth knowing, because it means "the panel looks broken" and "the
    * panel is missing" are the same symptom.
    */
   public static void load() {
      GameBackgroundTextures textures = new GameBackgroundTextures(
            SLICE, EDGE_MARGIN, CONTENT_PADDING,
            () -> GameTexture.fromFileRaw("ui/arcanestoragepanel"),
            () -> GameTexture.fromFileRaw("ui/arcanestoragepaneledge"));
      textures.loadTextures();
      panel = new TexturePanel(textures);
   }

   /**
    * The panel to draw a form on: ours if it is loaded and switched on, otherwise the player's own.
    *
    * <p>Deliberately a getter rather than a public field, so a form asks at the moment it is built
    * and a player who changes the setting sees it on the next open rather than the next restart.
    */
   public static GameBackground of() {
      if (panel == null || ArcaneStorage.SETTINGS == null || !ArcaneStorage.SETTINGS.useCustomPanel) {
         return GameBackground.form;
      }

      return panel;
   }

   /** Delegates all nine of {@link GameBackground}'s methods to a texture pair. */
   private static final class TexturePanel extends GameBackground {

      private final GameBackgroundTextures textures;

      private TexturePanel(GameBackgroundTextures textures) {
         this.textures = textures;
      }

      @Override
      public SharedTextureDrawOptions getOutlineDrawOptions(int x, int y, int width, int height) {
         return this.textures.getOutlineDrawOptions(x, y, width, height);
      }

      @Override
      public SharedTextureDrawOptions getCenterDrawOptions(int x, int y, int width, int height) {
         return this.textures.getCenterDrawOptions(x, y, width, height);
      }

      @Override
      public SharedTextureDrawOptions getDrawOptions(int x, int y, int width, int height) {
         return this.textures.getDrawOptions(x, y, width, height);
      }

      @Override
      public SharedTextureDrawOptions getOutlineEdgeDrawOptions(int x, int y, int width, int height) {
         return this.textures.getOutlineEdgeDrawOptions(x, y, width, height);
      }

      @Override
      public SharedTextureDrawOptions getCenterEdgeDrawOptions(int x, int y, int width, int height) {
         return this.textures.getCenterEdgeDrawOptions(x, y, width, height);
      }

      @Override
      public SharedTextureDrawOptions getEdgeDrawOptions(int x, int y, int width, int height) {
         return this.textures.getEdgeDrawOptions(x, y, width, height);
      }

      @Override
      public SharedTextureDrawOptions getTiledDrawOptions(
            int x, int y, int xPadding, int yPadding, PointSetAbstract<?> tiles, int tileWidth, int tileHeight) {
         return this.textures.getTiledDrawOptions(x, y, xPadding, yPadding, tiles, tileWidth, tileHeight);
      }

      @Override
      public SharedTextureDrawOptions getTiledEdgeDrawOptions(
            int x, int y, int xPadding, int yPadding, PointSetAbstract<?> tiles, int tileWidth, int tileHeight) {
         return this.textures.getTiledEdgeDrawOptions(x, y, xPadding, yPadding, tiles, tileWidth, tileHeight);
      }

      @Override
      public Color getCenterColor() {
         return this.textures.getCenterColor();
      }

      @Override
      public int getContentPadding() {
         return this.textures.contentPadding;
      }
   }
}
