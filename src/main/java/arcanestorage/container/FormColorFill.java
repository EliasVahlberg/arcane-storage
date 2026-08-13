package arcanestorage.container;

import java.awt.Color;
import java.awt.Rectangle;
import java.util.Collections;
import java.util.List;

import necesse.engine.gameLoop.tickManager.TickManager;
import necesse.entity.mobs.PlayerMob;
import necesse.gfx.Renderer;
import necesse.engine.input.InputEvent;
import necesse.engine.input.controller.ControllerEvent;
import necesse.gfx.forms.components.FormComponent;
import necesse.gfx.forms.controller.ControllerFocus;
import necesse.gfx.forms.controller.ControllerNavigationHandler;
import necesse.gfx.forms.position.FormFixedPosition;
import necesse.gfx.forms.position.FormPosition;
import necesse.gfx.forms.position.FormPositionContainer;

/**
 * A flat coloured rectangle, for giving a panel a backing its content can be read against.
 *
 * <p>Necesse has no such component -- the closest are the progress bars, which draw a coloured quad as part of
 * something else. So this is the primitive on its own: {@code Renderer.initQuadDraw}, the same call the game
 * uses to fade the screen.
 *
 * <p>Takes no input at all. That is not laziness but a requirement: a component that claimed the mouse over its
 * rectangle would starve everything drawn on top of it, which is exactly how the bus panel's Apply button came
 * to be inert. It returns no hitboxes for the same reason.
 */
public class FormColorFill extends FormComponent implements FormPositionContainer {

   protected FormPosition position;

   private final int width;

   private final int height;

   private final Color color;

   /** Set by the owner each frame. A backing for a list of problems has no business showing when there are none. */
   public boolean visible = true;

   public FormColorFill(int x, int y, int width, int height, Color color) {
      this.position = new FormFixedPosition(x, y);
      this.width = width;
      this.height = height;
      this.color = color;
   }

   @Override
   public void draw(TickManager tickManager, PlayerMob perspective, Rectangle renderBox) {
      if (this.visible) {
         Renderer.initQuadDraw(this.width, this.height).color(this.color).draw(this.getX(), this.getY());
      }
   }

   @Override
   public void handleInputEvent(InputEvent event, TickManager tickManager, PlayerMob perspective) {
   }

   @Override
   public void handleControllerEvent(ControllerEvent event, TickManager tickManager, PlayerMob perspective) {
   }

   @Override
   public void addNextControllerFocus(List<ControllerFocus> list, int currentXOffset, int currentYOffset,
         ControllerNavigationHandler customNavigationHandler, Rectangle area, boolean draw) {
   }

   @Override
   public List<Rectangle> getHitboxes() {
      return Collections.emptyList();
   }

   @Override
   public FormPosition getPosition() {
      return this.position;
   }

   @Override
   public void setPosition(FormPosition position) {
      this.position = position;
   }
}
