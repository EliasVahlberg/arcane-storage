package arcanestorage.ui;

import java.awt.Color;
import java.awt.Rectangle;
import java.util.Collections;
import java.util.List;
import java.util.function.BiConsumer;

import necesse.engine.gameLoop.tickManager.TickManager;
import necesse.engine.input.InputEvent;
import necesse.engine.input.controller.ControllerEvent;
import necesse.engine.input.controller.ControllerInput;
import necesse.engine.util.GameBlackboard;
import necesse.entity.mobs.PlayerMob;
import necesse.gfx.GameBackground;
import necesse.gfx.forms.controller.ControllerFocus;
import necesse.gfx.forms.controller.ControllerNavigationHandler;
import necesse.gfx.forms.components.FormComponent;
import necesse.gfx.forms.position.FormFixedPosition;
import necesse.gfx.forms.position.FormPosition;
import necesse.gfx.forms.position.FormPositionContainer;
import necesse.gfx.gameTexture.GameTexture;
import necesse.gfx.gameTooltips.GameTooltipManager;
import necesse.gfx.gameTooltips.TooltipLocation;
import necesse.gfx.ui.HoverStateTextures;
import necesse.inventory.InventoryItem;

/**
 * One item stack, drawn and clicked exactly like {@code FormItemList.ItemElement} -- the grid slot this
 * replaces -- but as a standalone {@link FormComponent} rather than a grid element, since
 * {@link CategoryTreeForm} positions leaves itself and has no room for a second widget's own scrolling
 * grid nested inside its own layout.
 *
 * <p>Modeled more directly on {@code FormContainerCreativeItem}, the creative menu's own leaf cell, for
 * the same reason {@link CategoryTreeForm} is modeled on {@code CreativeItemsTab.CategoryForm}: both are
 * a plain {@code FormComponent} sitting at a fixed position inside a recursive tree, and vanilla already
 * solved exactly that shape. What differs is the click -- a creative slot spawns an infinite copy; this
 * one forwards to whatever the storage tab's own click handling already does for a withdraw, which stays
 * with the tab rather than being duplicated here.
 */
public class StorageItemCell extends FormComponent implements FormPositionContainer {

   private static final int SIZE = 32;

   public final InventoryItem item;

   private final BiConsumer<InventoryItem, InputEvent> onClicked;

   private FormPosition position;

   private boolean isHovering;

   public StorageItemCell(InventoryItem item, int x, int y, BiConsumer<InventoryItem, InputEvent> onClicked) {
      this.item = item;
      this.onClicked = onClicked;
      this.position = new FormFixedPosition(x, y);
   }

   @Override
   public void handleInputEvent(InputEvent event, TickManager tickManager, PlayerMob perspective) {
      if (event.isMouseMoveEvent()) {
         this.isHovering = this.isMouseOver(event);
         if (this.isHovering) {
            event.useMove();
         }
      }

      if (!event.isUsed() && event.isMouseClickEvent() && event.state && this.isMouseOver(event)) {
         this.onClicked.accept(this.item, event);
      }
   }

   @Override
   public void handleControllerEvent(ControllerEvent event, TickManager tickManager, PlayerMob perspective) {
      if (this.isControllerFocus() && event.getState() == ControllerInput.MENU_SELECT) {
         this.onClicked.accept(this.item, InputEvent.ControllerButtonEvent(event, tickManager));
         event.use();
      }
   }

   @Override
   public void addNextControllerFocus(
         List<ControllerFocus> list, int currentXOffset, int currentYOffset,
         ControllerNavigationHandler customNavigationHandler, Rectangle area, boolean draw) {
      ControllerFocus.add(list, area, this, this.getBoundingBox(), currentXOffset, currentYOffset,
            this.controllerInitialFocusPriority, customNavigationHandler);
   }

   @Override
   public void draw(TickManager tickManager, PlayerMob perspective, Rectangle renderBox) {
      boolean hovering = this.isHovering();
      Color color = hovering
            ? this.getInterfaceStyle().highlightElementColor
            : this.getInterfaceStyle().activeElementColor;

      HoverStateTextures slotVariants = this.getInterfaceStyle().inventoryslot_small;
      GameTexture slotTexture = hovering ? slotVariants.highlighted : slotVariants.active;
      slotTexture.initDraw().color(color).draw(this.getX(), this.getY());

      // The slot frame is drawn before this and unconditionally, so a stack whose icon fails still leaves a
      // cell the player can see and click -- withdrawing goes through handleInputEvent and never touches a
      // texture, so a missing icon costs appearance only, not access to the item.
      boolean drawn = ItemDrawGuard.drawStack(this.item, perspective, this.getX(), this.getY());

      if (hovering && drawn) {
         GameTooltipManager.addTooltip(this.item.getTooltip(perspective, new GameBlackboard()),
               GameBackground.getItemTooltipBackground(), TooltipLocation.FORM_FOCUS);
      }
   }

   @Override
   public List<Rectangle> getHitboxes() {
      return singleBox(new Rectangle(this.getX(), this.getY(), SIZE, SIZE));
   }

   private boolean isHovering() {
      return this.isHovering || this.isControllerFocus();
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
