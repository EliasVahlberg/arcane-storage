package arcanestorage.container;

import java.awt.Rectangle;
import java.util.function.Consumer;

import necesse.engine.GameLog;
import necesse.engine.ItemCategoryExpandedSetting;
import necesse.engine.Settings;
import necesse.engine.localization.Localization;
import necesse.engine.localization.message.LocalMessage;
import necesse.engine.network.client.Client;
import necesse.gfx.forms.Form;
import necesse.gfx.forms.components.FormContentBox;
import necesse.gfx.forms.components.FormInputSize;
import necesse.gfx.forms.components.FormLabel;
import necesse.gfx.forms.components.FormTextInput;
import necesse.gfx.forms.components.localComponents.FormLocalTextButton;
import necesse.gfx.forms.presets.ItemCategoriesFilterForm;
import necesse.gfx.gameFont.FontOptions;
import necesse.gfx.ui.ButtonColor;
import necesse.inventory.item.Item;
import necesse.inventory.itemFilter.ItemCategoriesFilter;

/**
 * A bus's rule editor, built into whatever form asks for one.
 *
 * <p>Two surfaces edit a bus's rules: the panel on the bus itself, and the terminal's logistics tab. They
 * must not be two implementations. A rule editor that behaved differently in the two places would be a
 * lasting source of "it worked when I did it from the bus", and the validation each one has to obey is the
 * same validation.
 *
 * <p>Built into a caller's form rather than being a nested form, deliberately. A nested {@code Form} draws
 * its own panel, so the bus panel would gain a frame inside a frame it does not have today, and the shared
 * piece would be deciding how its host looks. This way the host owns its chrome and this owns the controls.
 *
 * <p>What the host keeps: the message line, and the plumbing that fills it. A refusal arrives by a different
 * route in each place -- the bus's own container action, or the terminal's -- so this reports only whether
 * there are edits waiting, through {@link #hasUnappliedEdits()}.
 */
public final class BusRulesEditor {

   /** Height reserved at the bottom for the Apply button, kept clear of the scrolling list. */
   private static final int APPLY_STRIP = 32;

   /** The amount row. Two lines at this font, because naming what the number means does not fit on one. */
   private static final int LIMIT_FONT = 14;

   private static final int LIMIT_ROW = 32;

   private static final int SEARCH_ROW = 28;

   public final ItemCategoriesFilterForm filterForm;

   private final ItemCategoriesFilter filter;

   /** Whether the player has changed something the server has not been told about. */
   private boolean unapplied;

   private BusRulesEditor(ItemCategoriesFilterForm filterForm, ItemCategoriesFilter filter) {
      this.filterForm = filterForm;
      this.filter = filter;
   }

   /**
    * Adds the amount row, the search box, the category tree and an Apply button into {@code host}.
    *
    * @param region where the editor may draw, in the host's coordinates
    * @param expandKey names the client-side setting remembering which categories are collapsed; sharing one
    *        key between the bus panel and the terminal is intentional, so a player's collapsed categories
    *        follow them rather than depending on which surface they opened
    * @param onApply given the edited filter when the player presses Apply
    */
   public static BusRulesEditor addTo(Form host, Client client, ItemCategoriesFilter filter, String limitKey,
         String expandKey, Rectangle region, Consumer<ItemCategoriesFilter> onApply) {
      int limitY = region.y;
      FormLabel limitLabel = host.addComponent(new FormLabel(
            Localization.translate("ui", limitKey), new FontOptions(LIMIT_FONT), -1, region.x + 4, limitY,
            region.width / 2 - 12));
      if (limitLabel.getHeight() > LIMIT_ROW) {
         GameLog.warn.println("Arcane Storage: the amount label needs " + limitLabel.getHeight()
               + "px but its row is " + LIMIT_ROW + "px; it will overlap what is below it.");
      }

      final FormTextInput limitInput = host.addComponent(new FormTextInput(
            region.x + region.width / 2 + 2, limitY, FormInputSize.SIZE_24, region.width / 2 - 6, 7));
      limitInput.setRegexMatchFull("([0-9]+)?");
      limitInput.rightClickToClear = true;
      limitInput.placeHolder = new LocalMessage("ui", "arcanestorage_buslimithint");
      if (filter.maxAmount != Integer.MAX_VALUE) {
         limitInput.setText(String.valueOf(filter.maxAmount));
      }

      int searchY = limitY + LIMIT_ROW;
      int contentY = searchY + SEARCH_ROW;

      // The list stops short of the bottom to leave the Apply strip clear. It has to be clear rather than
      // merely drawn over: FormContentBox.hitboxFullSize defaults to true, so the box claims the mouse
      // anywhere in its rectangle whether or not anything of its own is under the cursor -- and clicking a
      // component raises the priority key the event loop sorts on, so once the list has been touched it is
      // offered every event first. A button inside it draws perfectly and receives nothing.
      int listHeight = region.y + region.height - contentY - APPLY_STRIP;
      final FormContentBox content = host.addComponent(
            new FormContentBox(region.x, contentY, region.width, listHeight));

      ItemCategoryExpandedSetting expanded = Settings.getItemCategoryExpandedSetting(expandKey);
      final BusRulesEditor[] self = new BusRulesEditor[1];
      ItemCategoriesFilterForm filterForm = content.addComponent(
            new ItemCategoriesFilterForm(4, 28, filter, ItemCategoriesFilterForm.Mode.ALLOW_MAX_AMOUNT,
                  expanded, client.characterStats.items_obtained, true) {
               @Override
               public void onDimensionsChanged(int width, int height) {
                  content.setContentBox(new Rectangle(0, 0, Math.max(region.width, width), this.getY() + height));
               }

               @Override
               public void onItemsChanged(Item[] items, boolean allowed) {
                  self[0].edited();
               }

               @Override
               public void onItemLimitsChanged(Item item, ItemCategoriesFilter.ItemLimits limits) {
                  self[0].edited();
               }

               @Override
               public void onCategoryChanged(ItemCategoriesFilter.ItemCategoryFilter category, boolean allowed) {
                  self[0].edited();
               }

               @Override
               public void onCategoryLimitsChanged(ItemCategoriesFilter.ItemCategoryFilter category, int maxItems) {
                  self[0].edited();
               }
            });

      BusRulesEditor editor = new BusRulesEditor(filterForm, filter);
      self[0] = editor;

      limitInput.onSubmit(e -> {
         int next = limitInput.getText().isEmpty() ? Integer.MAX_VALUE : parseOr(limitInput.getText());
         if (filter.maxAmount != next) {
            filter.maxAmount = next;
            editor.edited();
         }
      });

      content.addComponent(new FormLocalTextButton(
            "ui", "allowallbutton", 4, 0, region.width / 2 - 6, FormInputSize.SIZE_24, ButtonColor.BASE))
         .onClicked(e -> {
            filter.master.setAllowed(true);
            filterForm.updateAllButtons();
            editor.edited();
         });
      content.addComponent(new FormLocalTextButton(
            "ui", "clearallbutton", region.width / 2 + 2, 0, region.width / 2 - 6, FormInputSize.SIZE_24,
            ButtonColor.BASE))
         .onClicked(e -> {
            filter.master.setAllowed(false);
            filterForm.updateAllButtons();
            editor.edited();
         });

      // Stays responsive whether or not there is anything to send. A button that goes inactive when it has
      // nothing to do draws in ButtonState.INACTIVE and refuses hover and click, which is indistinguishable
      // from being broken; pressing this with no edits pending re-sends the set already in force.
      int applyY = region.y + region.height - APPLY_STRIP + 4;
      host.addComponent(new FormLocalTextButton("ui", "arcanestorage_apply",
            region.x + region.width / 2 - 60, applyY, 120, FormInputSize.SIZE_24, ButtonColor.GREEN))
         .onClicked(e -> {
            editor.unapplied = false;
            onApply.accept(filter);
         });

      if (applyY < contentY + listHeight) {
         GameLog.warn.println("Arcane Storage: the apply button starts at y=" + applyY
               + " but the filter list runs to y=" + (contentY + listHeight) + "; the list claims the mouse over"
               + " its whole rectangle once clicked, so the button will draw normally and respond to nothing.");
      }

      FormTextInput search = host.addComponent(new FormTextInput(
            region.x + 4, searchY, FormInputSize.SIZE_24, region.width - 8, -1, 500));
      search.placeHolder = new LocalMessage("ui", "searchtip");
      search.onChange(e -> filterForm.setSearch(search.getText()));

      return editor;
   }

   /** The smallest region this can be built into without something overlapping something else. */
   public static int minimumHeight() {
      return LIMIT_ROW + SEARCH_ROW + FormInputSize.SIZE_24.height * 3 + APPLY_STRIP;
   }

   /**
    * Whether the player has edits the server has not been told about.
    *
    * <p>Edits are local until Apply, so a rule set is judged as one thing. Sending each checkbox as it was
    * clicked meant a player midway through a legitimate change could be refused for a state they were about
    * to leave, and a set that is only valid as a whole could never be reached at all.
    */
   public boolean hasUnappliedEdits() {
      return this.unapplied;
   }

   /** Called by the host when a rule set has been accepted, or when the editor is pointed at another bus. */
   public void markApplied() {
      this.unapplied = false;
   }

   public ItemCategoriesFilter getFilter() {
      return this.filter;
   }

   private void edited() {
      this.unapplied = true;
   }

   /** An unparseable number is treated as no limit, which is what clearing the field means. */
   private static int parseOr(String text) {
      try {
         return Integer.parseInt(text);
      } catch (NumberFormatException e) {
         return Integer.MAX_VALUE;
      }
   }
}
