package arcanestorage.container;

import java.awt.Rectangle;

import arcanestorage.objectentity.BusObjectEntity;
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
import necesse.gfx.forms.components.localComponents.FormLocalTextButton;
import necesse.gfx.forms.presets.ItemCategoriesFilterForm;
import necesse.gfx.forms.presets.containerComponent.ContainerForm;
import necesse.engine.gameLoop.tickManager.TickManager;
import necesse.entity.mobs.PlayerMob;
import necesse.engine.GameLog;
import necesse.gfx.GameColor;
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

   /** Height reserved at the bottom of the panel for the Apply button, kept clear of the scrolling list. */
   private static final int APPLY_STRIP = 32;

   public final ItemCategoriesFilterForm filterForm;

   /**
    * Why the bus has stopped, or empty when it has not.
    *
    * <p>Refreshed while the panel is open rather than fixed at construction, because the reason a bus stopped
    * is usually a rule the player is editing right now: they should see it clear as they fix it.
    */
   private final FormLabel stateLabel;

   /** Whether the panel holds edits the server has not been told about. */
   private boolean unapplied;

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

      // Reserved whether or not anything is wrong, so nothing below moves when a rule starts or stops
      // conflicting. Wrapped to the panel width because the reason names another device and its coordinates.
      this.stateLabel = new FormLabel("", new FontOptions(12), -1, 6, 0, WIDTH - 12);
      this.addComponent(flow.nextY(this.stateLabel, 4));

      // A label and a number, where the settlement panel puts a mode dropdown and a number. The dropdown is
      // deliberately absent: its four modes describe a container, and two of them cap a container's entire
      // item count, which for a network means "the network may hold 20 things in total" -- zero headroom in
      // any real network, so an import bus stops dead and an export bus treats everything as surplus. Both
      // were observed in game. A bus's number is per item, which is also what the per-item rows below mean,
      // so one reading covers the whole panel.
      int limitY = flow.next(28);
      this.addComponent(new FormLabel(
         Localization.translate("ui", "arcanestorage_buslimit"), new FontOptions(16), -1, 4, limitY + 6));

      final FormTextInput limitInput = this.addComponent(
         new FormTextInput(WIDTH / 2 + 2, limitY, FormInputSize.SIZE_24, WIDTH / 2 - 6, 7));
      limitInput.setRegexMatchFull("([0-9]+)?");
      limitInput.rightClickToClear = true;
      limitInput.placeHolder = new LocalMessage("ui", "arcanestorage_buslimithint");
      if (filter.maxAmount != Integer.MAX_VALUE) {
         limitInput.setText(String.valueOf(filter.maxAmount));
      }

      limitInput.onSubmit(e -> {
         int next = limitInput.getText().isEmpty() ? Integer.MAX_VALUE : parseOr(limitInput.getText());
         if (filter.maxAmount != next) {
            filter.maxAmount = next;
            this.edited();
         }
      });

      int searchY = flow.next(28);
      int contentY = flow.next();

      // The list stops short of the bottom edge to leave APPLY_STRIP clear. It has to be clear rather than
      // merely drawn over: FormContentBox.hitboxFullSize defaults to true, so the box claims the mouse
      // anywhere in its rectangle whether or not anything of its own is under the cursor.
      final FormContentBox content = this.addComponent(
         new FormContentBox(0, contentY, WIDTH, HEIGHT - contentY - APPLY_STRIP));

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
               BusContainerForm.this.edited();
            }

            @Override
            public void onItemLimitsChanged(Item item, ItemCategoriesFilter.ItemLimits limits) {
               BusContainerForm.this.edited();
            }

            @Override
            public void onCategoryChanged(ItemCategoriesFilter.ItemCategoryFilter category, boolean allowed) {
               BusContainerForm.this.edited();
            }

            @Override
            public void onCategoryLimitsChanged(ItemCategoriesFilter.ItemCategoryFilter category, int maxItems) {
               BusContainerForm.this.edited();
            }
         });

      content.addComponent(new FormLocalTextButton(
            "ui", "allowallbutton", 4, 0, WIDTH / 2 - 6, FormInputSize.SIZE_24, ButtonColor.BASE))
         .onClicked(e -> {
            filter.master.setAllowed(true);
            this.filterForm.updateAllButtons();
            this.edited();
         });
      content.addComponent(new FormLocalTextButton(
            "ui", "clearallbutton", WIDTH / 2 + 2, 0, WIDTH / 2 - 6, FormInputSize.SIZE_24, ButtonColor.BASE))
         .onClicked(e -> {
            filter.master.setAllowed(false);
            this.filterForm.updateAllButtons();
            this.edited();
         });

      // Apply sits in the reserved strip below the list, never inside it, and stays responsive whether or not
      // there is anything to send. A button that goes inactive when it has nothing to do is indistinguishable
      // from a broken one, and pressing this with no edits pending simply re-sends the set already in force.
      int applyY = HEIGHT - APPLY_STRIP + 4;
      FormLocalTextButton applyButton = this.addComponent(new FormLocalTextButton(
            "ui", "arcanestorage_apply", WIDTH / 2 - 60, applyY, 120, FormInputSize.SIZE_24,
            ButtonColor.GREEN));
      applyButton.onClicked(e -> this.apply());

      // Checked rather than trusted, because nothing headless can see this: forms are client-side, so a
      // control that draws correctly and receives nothing looks identical to a working one in every test we
      // can write. This is the assertion the first version of this panel needed and did not have -- it sat
      // inside the list's rectangle, drew perfectly, and went inert the moment the list was clicked, because
      // clicking a component raises its priority key and the event loop offers events in that order.
      int listBottom = HEIGHT - APPLY_STRIP;
      int buttonBottom = applyY + FormInputSize.SIZE_24.height;
      if (applyY < listBottom) {
         GameLog.warn.println("Arcane Storage: the apply button starts at y=" + applyY
               + " but the filter list runs to y=" + listBottom + "; the list claims the mouse over its whole"
               + " rectangle once clicked, so the button will draw normally and respond to nothing.");
      } else if (buttonBottom > HEIGHT) {
         GameLog.warn.println("Arcane Storage: the apply button ends at y=" + buttonBottom
               + " but the panel is " + HEIGHT + "px tall; it will be clipped.");
      }

      FormTextInput search = this.addComponent(
         new FormTextInput(4, searchY, FormInputSize.SIZE_24, WIDTH - 8, -1, 500));
      search.placeHolder = new LocalMessage("ui", "searchtip");
      search.onChange(e -> this.filterForm.setSearch(search.getText()));
   }

   /**
    * Keeps the stopped-reason line current while the panel is open.
    *
    * <p>The state arrives through the object entity's own sync, so a client's copy of the bus is
    * authoritative enough to read here -- and it changes without the panel doing anything, which is why this
    * is refreshed per frame rather than set once.
    */
   @Override
   public void draw(TickManager tickManager, PlayerMob perspective, Rectangle renderBox) {
      BusObjectEntity bus = this.container.bus;

      // A refusal outranks the device's state, because it answers what the player just did. The state is a
      // standing fact about the bus and will still be there once they have read the refusal.
      String message = "";
      if (this.container.refusal != null) {
         message = GameColor.RED.getColorCode() + this.container.refusal;
      } else if (bus != null && bus.isInactive()) {
         message = GameColor.RED.getColorCode() + bus.stateMessage();
      } else if (this.unapplied) {
         message = Localization.translate("ui", "arcanestorage_unapplied");
      }

      this.stateLabel.setText(message);
      super.draw(tickManager, perspective, renderBox);
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
   /**
    * Records that the player has changed something, without telling the server yet.
    *
    * <p>The panel used to send on every click, which had two consequences worth being rid of. A rule set was
    * judged one checkbox at a time, so a player midway through a legitimate change could be refused for a state
    * they were about to leave -- and a set that could only be valid as a whole could never be reached at all.
    * And on a bus that had stopped, each click restarted the work under a rule the player had not finished
    * writing.
    */
   private void edited() {
      this.unapplied = true;
      this.container.refusal = null;
   }

   /** Sends the whole rule set for the server to accept or refuse as one thing. */
   private void apply() {
      this.container.refusal = null;
      this.unapplied = false;
      this.container.setFilterAction.runAndSend(this.container.filter);
   }


}
