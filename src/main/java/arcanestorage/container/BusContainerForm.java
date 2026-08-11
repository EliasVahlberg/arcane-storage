package arcanestorage.container;

import java.awt.Rectangle;

import necesse.engine.Settings;
import necesse.engine.ItemCategoryExpandedSetting;
import necesse.engine.localization.Localization;
import necesse.engine.localization.message.LocalMessage;
import necesse.engine.network.client.Client;
import necesse.gfx.forms.components.FormContentBox;
import necesse.gfx.forms.components.FormFlow;
import necesse.gfx.forms.components.FormInputSize;
import necesse.gfx.forms.components.FormLabel;
import necesse.gfx.forms.components.FormTextInput;
import necesse.gfx.forms.components.FormDropdownSelectionButton;
import necesse.gfx.forms.components.localComponents.FormLocalTextButton;
import necesse.gfx.forms.presets.ItemCategoriesFilterForm;
import necesse.gfx.forms.presets.containerComponent.ContainerForm;
import necesse.gfx.gameFont.FontOptions;
import necesse.gfx.ui.ButtonColor;
import necesse.inventory.item.Item;
import necesse.inventory.itemFilter.ItemCategoriesFilter;

/**
 * The rule panel for an import or export bus.
 *
 * <p><b>This is the game's own filter panel, not a new one.</b> It is the same
 * {@link ItemCategoriesFilterForm} a player already meets behind "configure storage" on a settlement
 * chest — same category tree, same tri-state ticks, same per-item number, same search box, same "allow
 * all" and "clear all" buttons. Reusing it is the reason the phase's acceptance criterion of being
 * legible without documentation is met: there is nothing new to learn, only a different device to point
 * it at. Building a bespoke editor would have been more code and a worse result.
 *
 * <p>The one sentence the header has to convey is what a number means here, since that is the only thing
 * the panel cannot say for itself: <b>a number is how much of that item the network should hold.</b> An
 * import bus fills up to it, an export bus drains down to it.
 */
public class BusContainerForm<T extends BusContainer> extends ContainerForm<T> {

   private static final int WIDTH = 340;
   private static final int HEIGHT = 420;

   public final ItemCategoriesFilterForm filterForm;

   public BusContainerForm(Client client, T container, String nameKey, String explanationKey) {
      super(client, WIDTH, HEIGHT, container);
      final ItemCategoriesFilter filter = container.filter;
      FormFlow flow = new FormFlow(5);

      FormLabel title = new FormLabel(
         Localization.translate("object", nameKey), new FontOptions(20), -1, 6, 0);
      this.addComponent(flow.nextY(title, 4));

      FormLabel explanation = new FormLabel(
         Localization.translate("ui", explanationKey), new FontOptions(12), -1, 6, 0);
      this.addComponent(flow.nextY(explanation, 6));

      // The limit-mode dropdown and its number, laid out as the settlement panel lays them out, so the
      // two read as the same control. Vanilla supplies the labels and the placeholder text.
      int limitY = flow.next(28);
      final FormDropdownSelectionButton<ItemCategoriesFilter.ItemLimitMode> limitMode = this.addComponent(
         new FormDropdownSelectionButton<>(4, limitY, FormInputSize.SIZE_24, ButtonColor.BASE, WIDTH / 2 - 6));
      for (ItemCategoriesFilter.ItemLimitMode value : ItemCategoriesFilter.ItemLimitMode.values()) {
         limitMode.options.add(value, value.displayName);
      }

      limitMode.setSelected(filter.limitMode, filter.limitMode.displayName);

      final FormTextInput limitInput = this.addComponent(
         new FormTextInput(WIDTH / 2 + 2, limitY, FormInputSize.SIZE_24, WIDTH / 2 - 6, 7));
      limitInput.setRegexMatchFull("([0-9]+)?");
      limitInput.rightClickToClear = true;
      limitInput.placeHolder = filter.limitMode.inputPlaceholder;
      if (filter.maxAmount != Integer.MAX_VALUE) {
         limitInput.setText(String.valueOf(filter.maxAmount));
      }

      limitInput.onSubmit(e -> {
         int next = limitInput.getText().isEmpty() ? Integer.MAX_VALUE : parseOr(limitInput.getText());
         if (filter.maxAmount != next) {
            filter.maxAmount = next;
            this.send();
         }
      });
      limitMode.onSelected(e -> {
         if (filter.limitMode != e.value) {
            filter.limitMode = e.value;
            limitInput.placeHolder = filter.limitMode.inputPlaceholder;
            this.send();
         }
      });

      int searchY = flow.next(28);
      int contentY = flow.next();
      final FormContentBox content = this.addComponent(
         new FormContentBox(0, contentY, WIDTH, HEIGHT - contentY - 4));

      // Expand state is a client-side setting keyed by name, exactly as the settlement panel does it, so
      // a player's collapsed categories stay collapsed between openings.
      ItemCategoryExpandedSetting expanded = Settings.getItemCategoryExpandedSetting("arcanestoragebus");
      this.filterForm = content.addComponent(
         new ItemCategoriesFilterForm(4, 28, filter, ItemCategoriesFilterForm.Mode.ALLOW_MAX_AMOUNT,
               expanded, client.characterStats.items_obtained, true) {
            @Override
            public void onDimensionsChanged(int width, int height) {
               content.setContentBox(new Rectangle(0, 0, Math.max(WIDTH, width), this.getY() + height));
            }

            @Override
            public void onItemsChanged(Item[] items, boolean allowed) {
               BusContainerForm.this.send();
            }

            @Override
            public void onItemLimitsChanged(Item item, ItemCategoriesFilter.ItemLimits limits) {
               BusContainerForm.this.send();
            }

            @Override
            public void onCategoryChanged(ItemCategoriesFilter.ItemCategoryFilter category, boolean allowed) {
               BusContainerForm.this.send();
            }

            @Override
            public void onCategoryLimitsChanged(ItemCategoriesFilter.ItemCategoryFilter category, int maxItems) {
               BusContainerForm.this.send();
            }
         });

      content.addComponent(new FormLocalTextButton(
            "ui", "allowallbutton", 4, 0, WIDTH / 2 - 6, FormInputSize.SIZE_24, ButtonColor.BASE))
         .onClicked(e -> {
            filter.master.setAllowed(true);
            this.filterForm.updateAllButtons();
            this.send();
         });
      content.addComponent(new FormLocalTextButton(
            "ui", "clearallbutton", WIDTH / 2 + 2, 0, WIDTH / 2 - 6, FormInputSize.SIZE_24, ButtonColor.BASE))
         .onClicked(e -> {
            filter.master.setAllowed(false);
            this.filterForm.updateAllButtons();
            this.send();
         });

      FormTextInput search = this.addComponent(
         new FormTextInput(4, searchY, FormInputSize.SIZE_24, WIDTH - 8, -1, 500));
      search.placeHolder = new LocalMessage("ui", "searchtip");
      search.onChange(e -> this.filterForm.setSearch(search.getText()));
   }

   /** An unparseable number is treated as no limit, which is what clearing the field means. */
   private static int parseOr(String text) {
      try {
         return Integer.parseInt(text);
      } catch (NumberFormatException e) {
         return Integer.MAX_VALUE;
      }
   }

   /** Pushes the edited filter to the server. See {@link BusContainer.SetFilterAction} for why in full. */
   private void send() {
      necesse.engine.GameLog.warn.println("[bus] form sending "
            + BusContainer.describeFilter(this.container.filter)
            + " sameObjectAsContainer=" + (this.filterForm.filter == this.container.filter));
      this.container.setFilterAction.runAndSend(this.container.filter);
   }

}
