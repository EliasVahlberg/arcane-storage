package arcanestorage.container;

import java.awt.Rectangle;
import java.util.function.Consumer;

import arcanestorage.objectentity.BusObjectEntity;
import arcanestorage.ui.ArcanePanel;
import necesse.engine.GameLog;
import necesse.engine.ItemCategoryExpandedSetting;
import necesse.engine.Settings;
import necesse.engine.localization.Localization;
import necesse.engine.localization.message.LocalMessage;
import necesse.engine.network.client.Client;
import necesse.gfx.forms.ComponentListContainer;
import necesse.gfx.forms.Form;
import necesse.gfx.forms.components.FormComponent;
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

   /** The name row, which is also this panel's title: one control rather than a heading and a field. */
   static final int NAME_ROW_HEIGHT = 34;

   /** The status line's font, and the height it occupies when it has nothing to say. */
   private static final int STATUS_FONT = 12;

   /** How far the status line sits below the name row, and its body below it. */
   private static final int STATUS_GAP = 4;

   /**
    * Who provides the scrollbar.
    *
    * <p>The bus's own panel is a window that can grow, so its filter list scrolls inside a fixed viewport and
    * the window grows only for the status line. The terminal's pane cannot grow -- it is one half of a tab --
    * so there the whole editor is laid out at its natural height and the host scrolls all of it.
    *
    * <p>Not two scrollbars in the terminal's case, which is why this is a choice rather than both: a scrolling
    * list inside a scrolling pane is the nested hit-testing hazard that made the Apply button inert once
    * already, and it puts two bars a few pixels apart for the player to pick between.
    */
   public enum Scroll {
      /** The filter list scrolls within the region given. */
      OWN_LIST,

      /** Nothing scrolls here; the editor fills its natural height and the host scrolls it. */
      HOST_SCROLLS_ALL
   }

   public final ItemCategoriesFilterForm filterForm;

   private final ItemCategoriesFilter filter;

   private final FormTextInput nameInput;

   /**
    * The status line: why the bus has stopped, what was refused, or that there are edits waiting.
    *
    * <p>Owned here rather than by each host because both hosts want it in the same place -- directly under the
    * name, above the amount -- and because its height is what everything below it is positioned from. A host
    * that owned it would be deciding this layout from outside.
    */
   private final FormLabel statusLabel;

   /** The status label's own options, recoloured in place. See where it is built for why. */
   private final FontOptions statusFont;

   /**
    * Everything below the status line, as one invisible form.
    *
    * <p>{@code drawBase = false}, so it draws no panel of its own and the host keeps its chrome. The point of
    * the grouping is that a status line growing by a line moves the amount row, the search box, the list and
    * the Apply button by one {@code setPosition} call instead of five, and cannot move four of them and forget
    * the fifth.
    */
   private final Form body;

   /** Where the body sits when the status line is empty, and the width the status wraps at. */
   private final int bodyBaseY;

   private final int statusWrapWidth;

   /** The status height the body was last positioned for, so a reflow happens only when it changes. */
   private int shownStatusHeight = -1;

   /** Set when the layout has changed height, for a host that needs to resize around it. */
   private boolean heightChanged;

   /** Told when the natural height changes, so a host can resize or rescroll. Null when nobody cares. */
   private Runnable onLayoutChanged;

   /** Kept so it can follow the tree down in the host-scrolled case. */
   private FormLocalTextButton applyButton;

   /** The name as the server last reported it, so a rename by anything else is picked up and typing is not. */
   private String shownName;

   /** Whether the player has changed something the server has not been told about. */
   private boolean unapplied;

   private BusRulesEditor(ItemCategoriesFilterForm filterForm, ItemCategoriesFilter filter,
         FormTextInput nameInput, FormLabel statusLabel, FontOptions statusFont, Form body, int bodyBaseY,
         int statusWrapWidth,
         String name) {
      this.filterForm = filterForm;
      this.filter = filter;
      this.nameInput = nameInput;
      this.statusLabel = statusLabel;
      this.statusFont = statusFont;
      this.body = body;
      this.bodyBaseY = bodyBaseY;
      this.statusWrapWidth = statusWrapWidth;
      this.shownName = name;
   }

   /**
    * Adds the name row, the status line, the amount row, the search box, the category tree and an Apply
    * button into {@code host}.
    *
    * <p>The order is deliberate and is the one a player reads top to bottom: which device this is, whether
    * anything is wrong with it, then the rules themselves. The status line was previously a fixed block of
    * reserved lines owned by each host, sized against the longest message any device could produce -- which
    * meant every panel paid for the worst case at all times, and the reservation still went wrong twice as
    * messages grew. It now takes the height it needs and the layout follows it.
    *
    * @param region where the editor may draw, in the host's coordinates. In {@link Scroll#HOST_SCROLLS_ALL}
    *        its height is ignored: the editor lays out at its natural height and the host scrolls it
    * @param expandKey names the client-side setting remembering which categories are collapsed; sharing one
    *        key between the bus panel and the terminal is intentional, so a player's collapsed categories
    *        follow them rather than depending on which surface they opened
    * @param name what this bus is currently called, shown in the name row
    * @param onRename given the new name when the player submits the name row
    * @param onApply given the edited filter when the player presses Apply
    * @param onLayoutChanged called when the editor's natural height changes, for a host that has to resize or
    *        rescroll around it. May be null
    */
   public static BusRulesEditor addTo(ComponentListContainer<FormComponent> host, Client client, ItemCategoriesFilter filter, String limitKey,
         String expandKey, Rectangle region, String name, Consumer<String> onRename,
         Consumer<ItemCategoriesFilter> onApply, Scroll scroll, Runnable onLayoutChanged) {
      // The name row doubles as the panel's title. A device is addressed by coordinates and a player has no
      // way to relate coordinates to the bus in front of them -- nothing in the game shows a tile position --
      // so the name is the only handle they have, and it is worth the top of the panel.
      final FormTextInput nameInput = host.addComponent(new FormTextInput(
            region.x + 4, region.y, FormInputSize.SIZE_24, region.width - 8, BusObjectEntity.MAX_NAME_LENGTH));
      nameInput.setText(name);
      nameInput.onSubmit(e -> onRename.accept(nameInput.getText()));

      // Directly under the name, and empty until there is something to say. No height is reserved for it:
      // when it is empty it occupies nothing and the amount row sits right below the name.
      int statusY = region.y + NAME_ROW_HEIGHT;
      int statusWrapWidth = region.width - 12;
      // The instance is kept rather than the label's own copy, because a status line's colour changes with what it
      // says and FormLabel has no setter for it. Prefixing a colour code instead does not work: a label renders one
      // literally, paragraph mark and all -- codes are chat and tooltip syntax.
      FontOptions statusFont = new FontOptions(STATUS_FONT);
      FormLabel statusLabel = host.addComponent(new FormLabel(
            "", statusFont, -1, region.x + 6, statusY, statusWrapWidth));

      int bodyBaseY = statusY + STATUS_GAP;
      final Form body = host.addComponent(new Form(region.width, Math.max(1, region.height - (bodyBaseY - region.y))));
      body.setBackground(ArcanePanel.of());
      body.drawBase = false;
      body.setPosition(region.x, bodyBaseY);

      FormLabel limitLabel = body.addComponent(new FormLabel(
            Localization.translate("ui", limitKey), new FontOptions(LIMIT_FONT), -1, 4, 0,
            region.width / 2 - 12));
      if (limitLabel.getHeight() > LIMIT_ROW) {
         GameLog.warn.println("Arcane Storage: the amount label needs " + limitLabel.getHeight()
               + "px but its row is " + LIMIT_ROW + "px; it will overlap what is below it.");
      }

      final FormTextInput limitInput = body.addComponent(new FormTextInput(
            region.width / 2 + 2, 0, FormInputSize.SIZE_24, region.width / 2 - 6, 7));
      limitInput.setRegexMatchFull("([0-9]+)?");
      limitInput.rightClickToClear = true;
      limitInput.placeHolder = new LocalMessage("ui", "arcanestorage_buslimithint");
      if (filter.maxAmount != Integer.MAX_VALUE) {
         limitInput.setText(String.valueOf(filter.maxAmount));
      }

      int searchY = LIMIT_ROW;
      int contentY = searchY + SEARCH_ROW;

      // The list stops short of the bottom to leave the Apply strip clear. It has to be clear rather than
      // merely drawn over: FormContentBox.hitboxFullSize defaults to true, so the box claims the mouse
      // anywhere in its rectangle whether or not anything of its own is under the cursor -- and clicking a
      // component raises the priority key the event loop sorts on, so once the list has been touched it is
      // offered every event first. A button inside it draws perfectly and receives nothing.
      int listHeight = body.getHeight() - contentY - APPLY_STRIP;
      final FormContentBox content = scroll == Scroll.OWN_LIST
            ? body.addComponent(new FormContentBox(0, contentY, region.width, listHeight))
            : null;

      // Where the tree and the two all-or-nothing buttons go: inside the scrolling box when there is one, and
      // straight into the body when the host is scrolling everything instead.
      final ComponentListContainer<FormComponent> treeHost = content == null ? body : content;
      final int treeBaseY = content == null ? contentY : 0;

      ItemCategoryExpandedSetting expanded = Settings.getItemCategoryExpandedSetting(expandKey);
      final BusRulesEditor[] self = new BusRulesEditor[1];
      ItemCategoriesFilterForm filterForm = treeHost.addComponent(
            new ItemCategoriesFilterForm(4, treeBaseY + 28, filter, ItemCategoriesFilterForm.Mode.ALLOW_MAX_AMOUNT,
                  expanded, client.characterStats.items_obtained, true) {
               @Override
               public void onDimensionsChanged(int width, int height) {
                  if (content != null) {
                     content.setContentBox(new Rectangle(0, 0, Math.max(region.width, width), this.getY() + height));
                  } else if (self[0] != null) {
                     // Nothing clips the tree here, so the editor itself has to grow to contain it and the
                     // host has to be told, or the Apply button below ends up unreachable.
                     self[0].relayoutForTreeHeight(this.getY() + height);
                  }
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

      BusRulesEditor editor = new BusRulesEditor(filterForm, filter, nameInput, statusLabel, statusFont, body,
            bodyBaseY,
            statusWrapWidth, name);
      self[0] = editor;
      editor.onLayoutChanged = onLayoutChanged;

      limitInput.onSubmit(e -> {
         int next = limitInput.getText().isEmpty() ? Integer.MAX_VALUE : parseOr(limitInput.getText());
         if (filter.maxAmount != next) {
            filter.maxAmount = next;
            editor.edited();
         }
      });

      treeHost.addComponent(new FormLocalTextButton(
            "ui", "allowallbutton", 4, treeBaseY, region.width / 2 - 6, FormInputSize.SIZE_24, ButtonColor.BASE))
         .onClicked(e -> {
            filter.master.setAllowed(true);
            filterForm.updateAllButtons();
            editor.edited();
         });
      treeHost.addComponent(new FormLocalTextButton(
            "ui", "clearallbutton", region.width / 2 + 2, treeBaseY, region.width / 2 - 6, FormInputSize.SIZE_24,
            ButtonColor.BASE))
         .onClicked(e -> {
            filter.master.setAllowed(false);
            filterForm.updateAllButtons();
            editor.edited();
         });

      // Stays responsive whether or not there is anything to send. A button that goes inactive when it has
      // nothing to do draws in ButtonState.INACTIVE and refuses hover and click, which is indistinguishable
      // from being broken; pressing this with no edits pending re-sends the set already in force.
      int applyY = body.getHeight() - APPLY_STRIP + 4;
      editor.applyButton = body.addComponent(new FormLocalTextButton("ui", "arcanestorage_apply",
            region.width / 2 - 60, applyY, 120, FormInputSize.SIZE_24, ButtonColor.GREEN));
      editor.applyButton.onClicked(e -> {
         editor.unapplied = false;
         onApply.accept(filter);
      });

      if (content != null && applyY < contentY + listHeight) {
         GameLog.warn.println("Arcane Storage: the apply button starts at y=" + applyY
               + " but the filter list runs to y=" + (contentY + listHeight) + "; the list claims the mouse over"
               + " its whole rectangle once clicked, so the button will draw normally and respond to nothing.");
      }

      FormTextInput search = body.addComponent(new FormTextInput(
            4, searchY, FormInputSize.SIZE_24, region.width - 8, -1, 500));
      search.placeHolder = new LocalMessage("ui", "searchtip");
      search.onChange(e -> filterForm.setSearch(search.getText()));

      // The first measurement, taken here rather than waited for: onDimensionsChanged fires only when the tree
      // changes size, and the one during construction happened before this object existed to hear it.
      if (scroll == Scroll.HOST_SCROLLS_ALL) {
         editor.relayoutForTreeHeight(filterForm.getY() + filterForm.getHeight());
      }

      return editor;
   }

   /**
    * Sets the status line, and moves everything below it if its height changed.
    *
    * <p>Called every frame by both hosts, so the guard matters: repositioning the body and asking the host to
    * resize on every frame would fight the scrollbar and re-anchor a window continuously.
    */
   public void setStatus(String message) {
      this.setStatus(message, false);
   }

   /** {@code problem} decides the colour: a refusal and a stopped device are red, an unapplied edit is not. */
   public void setStatus(String message, boolean problem) {
      this.statusFont.color(problem
            ? arcanestorage.ui.ArcaneText.errorColor(this.statusLabel)
            : arcanestorage.ui.ArcaneText.body(this.statusLabel));
      this.statusLabel.setText(message == null ? "" : message, this.statusWrapWidth);
      int height = message == null || message.isEmpty() ? 0 : this.statusLabel.getHeight() + STATUS_GAP;
      if (height == this.shownStatusHeight) {
         return;
      }

      this.shownStatusHeight = height;
      this.body.setPosition(this.body.getX(), this.bodyBaseY + height);
      this.heightChanged = true;
      if (this.onLayoutChanged != null) {
         this.onLayoutChanged.run();
      }
   }

   /** The height this editor currently occupies, measured from the top of its name row. */
   public int getNaturalHeight() {
      return NAME_ROW_HEIGHT + Math.max(0, this.shownStatusHeight) + STATUS_GAP + this.body.getHeight();
   }

   /** Whether the height changed since this was last asked, so a host resizes only when it has to. */
   public boolean consumeHeightChanged() {
      boolean changed = this.heightChanged;
      this.heightChanged = false;
      return changed;
   }

   /**
    * Grows the body to contain the whole category tree, for the host-scrolled case where nothing clips it.
    *
    * <p>The Apply button sits below the tree rather than over it, so it has to move with it.
    */
   private void relayoutForTreeHeight(int treeBottom) {
      // treeBottom is already body-local and so already past the amount and search rows -- adding contentY
      // again here left the Apply button a row and a half below the tree with dead space between them.
      int height = treeBottom + APPLY_STRIP;
      if (height == this.body.getHeight()) {
         return;
      }

      this.body.setHeight(height);
      if (this.applyButton != null) {
         this.applyButton.setY(height - APPLY_STRIP + 4);
      }

      this.heightChanged = true;
      if (this.onLayoutChanged != null) {
         this.onLayoutChanged.run();
      }
   }

   /**
    * The smallest region this can be built into without something overlapping something else.
    *
    * <p>The status line is not counted: it takes no space until there is something wrong, and when there is,
    * the layout grows rather than the controls being squeezed.
    */
   public static int minimumHeight() {
      return NAME_ROW_HEIGHT + STATUS_GAP + LIMIT_ROW + SEARCH_ROW + FormInputSize.SIZE_24.height * 3
            + APPLY_STRIP;
   }

   /**
    * Takes a name the server has reported, if it is not the one already shown.
    *
    * <p>Guarded rather than set every frame, because this is the box the player types into. Typing does not
    * change the name until it is submitted, so the guard holds for as long as they are editing; a rename by
    * somebody else lands immediately, which is the right outcome for the rarer case.
    */
   public void refreshName(String name) {
      if (name != null && !name.equals(this.shownName)) {
         this.shownName = name;
         this.nameInput.setText(name);
      }
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
