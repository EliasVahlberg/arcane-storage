package arcanestorage.ui;

import java.util.ArrayList;
import java.util.List;

import necesse.engine.input.InputEvent;
import necesse.engine.input.InputID;
import necesse.engine.localization.message.GameMessage;
import necesse.gfx.forms.Form;
import necesse.gfx.forms.components.FormCheckBox;
import necesse.gfx.forms.components.FormInputSize;
import necesse.gfx.forms.components.FormTextButton;
import necesse.gfx.forms.floatMenu.FormFloatMenu;
import necesse.gfx.gameFont.FontManager;
import necesse.gfx.gameFont.FontOptions;
import necesse.gfx.ui.ButtonColor;

/**
 * A button that opens a themed panel of independent tickboxes, rather than a single choice.
 *
 * <h2>Why not {@link ArcaneDropdown}</h2>
 *
 * {@code ArcaneDropdown} answers "which one", and closes itself the instant a choice is made --
 * {@code choose} calls {@code openMenu.remove()} on every selection, because a single-select menu has
 * nothing left to show once its one answer changed. Multi-select needs the opposite: choosing an
 * option should never close the panel, or a player ticking three sources back-to-back would have to
 * reopen the menu twice. Bending the single-select class to not-close-on-choose would leave a
 * {@code SelectionFloatMenu} built entirely around delivering one value out through {@code choose},
 * which the caller here has no use for -- rows tick their own boxes and tell the caller directly, the
 * same shape {@link necesse.gfx.forms.components.FormCheckBox#onClicked} already has everywhere else
 * in the game.
 *
 * <p>Right-click is the escape hatch back to "which one": it checks that row alone and clears every
 * other tick, without closing the panel. Right-click the same solo row again to re-check every
 * source. Left-click stays independent multi-select.
 *
 * <h2>What this is built from instead</h2>
 *
 * {@link FormFloatMenu} wraps an arbitrary {@link Form} as a floating panel and only removes itself on
 * a click that lands outside that form -- ticking a box inside it is a click that lands *inside*, so
 * the panel survives it for free, with no override needed. The panel {@code Form} keeps its own
 * default background and edge (unlike {@link CategoryTreeForm}, which turns both off), because here
 * there is exactly one panel per open menu rather than one nested per category, so the "boxed tile"
 * look this mod avoids elsewhere is correct here -- it is what tells a player where the floating panel
 * ends.
 *
 * <p>Getting that panel themed needs two separate things, not one. {@code form.inheritStyle(this.parent)},
 * done by {@code FormFloatMenu.setForm}, covers every {@link FormComponent}-driven draw inside the panel
 * (the checkbox rows' own text color, most notably) -- <b>provided the rows are added after that call,
 * not before</b>, since {@code inheritStyle} only fires when a component is added to a parent (see
 * {@code ComponentList.add}) and copies the parent's *current* style; a panel built row-first and
 * wrapped second would freeze every row on the ungrouped default the same way {@link CategoryTreeForm}
 * once did. It does <b>not</b> cover the panel's own background and edge, though: {@code Form.draw}
 * paints those from {@code this.background}, a {@link necesse.gfx.GameBackground} field, entirely
 * independent of {@code FormComponent.style}/{@code getInterfaceStyle} -- {@link ArcanePanel#of()}
 * reads the mod's own ambient {@code ArcaneStyles.current()} rather than anything inherited, which is
 * exactly why {@code setBackground(ArcanePanel.of())} needs its own explicit call here, the same one
 * {@code StorageTerminalContainerForm.styled(Form)} already makes for every tab root.
 *
 * <h2>Row width: fit the label, capped, and clipped rather than wrapped</h2>
 *
 * {@link FormCheckBox#setText} wraps onto a second line whenever a label does not fit its {@code
 * maxWidth} -- correct for prose, wrong for a station name, which reads worse split across two lines
 * than cut off on one. Rows are therefore built with {@code maxWidth = -1} (never wraps, always a
 * single line at its natural width), and the panel is sized to the *widest* row's measured text --
 * capped, so one absurdly long name cannot blow the panel past what looks like a dropdown -- up to
 * twice this button's own width. A {@link Form} clips its children to its own bounds by default
 * ({@code shouldLimitDrawArea}), so a label that still exceeds the cap draws past the panel's right
 * edge and is cut off there rather than wrapping, which is the "clipped after that" the cap implies.
 *
 * <p>The button itself is a plain {@link FormTextButton}, themed the same way every other button in
 * this mod is -- through {@code inheritStyle}, set up the instant this component is added to a styled
 * parent, same as {@link ArcaneDropdown}.
 */
public class ArcaneCheckDropdown extends FormTextButton {

   /** One row: label, current state, and where a click should go. */
   public interface Row {
      String label();

      boolean isChecked();

      void setChecked(boolean checked);
   }

   private static final int ROW_HEIGHT = 20;

   private static final int ROW_PADDING = 4;

   /** Room {@link FormCheckBox#setText} reserves for its tick icon and the icon-to-text gap. */
   private static final int ROW_ICON_OVERHEAD = 18;

   private static final FontOptions ROW_FONT = new FontOptions(12);

   private List<Row> rows = new ArrayList<>();

   private FormFloatMenu openMenu;

   public ArcaneCheckDropdown(GameMessage text, int x, int y, int width, FormInputSize size, ButtonColor color) {
      super(text.translate(), x, y, width, size, color);
      // FormButton.handleInputEvent only runs its click handler at all when handleClicksIfNoEventHandlers
      // is set or an onClicked listener exists -- neither is true here, since this button's own behavior
      // (open/close the menu) lives in the pressed() override below rather than in a listener. Without
      // this flag the button draws and hovers correctly but the click itself is never delivered.
      this.handleClicksIfNoEventHandlers = true;
   }

   /** Replaces the row set, closing any open panel -- called whenever the caller's own source list changes. */
   public void setRows(List<Row> rows) {
      this.rows = rows;
      this.closeMenu();
   }

   @Override
   protected void pressed(InputEvent event) {
      super.pressed(event);
      if (this.openMenu != null) {
         this.closeMenu();
      } else {
         this.openMenu(event);
      }
   }

   private void openMenu(InputEvent event) {
      if (this.rows.isEmpty()) {
         return;
      }

      int cap = this.getWidth() * 2;
      int widestLabel = 0;
      for (Row row : this.rows) {
         widestLabel = Math.max(widestLabel, FontManager.bit.getWidthCeil(row.label(), ROW_FONT));
      }

      int width = Math.min(cap, Math.max(this.getWidth(), widestLabel + ROW_ICON_OVERHEAD + ROW_PADDING * 2));

      // Built empty and wrapped first, rows added after: see the class doc for why the order matters.
      Form panel = new Form(width, this.rows.size() * ROW_HEIGHT + ROW_PADDING * 2);
      panel.setBackground(ArcanePanel.of());
      this.openMenu = new FormFloatMenu(this, panel);

      // Kept so a right-click can sync every sibling's drawn tick, not only the Row model behind it.
      List<FormCheckBox> ticks = new ArrayList<>(this.rows.size());
      for (int i = 0; i < this.rows.size(); i++) {
         Row row = this.rows.get(i);
         FormCheckBox tick = panel.addComponent(new FormCheckBox(row.label(), ROW_PADDING,
               ROW_PADDING + i * ROW_HEIGHT, row.isChecked()));
         // FormCheckBox ignores right-clicks unless asked; exclusive-select needs them.
         tick.acceptRightClicks = true;
         final int index = i;
         tick.onClicked(e -> {
            if (e.event != null && e.event.getID() == InputID.RIGHT_CLICK) {
               // FormCheckBox toggles before firing onClicked; undo that so exclusive-select owns state.
               e.preventDefault();
               this.exclusiveSelectOrRestore(index, ticks);
               return;
            }
            row.setChecked(e.from.checked);
         });
         ticks.add(tick);
      }

      this.getManager().openFloatMenu(this.openMenu, this, event);
   }

   /**
    * Right-click once: check {@code selectedIndex} alone. Right-click that same solo row again:
    * re-check every row. Uses the Row model (not the already-toggled checkbox) so the second click
    * still sees the exclusive state from the first.
    */
   private void exclusiveSelectOrRestore(int selectedIndex, List<FormCheckBox> ticks) {
      if (this.isAlreadyExclusive(selectedIndex)) {
         this.selectAll(ticks);
         return;
      }
      this.exclusiveSelect(selectedIndex, ticks);
   }

   /** True when {@code selectedIndex} is the only checked row in the model. */
   private boolean isAlreadyExclusive(int selectedIndex) {
      for (int i = 0; i < this.rows.size(); i++) {
         if (this.rows.get(i).isChecked() != (i == selectedIndex)) {
            return false;
         }
      }
      return true;
   }

   /** Checks every row -- model and drawn ticks together. */
   private void selectAll(List<FormCheckBox> ticks) {
      for (int i = 0; i < this.rows.size(); i++) {
         this.rows.get(i).setChecked(true);
         ticks.get(i).checked = true;
      }
   }

   /**
    * Checks {@code selectedIndex} alone and clears every other row -- model and drawn ticks together.
    */
   private void exclusiveSelect(int selectedIndex, List<FormCheckBox> ticks) {
      for (int i = 0; i < this.rows.size(); i++) {
         boolean on = i == selectedIndex;
         this.rows.get(i).setChecked(on);
         ticks.get(i).checked = on;
      }
   }

   private void closeMenu() {
      if (this.openMenu != null) {
         this.openMenu.remove();
         this.openMenu = null;
      }
   }
}
