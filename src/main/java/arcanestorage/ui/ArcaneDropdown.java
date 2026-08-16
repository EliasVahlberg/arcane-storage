package arcanestorage.ui;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

import necesse.engine.Settings;
import necesse.engine.input.InputEvent;
import necesse.engine.input.InputID;
import necesse.gfx.forms.controller.ControllerFocus;
import necesse.engine.localization.message.GameMessage;
import necesse.gfx.forms.FormClickHandler;
import necesse.gfx.forms.components.FormComponent;
import necesse.gfx.forms.components.FormDropdownSelectionButton;
import necesse.gfx.forms.components.FormInputSize;
import necesse.gfx.forms.events.FormValueEvent;
import necesse.gfx.forms.floatMenu.SelectionFloatMenu;
import necesse.gfx.gameTooltips.StringTooltips;
import necesse.gfx.gameFont.FontOptions;
import necesse.gfx.ui.ButtonColor;
import necesse.gfx.ui.GameInterfaceStyle;
import necesse.engine.gameLoop.tickManager.TickManager;
import necesse.entity.mobs.PlayerMob;

/**
 * A dropdown whose open menu is drawn with the mod's own theme rather than the game's global one.
 *
 * <h2>The gap this closes</h2>
 *
 * {@link ArcaneStyles} documents two things {@code overrideStyle} does not reach. This is a third, and it was
 * visible in play: the dropdown *button* is themed, because {@code FormDropdownSelectionButton.draw} asks
 * {@code getInterfaceStyle()} for its art, but the panel that opens below it stayed base-game wood in every theme.
 *
 * <p>The cause is one hardcoded line. The open panel is a {@link SelectionFloatMenu}, built by the private
 * {@code OptionsList.getMenu} as {@code new SelectionFloatMenu(button, SelectionFloatMenu.Solid(...), minWidth)},
 * and {@code Solid} reads {@code Settings.UI.selectionBox} -- the global style, not the component's. So the menu
 * could not follow a scoped style however the button was configured.
 *
 * <p>No new art was needed: {@code selectionbox.png}, {@code selectionbox_highlighted.png} and
 * {@code selectionbox_inactive.png} already shipped in both style sets and had simply never been reachable.
 *
 * <h2>Why a subclass and not a patch</h2>
 *
 * The obvious alternative was a {@code @ModMethodPatch} on {@code getMenu}, or on the {@code SelectionFloatMenu}
 * constructor, swapping the style when the parent component carries one of ours. It was rejected: {@code getMenu}
 * is a private method of a private inner class, which is the most fragile target a patch can bind to, and the mod
 * loader does not stop a mod built for one game version from loading on the next -- {@code ModProvider} only
 * colours the version label red. A patch that stopped binding would therefore reach players as a fault rather
 * than as a missing feature, and all of this is cosmetic.
 *
 * <p>{@code clickHandler} is a protected, non-final field, so replacing the open behaviour needs no patching at
 * all. Everything else here is public API: the {@link SelectionFloatMenu} constructor takes a style, and
 * {@code add} accepts both entries and submenus.
 *
 * <h2>Consequence for callers</h2>
 *
 * The inherited {@code options} field is <b>not used</b> and populating it does nothing, because its option
 * containers are private to the engine class and cannot be read back out. Use {@link #choices} instead, which
 * mirrors the same {@code add} / {@code addSub} shape. The inherited field cannot be hidden, so this is the one
 * trap in the class.
 */
public class ArcaneDropdown<T> extends FormDropdownSelectionButton<T> {

   /** Font size of the entries, matching the engine's own dropdowns. */
   private static final int MENU_FONT = 12;

   /** The option tree, built by the caller and turned into a menu on each click. */
   public final Options choices = new Options();

   private SelectionFloatMenu openMenu;

   /**
    * The label currently shown, mirrored because the engine's own {@code text} field is private with no accessor
    * and a refused selection has to be able to put the previous one back.
    */
   private GameMessage shown;

   public ArcaneDropdown(int x, int y, FormInputSize size, ButtonColor color, int width) {
      super(x, y, size, color, width);
      this.clickHandler = new FormClickHandler(
            event -> this.isActive()
                  && this.isMouseOver(event)
                  && (this.openMenu == null
                        || this.openMenu.isDisposed() && !InputEvent.isFromSameEvent(event, this.openMenu.removeEvent)),
            event -> event.getID() == InputID.LEFT_CLICK
                  || event.getID() == InputID.RIGHT_CLICK && this.acceptRightClicks,
            this::openMenu);
   }

   public ArcaneDropdown(int x, int y, FormInputSize size, ButtonColor color, int width, GameMessage startMessage) {
      this(x, y, size, color, width);
      this.setSelected(null, startMessage);
   }

   /**
    * Opens the themed menu. The offsets and the controller branch are the engine's own, from the handler this
    * replaces, so the panel lands exactly where the wood one did.
    */
   private void openMenu(InputEvent event) {
      this.playTickSound();
      this.openMenu = this.buildMenu(this.choices, this.width - 4);

      if (event.isControllerEvent()) {
         ControllerFocus focus = this.getManager().getCurrentFocus();
         if (focus != null) {
            this.getManager().openFloatMenuAt(this.openMenu, focus.boundingBox.x,
                  focus.boundingBox.y + this.size.textureDrawOffset + this.size.height);
         } else {
            this.getManager().openFloatMenuAt(this.openMenu, 0, 0);
         }
      } else {
         this.getManager().openFloatMenu(this.openMenu,
               this.getX() - event.pos.hudX,
               this.getY() - event.pos.hudY + this.size.textureDrawOffset + this.size.height);
      }
   }

   private SelectionFloatMenu buildMenu(Options options, int minWidth) {
      SelectionFloatMenu menu = new ThemedMenu(this, minWidth);
      for (Entry entry : options.entries) {
         entry.addTo(menu);
      }

      return menu;
   }

   @Override
   public void setSelected(T value, GameMessage text) {
      super.setSelected(value, text);
      this.shown = text;
   }

   /**
    * Selects a value and tells listeners, mirroring the engine's {@code selectedOption}.
    *
    * <p>{@code setSelected} deliberately fires nothing -- it is how the engine sets an initial value -- so the
    * event is raised here. {@code preventDefault} is honoured for the same reason the engine honours it: a
    * listener that refuses a choice should leave the previous one showing.
    */
   private void choose(T value, GameMessage text) {
      FormValueEvent<FormDropdownSelectionButton<?>, T> event = new FormValueEvent<>(this, value);
      T previousValue = this.getSelected();
      GameMessage previousText = this.shown;

      this.setSelected(value, this.setSelectedText || previousText == null ? text : previousText);
      this.selectedEvents.onEvent(event);

      if (event.hasPreventedDefault()) {
         this.setSelected(previousValue, previousText == null ? text : previousText);
      } else if (this.openMenu != null && !this.openMenu.isDisposed()) {
         this.openMenu.remove();
      }
   }

   /**
    * A selection menu that draws with the component's interface style instead of the player's global one.
    *
    * <h2>Why it is done by swapping a static field, which deserves an explanation</h2>
    *
    * The engine's intended answer is a {@code SelectionFloatMenuStyle}, and the constructor accepts one, so the
    * obvious implementation is to write a style that reads {@code owner.getInterfaceStyle()}. That cannot be
    * compiled. {@code SelectionFloatMenuStyle} is a public abstract class whose {@code drawListBackground} and
    * {@code drawListBackgroundEdge} both take a {@code SelectionFloatMenu.SelectionBoxList} -- a <b>private</b>
    * inner class. The type is unnameable outside {@code SelectionFloatMenu}, so no code outside that file can
    * implement the abstract class. It is public in name only, and every menu in the game therefore draws with
    * {@code Solid}, which reads the global {@code Settings.UI}.
    *
    * <p>What is left is to make {@code Settings.UI} be our style for exactly as long as this menu is drawing.
    * {@code draw} is public and not final, so no bytecode patching is needed, and the alternatives were all worse:
    * patching the private {@code OptionsList.getMenu} binds to the most fragile target available and would still
    * hit the same unimplementable interface; and reimplementing {@link necesse.gfx.forms.floatMenu.FloatMenu}
    * means hand-writing hit testing, dismissal and nesting to change a background.
    *
    * <p>Three things make the swap safe rather than merely short. Drawing is single threaded, so nothing else
    * reads the field during the call. The previous value is restored in a {@code finally}, so an exception
    * mid-draw cannot leave the game wearing this mod's skin. And the previous value is saved rather than assumed,
    * which is what makes nesting work: {@code draw} calls {@code subMenu.draw} virtually, so a submenu built by
    * {@link #buildMenu} swaps and restores inside the parent's own swap.
    */
   private static class ThemedMenu extends SelectionFloatMenu {

      private final FormComponent owner;

      ThemedMenu(FormComponent owner, int minWidth) {
         super(owner, SelectionFloatMenu.Solid(new FontOptions(MENU_FONT)), minWidth);
         this.owner = owner;
      }

      @Override
      public void draw(TickManager tickManager, PlayerMob perspective) {
         GameInterfaceStyle previous = Settings.UI;
         Settings.UI = this.owner.getInterfaceStyle();

         try {
            super.draw(tickManager, perspective);
         } finally {
            Settings.UI = previous;
         }
      }
   }

   /** One level of the option tree. */
   public final class Options {

      private final List<Entry> entries = new ArrayList<>();

      public Options add(T value, GameMessage text) {
         this.entries.add(menu -> menu.add(text.translate(), () -> ArcaneDropdown.this.choose(value, text)));
         return this;
      }

      /**
       * An entry that can be shown but refused, with an optional tooltip saying why.
       *
       * <p>The tooltip wrapping is the engine's own, from the matching {@code OptionsList.add}: a supplier
       * returning null means no tooltip that frame, which is how a reason that changes over time is expressed.
       */
      public Options add(T value, GameMessage text, Supplier<GameMessage> tooltip, Supplier<Boolean> isActive) {
         this.entries.add(menu -> menu.add(
               text.translate(),
               isActive,
               null,
               tooltip == null ? null : () -> {
                  GameMessage message = tooltip.get();
                  return message == null ? null : new StringTooltips(message.translate());
               },
               () -> ArcaneDropdown.this.choose(value, text)));
         return this;
      }

      /** Adds a nested level and returns it, so callers read the same as the engine's {@code addSub}. */
      public Options addSub(GameMessage text) {
         Options sub = new Options();
         this.entries.add(menu -> menu.add(text.translate(),
               ArcaneDropdown.this.buildMenu(sub, 0), ArcaneDropdown.this.removingSubmenuRemovesParent));
         return sub;
      }

      public void clear() {
         this.entries.clear();
      }

      public boolean isEmpty() {
         return this.entries.isEmpty();
      }
   }

   /** An entry knows how to add itself to a menu, which is all the tree is for. */
   private interface Entry {
      void addTo(SelectionFloatMenu menu);
   }
}
