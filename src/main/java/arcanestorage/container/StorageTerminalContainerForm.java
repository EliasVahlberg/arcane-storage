package arcanestorage.container;

import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import necesse.engine.gameLoop.tickManager.TickManager;
import necesse.engine.input.Control;
import necesse.engine.input.InputEvent;
import necesse.engine.input.InputID;
import necesse.engine.achievements.Achievement;
import necesse.engine.util.GameMath;
import necesse.gfx.gameTooltips.GameTooltipManager;
import necesse.gfx.gameTooltips.GameTooltips;
import necesse.gfx.gameTooltips.TooltipLocation;
import necesse.inventory.container.slots.ContainerSlot;
import necesse.engine.localization.Localization;
import necesse.engine.localization.message.GameMessage;
import necesse.engine.localization.message.LocalMessage;
import necesse.engine.network.client.Client;
import necesse.engine.network.packet.PacketContainerAction;
import necesse.engine.util.GameBlackboard;
import necesse.engine.window.GameWindow;
import necesse.entity.mobs.PlayerMob;
import necesse.gfx.forms.ContainerComponent;
import necesse.gfx.forms.Form;
import necesse.gfx.forms.components.FormFlow;
import necesse.gfx.forms.components.FormInputSize;
import necesse.gfx.forms.components.FormContentIconButton;
import necesse.gfx.forms.components.FormDropdownSelectionButton;
import necesse.gfx.forms.components.FormLabel;
import java.awt.Color;
import necesse.gfx.forms.components.FormProgressBarText;
import necesse.gfx.forms.components.FormTextInput;
import necesse.gfx.forms.components.lists.FormItemList;
import necesse.gfx.forms.components.localComponents.FormLocalLabel;
import necesse.gfx.forms.components.localComponents.FormLocalTextButton;
import necesse.gfx.forms.presets.containerComponent.ContainerFormSwitcher;
import necesse.engine.Settings;
import necesse.gfx.ui.ButtonColor;
import necesse.gfx.gameFont.FontOptions;
import necesse.inventory.InventoryItem;
import necesse.inventory.container.Container;
import necesse.inventory.container.ContainerAction;
import necesse.inventory.item.ItemCategory;
import necesse.inventory.item.ItemSearchTester;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import necesse.engine.GameLog;
import necesse.engine.GlobalData;
import necesse.engine.registries.RecipeTechRegistry;
import necesse.gfx.forms.components.FormContainerCraftingListContentBox;
import necesse.gfx.forms.components.localComponents.FormLocalCheckBox;
import necesse.gfx.forms.presets.TabbedFormPreset;
import necesse.inventory.container.ContainerRecipe;
import necesse.inventory.recipe.RecipeFilter;
import arcanestorage.ArcaneStorage;

/**
 * Client-side UI for the Storage Terminal.
 *
 * <p>Draws the network as one deduplicated grid of items rather than as the underlying
 * slots. {@link FormItemList} is the right primitive because it renders
 * {@code InventoryItem}s, not {@code ContainerSlot}s, which is exactly what an aggregated
 * view is: 60 iron spread over three units is one entry of 60 and has no single slot.
 *
 * <p>Clicking does nothing yet — withdraw and deposit are step 4.
 */
public class StorageTerminalContainerForm<T extends StorageTerminalContainer> extends ContainerFormSwitcher<T> {

   /**
    * Orderings offered for the pooled list.
    *
    * <p>{@link #GROUP} is the engine's own: {@code InventoryItem} is {@code Comparable} and
    * {@code Inventory.sortItems} simply calls {@code Collections.sort}, so sorting by natural
    * order puts the network in the same order the player's own inventory-sort button produces.
    * That is the semantic grouping asked for, and it costs nothing to match rather than invent.
    *
    * <p>Sorting is safe to do purely in the view for the same reason filtering is: a withdrawal
    * names an item, never a position, so reordering cannot misdirect a click.
    */
   private enum SortMode {
      GROUP("arcanestorage_sort_group"),
      NAME("arcanestorage_sort_name"),
      AMOUNT("arcanestorage_sort_amount");

      final String localeKey;

      SortMode(String localeKey) {
         this.localeKey = localeKey;
      }

      SortMode next() {
         return values()[(this.ordinal() + 1) % values().length];
      }

      Comparator<InventoryItem> comparator() {
         switch (this) {
            case NAME:
               return Comparator.comparing(item -> item.getItemDisplayName().toLowerCase());
            case AMOUNT:
               // Most numerous first, then the engine's order so equal amounts are not arbitrary.
               return Comparator.<InventoryItem>comparingInt(InventoryItem::getAmount).reversed().thenComparing(item -> item);
            case GROUP:
            default:
               return Comparator.naturalOrder();
         }
      }
   }

   private static final int CELL_SIZE = 36;

   /**
    * Sized against the game's own item browser rather than against a chest.
    *
    * <p>The creative menu is 684x264 and is the widest interface Necesse ships -- which makes it
    * the honest upper bound for how much room a form may take, and the right comparison for this
    * one, because both exist to browse an item set far larger than a container's. Eighteen columns
    * put the form within thirty pixels of that width. The previous ten columns were a chest's
    * layout, and a chest holds forty stacks while a network holds up to 2560.
    */
   private static final int COLUMNS = 18;

   private static final int ROWS = 8;

   /**
    * Height the grid loses to its own scroll buttons.
    *
    * <p>{@code FormGeneralGridList} draws a button strip and computes its scroll limit against
    * {@code height - 32}, so a grid given exactly {@code ROWS * CELL_SIZE} shows one row fewer
    * than it was asked for. The old six-row grid was really showing five, which is part of why
    * the interface felt cramped: the shortfall was worse than the constants said.
    */
   private static final int GRID_SCROLL_BUTTONS = 32;

   private static final int PADDING = 4;
   private static final int SEARCH_WIDTH = 220;

   private static final int CAPACITY_BAR_WIDTH = 180;

   /**
    * Fill colours for the capacity bar, in quarters, where fuller is worse.
    *
    * <p>Four steps rather than the interface style's three text colours, because the style has no
    * fourth and the point of the fill is a glance-readable ramp. Literal colours are a deliberate
    * exception to preferring style colours: these are a bar, not text, and they have to read as a
    * ramp against each other rather than match the theme's text palette.
    */
   private static final Color CAPACITY_EMPTY = new Color(96, 186, 96);
   private static final Color CAPACITY_FILLING = new Color(214, 202, 88);
   private static final Color CAPACITY_HIGH = new Color(224, 148, 62);
   private static final Color CAPACITY_FULL = new Color(206, 78, 70);

   private static Color capacityFillColor(float used) {
      if (used < 0.25F) {
         return CAPACITY_EMPTY;
      } else if (used < 0.5F) {
         return CAPACITY_FILLING;
      } else {
         return used < 0.75F ? CAPACITY_HIGH : CAPACITY_FULL;
      }
   }

   private static final int FORM_WIDTH = PADDING * 2 + COLUMNS * CELL_SIZE;

   /**
    * Height shared by every tab.
    *
    * <p>Tabs cannot size themselves: {@code TabbedFormPreset} draws one panel at the size it was
    * constructed with and gives each tab a content form with {@code drawBase = false}, so a tab that
    * wanted a different height would either be clipped or leave the panel empty below it. The value
    * is therefore derived from the storage layout, which is the taller of the two, and the crafting
    * tab fills whatever is left.
    *
    * <p>This duplicates what the storage tab's {@code FormFlow} computes, so the two can drift. That
    * is checked at construction rather than trusted -- see the warning below the layout.
    */
   private static final int FORM_HEIGHT = PADDING
         + FormInputSize.SIZE_24.height + PADDING
         + FormInputSize.SIZE_20.height + PADDING
         + ROWS * CELL_SIZE + GRID_SCROLL_BUTTONS
         + PADDING
         + FormInputSize.SIZE_24.height + PADDING
         + PADDING;

   /**
    * How deep the category menu goes before it stops offering submenus.
    *
    * <p>The tree is deeper than it is useful: {@code objects > furniture > chairs} is a helpful
    * distinction, and the levels below that mostly separate wood types, which the search box
    * answers better than a menu can. Three levels keeps the menu navigable and still reaches
    * every leaf through the "everything in here" entry at each level.
    */
   private static final int CATEGORY_MENU_DEPTH = 3;

   public final Form mainForm;
   public final FormItemList itemList;
   public final FormTextInput searchInput;
   public final TabbedFormPreset tabs;
   public final Form craftingForm;
   public final FormProgressBarText capacityBar;
   public final FormLabel summaryLabel;
   public FormContentIconButton sortButton;

   /** Picks a category to filter by. Built from the game's own tree, so mods appear in it too. */
   public FormDropdownSelectionButton<ItemCategory> categoryButton;

   /**
    * The live search filter, rebuilt whenever the query changes.
    *
    * <p>Held as a field rather than constructed per item, because
    * {@link ItemSearchTester#constructSearchTester} parses the query — splitting on {@code |}
    * and stripping {@code @} — and doing that per item would repeat the parse for every entry
    * in the network on every rebuild.
    *
    * <p>An empty query yields a tester that matches everything, so there is no special case
    * for "not searching".
    */
   private ItemSearchTester searchTester = ItemSearchTester.constructSearchTester("");

   /**
    * The chosen category, or {@code null} for every item.
    *
    * <p>Held as an {@link ItemCategory} rather than as a name because the filter is then a
    * structural test — walk an item's category chain and look for this one — instead of a string
    * comparison that would break in any language but English.
    */
   private ItemCategory categoryFilter;

   /**
    * The current ordering. Not persisted anywhere: it resets when the terminal is reopened,
    * which keeps it out of config and save data until there is evidence a player misses it.
    */
   private SortMode sortMode = SortMode.GROUP;

   /**
    * The network's contents as of this frame.
    *
    * <p>Aggregation walks every slot in the network, and the draw path needs the result three
    * times over -- to detect a change, to rebuild the grid, and to fill it. Reading it once per
    * frame keeps a 64-unit network from paying for that three times at 60fps.
    */
   private List<InventoryItem> aggregated;

   /**
    * Signature of the aggregated list the grid was last built from. The list only populates
    * when empty, so it has to be rebuilt deliberately — but rebuilding every frame would
    * reset the player's scroll position, so it is rebuilt only when the contents change.
    */
   private long shownSignature = Long.MIN_VALUE;

   public StorageTerminalContainerForm(Client client, T container) {
      super(client, container);

      // One terminal with two tabs rather than Magic Storage's two blocks. The tab strip is
      // vanilla's own -- the creative menu is built from this preset -- so the tabs draw above the
      // panel, in the game's shape, and the earlier plan of reserving a strip inside the form for
      // them turned out to be unnecessary: FormTabContentComponent positions itself at
      // form.getY() - offset, outside the panel entirely.
      this.tabs = this.addComponent(
            new TabbedFormPreset(0, TabbedFormPreset.TabStyle.Fill, FORM_WIDTH, FORM_HEIGHT));
      this.mainForm = this.tabs.addLocalizedTab(new LocalMessage("ui", "arcanestorage_tab_storage"), null);

      FormFlow flow = new FormFlow(PADDING);
      // The header is one row: title on the left, search on the right. Its height is driven by
      // the search input rather than the font, since the input is the taller of the two.
      int headerHeight = FormInputSize.SIZE_24.height;
      int headerY = flow.next(headerHeight + PADDING);
      this.mainForm
         .addComponent(
            new FormLocalLabel(
               container.terminal.getInventoryName(),
               new FontOptions(20),
               -1,
               PADDING,
               headerY + (headerHeight - 20) / 2,
               this.mainForm.getWidth() - PADDING * 3 - SEARCH_WIDTH
            )
         );

      // Reuses the game's own "searchtip" placeholder and its SIZE_24 input, so the box looks
      // and reads like the search in the crafting station and creative menu.
      this.searchInput = this.mainForm
         .addComponent(
            new FormTextInput(
               this.mainForm.getWidth() - PADDING - SEARCH_WIDTH, headerY, FormInputSize.SIZE_24, SEARCH_WIDTH, -1, 100
            )
         );
      this.searchInput.placeHolder = new LocalMessage("ui", "searchtip");
      this.searchInput.onChange(event -> this.setSearch(this.searchInput.getText()));

      // The category picker gets its own row rather than a place on the crowded control row.
      // It is a dropdown and not the row of icon buttons this kind of UI usually has, and that is
      // a deliberate consequence of using Necesse's taxonomy instead of Terraria's: the game has
      // eight top-level categories and over a hundred in total, so any fixed set of icon buttons
      // would have to invent buckets and then decide, wrongly, which real category belongs in
      // which. The dropdown carries the game's own names and its own nesting, gains any category a
      // mod adds for free, and needs no art.
      int categoryHeight = FormInputSize.SIZE_20.height;
      int categoryY = flow.next(categoryHeight + PADDING);
      this.categoryButton = this.mainForm
         .addComponent(
            new FormDropdownSelectionButton<>(PADDING, categoryY, FormInputSize.SIZE_20, ButtonColor.BASE, 150)
         );
      this.categoryButton.setSelected(null, new LocalMessage("ui", "arcanestorage_category_all"));
      this.categoryButton.options.add(null, new LocalMessage("ui", "arcanestorage_category_all"));
      addCategoryOptions(this.categoryButton.options, ItemCategory.masterCategory, 1);
      this.categoryButton.onSelected(event -> {
         this.categoryFilter = event.value;
         this.refreshList();
      });

      // Right-aligned on the category row, which is otherwise empty across most of an 18-column
      // form. It answers a question the interface could not previously answer: search and category
      // both hide things, and without a count there is no way to tell "the network has none" from
      // "the filter removed them all". Set from inside addAllItems, so it counts the list that was
      // actually built rather than re-deriving the filter and risking the two disagreeing.
      this.summaryLabel = this.mainForm
         .addComponent(new FormLabel("", new FontOptions(12), 1,
               this.mainForm.getWidth() - PADDING, categoryY + 4));

      this.itemList = this.mainForm
         .addComponent(
            new FormItemList(
                  PADDING,
                  flow.next(ROWS * CELL_SIZE + GRID_SCROLL_BUTTONS),
                  COLUMNS * CELL_SIZE,
                  ROWS * CELL_SIZE + GRID_SCROLL_BUTTONS,
                  FormItemList.UpdateMode.WAIT_FULl) {
               @Override
               public void addAllItems(List<InventoryItem> list) {
                  // Filtering here rather than in the container keeps search a pure view
                  // concern: the client already holds every slot, so there is nothing to ask
                  // the server for and no packet to wait on while typing.
                  //
                  // It is also safe against the withdraw path by construction. A withdrawal
                  // sends the item and an amount, never a network slot index, and the server
                  // re-resolves it against its own units -- so a filtered view cannot make a
                  // click land on the wrong item.
                  GameBlackboard blackboard = new GameBlackboard();
                  for (InventoryItem item : StorageTerminalContainerForm.this.aggregated) {
                     if (StorageTerminalContainerForm.this.matchesCategory(item)
                           && StorageTerminalContainerForm.this.searchTester.matches(item, client.getPlayer(), blackboard)) {
                        list.add(item);
                     }
                  }

                  list.sort(StorageTerminalContainerForm.this.sortMode.comparator());
                  StorageTerminalContainerForm.this.updateSummary(list);
               }

               @Override
               public void onItemClicked(InventoryItem item, InputEvent event) {
                  // Right clicks reach here as well as left: FormGeneralList dispatches any
                  // isMouseClickEvent to the element under the mouse, so the button has to be
                  // read rather than assumed.
                  boolean rightClick = event.getID() == InputID.RIGHT_CLICK;

                  // Holding something means the click is an insert, whatever it landed on. That is
                  // the inventory convention -- clicking a slot while holding a stack puts it down
                  // -- and it means a player never has to find empty space to deposit into.
                  if (StorageTerminalContainerForm.this.isHoldingItem()) {
                     container.depositCursorAction.runAndSend(rightClick ? 1 : -1);
                     event.use();
                     return;
                  }

                  if (rightClick) {
                     // Half a stack, not half the network's supply. The cursor cannot hold more
                     // than one stack, so "half" is measured against what one click could pick up.
                     // A non-stackable item has a stack of one, which makes this a whole item and
                     // left and right identical -- as they are on any vanilla slot.
                     int oneStack = Math.min(item.getAmount(), item.item.getStackSize());
                     container.withdrawAction.runAndSend(item, Math.max(1, oneStack / 2), true);
                     event.use();
                     return;
                  }

                  // Plain click picks up onto the cursor, INV_QUICK_MOVE (shift by default)
                  // transfers into the inventory.
                  boolean quickMove = Control.INV_QUICK_MOVE.isDown();
                  container.withdrawAction
                     .runAndSend(item, Math.min(item.getAmount(), item.item.getStackSize()), !quickMove);
                  event.use();
               }

               @Override
               public void handleInputEvent(InputEvent event, TickManager tickManager, PlayerMob perspective) {
                  super.handleInputEvent(event, tickManager, perspective);

                  // Deposit when the click landed on the grid but not on an item. super runs first
                  // and marks the event used if an element or a scroll button took it, so this only
                  // sees genuinely empty space -- which is what makes "click anywhere" safe to add
                  // without stealing clicks from anything.
                  if (event.isUsed() || !event.isMouseClickEvent() || !event.state) {
                     return;
                  }

                  if (!StorageTerminalContainerForm.this.isHoldingItem() || !this.isMouseWithin(event)) {
                     return;
                  }

                  container.depositCursorAction.runAndSend(event.getID() == InputID.RIGHT_CLICK ? 1 : -1);
                  event.use();
               }

               private boolean isMouseWithin(InputEvent event) {
                  // hudX/hudY rather than window coordinates, because that is the space component
                  // positions and hitboxes are expressed in -- see FormComponent.isMouseOver.
                  return event.pos.hudX >= this.getX()
                        && event.pos.hudX < this.getX() + this.width
                        && event.pos.hudY >= this.getY()
                        && event.pos.hudY < this.getY() + this.height;
               }
            }
         );

      flow.next(PADDING);

      // Controls reuse icons GameInterfaceStyle already provides, so this needs no new art.
      //
      // The icon directions follow vanilla exactly, which is the reverse of what the names
      // suggest at first glance: "in" means into the player's inventory (restock) and "out"
      // means out of it (quick-stack). Matching that is the point -- a player who has learnt
      // these icons on the inventory quickbar should not have to relearn them here.
      //
      // The tooltips, however, are ours. Vanilla's read "Quick stack to nearby storage" and
      // "Restock from nearby storage", and these buttons deliberately do not work by proximity:
      // they reach the whole network however far it stretches and ignore chests that merely
      // happen to be close. Reusing those strings would have been free translation in exchange
      // for telling the player something false.
      int controlHeight = FormInputSize.SIZE_24.height;
      int controlY = flow.next(controlHeight + PADDING);
      int controlX = this.mainForm.getWidth() - PADDING - controlHeight;

      FormContentIconButton restockButton = this.mainForm
         .addComponent(
            new FormContentIconButton(
               controlX,
               controlY,
               FormInputSize.SIZE_24,
               ButtonColor.BASE,
               Settings.UI.inventory_quickstack_in,
               new LocalMessage("ui", "arcanestorage_restocktip")
            )
         );
      restockButton.onClicked(event -> this.sendSlotAction(Container.RESTOCK_SLOT));
      restockButton.setCooldown(500);

      controlX -= controlHeight + PADDING;
      this.sortButton = this.mainForm
         .addComponent(
            new FormContentIconButton(
               controlX, controlY, FormInputSize.SIZE_24, ButtonColor.BASE, Settings.UI.inventory_sort, this.sortTooltip()
            )
         );
      this.sortButton.onClicked(event -> {
         this.sortMode = this.sortMode.next();
         this.sortButton.setTooltips(this.sortTooltip());
         this.refreshList();
      });

      controlX -= controlHeight + PADDING;
      FormContentIconButton quickStackButton = this.mainForm
         .addComponent(
            new FormContentIconButton(
               controlX,
               controlY,
               FormInputSize.SIZE_24,
               ButtonColor.BASE,
               Settings.UI.inventory_quickstack_out,
               new LocalMessage("ui", "arcanestorage_quickstacktip")
            )
         );
      quickStackButton.onClicked(event -> this.sendSlotAction(Container.QUICK_STACK_SLOT));
      quickStackButton.setCooldown(500);

      // Deposit-all is labelled rather than iconned. It moves items in the same direction as
      // quick-stack, so an icon would have to distinguish scope rather than direction, and
      // reusing the quick-stack arrow with a different tooltip would just look like a duplicate
      // button. A word is unambiguous and costs no art.
      int depositWidth = 96;
      controlX -= depositWidth + PADDING;
      FormLocalTextButton depositAllButton = this.mainForm
         .addComponent(
            new FormLocalTextButton(
               new LocalMessage("ui", "arcanestorage_depositall"),
               new LocalMessage("ui", "arcanestorage_depositalltip"),
               controlX,
               controlY,
               depositWidth,
               FormInputSize.SIZE_24,
               ButtonColor.BASE
            )
         );
      depositAllButton.onClicked(event -> container.depositAllAction.runAndSend());
      depositAllButton.setCooldown(500);

      // Capacity sits on the same row, in slots, because slots are what run out.
      //
      // This was a plain label, on the reasoning that a bar would need art and read as decorative.
      // That was wrong: FormProgressBarText is a vanilla component, needs no art, and draws the
      // number inside the bar -- so it keeps "312 / 480", which is what a player deciding whether
      // to place another unit needs, and adds the proportion at a glance.
      //
      // Its colours have to be inverted, though. The component calls full "complete" and paints it
      // with successTextColor, which is correct for a crafting requirement and exactly backwards
      // for storage: a full network is the bad outcome. See getTextColor below.
      this.capacityBar = this.mainForm.addComponent(new FormProgressBarText(PADDING, controlY + 4, 1, CAPACITY_BAR_WIDTH) {
         @Override
         public String getText() {
            return Localization.translate("ui", "arcanestorage_capacity",
                  "used", String.valueOf(this.currentProgress), "total", String.valueOf(this.totalProgress));
         }

         @Override
         public Color getTextColor() {
            // The bar carries the signal now, so the number stays readable rather than competing
            // with it. Colouring both said the same thing twice and made the text harder to read.
            return this.getInterfaceStyle().activeTextColor;
         }

         @Override
         public void draw(TickManager tickManager, PlayerMob perspective, Rectangle renderBox) {
            // Reimplemented rather than extended because FormProgressBarText hardcodes
            // Settings.UI.progressBarFill for the fill and exposes no hook, and its width field is
            // private -- hence CAPACITY_BAR_WIDTH being a constant rather than read back.
            //
            // The colours are ours but the drawing is not: this is the same Achievement call the
            // component makes, one overload deeper, where completeCol is the fill.
            float progress = this.totalProgress <= 0
                  ? 0.0F
                  : GameMath.limit((float) this.currentProgress / this.totalProgress, 0.0F, 1.0F);

            Achievement.drawProgressbarText(
                  this.getX(), this.getY(), CAPACITY_BAR_WIDTH, 5, progress,
                  Settings.UI.progressBarOutline, capacityFillColor(progress),
                  this.getText(), new FontOptions(16).color(this.getTextColor()));

            if (this.isHovering()) {
               GameTooltips tooltips = this.getTooltips();
               if (tooltips != null) {
                  GameTooltipManager.addTooltip(tooltips, TooltipLocation.FORM_FOCUS);
               }
            }
         }
      });
      this.capacityBar.setTooltip(new LocalMessage("ui", "arcanestorage_capacity_tip"));
      this.updateCapacityLabel();

      // The tab's height is fixed, so the flow cannot set it. Checked instead, because a silent
      // half-row of clipping is exactly the kind of thing that survives a review.
      int layoutHeight = flow.next(PADDING);
      if (layoutHeight != FORM_HEIGHT) {
         GameLog.warn.println("Arcane Storage: storage tab wants " + layoutHeight
               + "px but FORM_HEIGHT is " + FORM_HEIGHT + "px; the tab will clip or leave a gap.");
      }

      this.craftingForm = this.buildCraftingTab(client, container);
      this.makeCurrent(this.tabs);

      // Primed here because refreshList() reads it, and the first draw has not happened yet.
      this.aggregated = container.getAggregatedItems();

      // Must happen before the form can receive input, not lazily in draw().
      // FormItemList.reset() does not call super.reset(), so FormGeneralList.elements stays
      // null from construction until the first updateList — and input events are handled
      // earlier in the frame than any draw, so a click on the first frame would hit null.
      this.refreshList();
   }

   /**
    * The crafting tab: vanilla's recipe list, fed by the network.
    *
    * <p>Almost nothing here is ours. {@link FormContainerCraftingListContentBox} is abstract with a
    * single method to supply, and computes each recipe's craftable state itself against the
    * container's craft inventories -- which for this container are the network's slots, so "can I
    * build this" is answered against everything in storage without a line of code here. The
    * per-ingredient have/missing display and the click-to-craft path come with it.
    *
    * <p>{@link RecipeFilter} is vanilla too, and already holds exactly the state this tab needs:
    * a search tester, a craftable-only flag, and category filters, with
    * {@code getFilteredRecipes} evaluating craftability against the container's own inventories.
    * Writing a filter here would have duplicated it and diverged from how a bench behaves.
    *
    * <p>Recipes are streamed with {@link RecipeTechRegistry#ALL}, which {@code Recipe.matchTech}
    * treats as matching everything. So the tab shows whatever the container has registered and
    * needs no knowledge of which stations are installed -- when bench installation lands, its
    * recipes appear here because they were registered, not because this method changed.
    */
   private Form buildCraftingTab(Client client, T container) {
      Form form = this.tabs.addLocalizedTab(new LocalMessage("ui", "arcanestorage_tab_crafting"), null);

      // Keyed by string ID rather than by the object instance, because Settings keeps these in a
      // plain map for the session and a stable key is the only requirement.
      RecipeFilter filter = Settings.getRecipeFilterSetting(ArcaneStorage.TERMINAL_STRING_ID);

      // Vanilla's setting is the source of truth, so the choice carries between a bench and the
      // terminal instead of being a private option a player has to find twice.
      filter.setCraftableOnly(Settings.craftingListOnlyCraftable.get());

      FormFlow flow = new FormFlow(PADDING);
      int headerY = flow.next(FormInputSize.SIZE_24.height + PADDING);
      form.addComponent(
            new FormLocalLabel("ui", "arcanestorage_tab_crafting", new FontOptions(20), -1, PADDING, headerY + 4,
                  FORM_WIDTH - PADDING * 3 - SEARCH_WIDTH));

      FormTextInput search = form.addComponent(
            new FormTextInput(FORM_WIDTH - PADDING - SEARCH_WIDTH, headerY, FormInputSize.SIZE_24, SEARCH_WIDTH, -1, 100));
      search.placeHolder = new LocalMessage("ui", "searchtip");
      search.rightClickToClear = true;
      search.setText(filter.getSearchFilter());
      search.onChange(event -> filter.setSearchFilter(search.getText()));

      int controlHeight = 16 + PADDING;
      int listY = flow.next(0);
      int listHeight = FORM_HEIGHT - listY - controlHeight - PADDING;

      form.addComponent(
            new FormContainerCraftingListContentBox(
                  PADDING, listY, FORM_WIDTH - PADDING * 2, listHeight, client, false, false, false) {
               private final Supplier<Boolean> filterChanged = filter.addMonitor(this);
               private boolean craftabilityChanged;

               @Override
               public Stream<ContainerRecipe> streamAllRecipes() {
                  return filter
                     .getFilteredRecipes(
                        container.streamRecipes(RecipeTechRegistry.ALL).collect(Collectors.toList()), container)
                     .stream();
               }

               @Override
               public void updateCraftable() {
                  // The base class recomputes each recipe's canCraft here, but list *membership* is
                  // fixed at updateRecipes time -- and craftable-only filtering happens there, in
                  // RecipeFilter.getFilteredRecipes. So when the network's contents changed while
                  // the tab was open, a recipe that became craftable had no way to reappear: its
                  // shouldShow is only showHidden || doesShowRecipe, which craftability never
                  // enters. That is the bug Elias hit. A full rebuild is the honest fix, since the
                  // filter is what decides membership.
                  this.craftabilityChanged = true;
                  super.updateCraftable();
               }

               @Override
               public void draw(TickManager tickManager, PlayerMob perspective, Rectangle renderBox) {
                  // Polled in draw for the same reason the base class polls its own craftable flag
                  // here: a filter change can arrive from the search box, the checkbox, or the
                  // settings listener, and rebuilding at the point of change would do it repeatedly
                  // for one keystroke. Coalescing to one rebuild per frame also means a burst of
                  // inventory updates costs one rebuild rather than one each.
                  boolean membershipMayHaveChanged = this.craftabilityChanged && filter.craftableOnly();
                  this.craftabilityChanged = false;

                  if (this.filterChanged.get() || membershipMayHaveChanged) {
                     this.updateRecipes();
                  }

                  super.draw(tickManager, perspective, renderBox);
               }
            });

      FormLocalCheckBox onlyCraftable = form.addComponent(
            new FormLocalCheckBox("ui", "filteronlycraftable", PADDING, FORM_HEIGHT - 16 - PADDING,
                  Settings.craftingListOnlyCraftable.get()),
            100);
      onlyCraftable.onClicked(event -> {
         Settings.craftingListOnlyCraftable.set(event.from.checked);
         Settings.saveClientSettings();
      });

      // Mirrors vanilla's own listener so the checkbox follows the setting when it is changed
      // elsewhere -- including by a crafting bench open at the same time.
      Settings.craftingListOnlyCraftable.addChangeListener(value -> {
         onlyCraftable.checked = value;
         filter.setCraftableOnly(value);
         GlobalData.updateCraftable();
      }, this::isDisposed);

      return form;
   }

   /**
    * Fills a dropdown level with a category's children, recursing while depth allows.
    *
    * <p>A category with children of its own becomes a submenu whose first entry selects the
    * category itself, so "everything under Materials" stays one click away from where its
    * subdivisions are. Without that entry a parent category would be visible and unselectable,
    * which is the usual way a nested menu becomes annoying.
    *
    * <p>Children are sorted with the game's own {@link ItemCategory} ordering, which is what the
    * creative menu uses, so the menu reads in the order a player has already seen elsewhere.
    */
   private static void addCategoryOptions(
         FormDropdownSelectionButton<ItemCategory>.OptionsList<ItemCategory> options, ItemCategory parent, int depth) {
      List<ItemCategory> children = new ArrayList<>();
      parent.getChildren().forEach(children::add);
      children.sort(Comparator.naturalOrder());

      for (ItemCategory category : children) {
         boolean hasChildren = category.getChildren().iterator().hasNext();
         if (hasChildren && depth < CATEGORY_MENU_DEPTH) {
            FormDropdownSelectionButton<ItemCategory>.OptionsList<ItemCategory> sub = options.addSub(category.displayName);
            sub.add(category, new LocalMessage("ui", "arcanestorage_category_everything", "category", category.displayName.translate()));
            addCategoryOptions(sub, category, depth + 1);
         } else {
            options.add(category, category.displayName);
         }
      }
   }

   /**
    * Whether an item belongs to the chosen category, directly or through any ancestor.
    *
    * <p>Walking up from the item's own category is what makes picking a parent mean "and
    * everything beneath it", and it matches how {@code Item.matchesSearch} treats categories —
    * so the picker and the search box agree about what a category contains rather than each
    * having its own idea.
    */
   private boolean matchesCategory(InventoryItem item) {
      if (this.categoryFilter == null) {
         return true;
      }

      for (ItemCategory category = ItemCategory.getItemsCategory(item.item); category != null; category = category.parent) {
         if (category.id == this.categoryFilter.id) {
            return true;
         }
      }

      return false;
   }

   /** Names the current ordering, so one cycling button does not leave the player guessing. */
   private GameMessage sortTooltip() {
      return new LocalMessage("ui", "arcanestorage_sorttip", "mode", Localization.translate("ui", this.sortMode.localeKey));
   }

   /**
    * Fires one of the engine's special slot actions, the way vanilla's quickbar buttons do.
    *
    * <p>Sent as a packet rather than applied locally because these move items between the
    * player and the network, and only the server's copy is authoritative. Our container
    * redirects both indices at the network instead of at nearby containers.
    */
   private void sendSlotAction(int slotIndex) {
      this.client.network.sendPacket(new PacketContainerAction(slotIndex, ContainerAction.LEFT_CLICK, 1));
   }

   /**
    * Applies a new query and rebuilds the grid.
    *
    * <p>Uses the engine's {@link ItemSearchTester}, so the syntax is the same as everywhere
    * else in the game rather than something a player has to learn twice: terms separated by
    * {@code |} are alternatives, and a term prefixed with {@code @} searches tooltips as well.
    * Matching covers the item's string ID, its display name, and every category above it — so
    * a query like "sword" or "food" filters by category without a category picker existing yet.
    */
   private void setSearch(String query) {
      this.searchTester = ItemSearchTester.constructSearchTester(query);
      this.refreshList();
   }

   /**
    * Writes the current slot usage into the footer.
    *
    * <p>Refreshed alongside the grid rather than every frame, since it changes for exactly the
    * same reasons the grid does.
    */
   /**
    * Reports what the grid is showing, and says so in terms of kinds and items.
    *
    * <p>Kinds and items are counted separately because they answer different questions -- how far
    * the list scrolls, and how much material is in the network -- and a single number would be
    * ambiguous between them. When a filter is hiding something the total is shown alongside, so an
    * empty grid is legible: "0 of 37 kinds" is a filter, "0 kinds" is an empty network.
    */
   /**
    * Whether the player is holding something on the cursor.
    *
    * <p>Read from the container's own dragging slot rather than from the player's drag inventory,
    * so the client asks the same question the server will answer when the action arrives.
    */
   private boolean isHoldingItem() {
      ContainerSlot cursor = this.getContainer().getClientDraggingSlot();
      return cursor != null && !cursor.isClear();
   }

   private void updateSummary(List<InventoryItem> shown) {
      long items = 0L;
      for (InventoryItem item : shown) {
         items += item.getAmount();
      }

      int kinds = shown.size();
      int available = this.aggregated == null ? kinds : this.aggregated.size();
      this.summaryLabel.setText(kinds == available
            ? Localization.translate("ui", "arcanestorage_summary",
                  "kinds", String.valueOf(kinds), "items", String.valueOf(items))
            : Localization.translate("ui", "arcanestorage_summary_filtered",
                  "kinds", String.valueOf(kinds), "available", String.valueOf(available),
                  "items", String.valueOf(items)));
   }

   private void updateCapacityLabel() {
      T container = this.getContainer();
      this.capacityBar.currentProgress = container.getUsedSlots();
      // Guarded because the bar divides by this, and a terminal with no units linked reports zero.
      this.capacityBar.totalProgress = Math.max(1, container.getTotalSlots());
   }

   /**
    * Rebuilds the grid from the network's current contents.
    *
    * <p>Driven by a content signature rather than run every frame, because rebuilding
    * resets the player's scroll position. It cannot be driven by
    * {@code populateIfNotAlready} alone either: that only refills when the list is empty,
    * so an empty network would rebuild on every frame while a full one would never rebuild
    * at all.
    */
   private void refreshList() {
      this.shownSignature = signatureOf(this.aggregated);
      this.itemList.reset();
      this.itemList.populateIfNotAlready();
      this.updateCapacityLabel();
   }

   /**
    * Refreshes when the network's contents change. The slots behind the list are real and
    * engine-synced, so a unit edited by anything else — another player, a settler, a unit
    * being broken — shows up here without any extra messaging.
    */
   @Override
   public void draw(TickManager tickManager, PlayerMob perspective, Rectangle renderBox) {
      this.aggregated = this.getContainer().getAggregatedItems();
      if (signatureOf(this.aggregated) != this.shownSignature) {
         this.refreshList();
      }

      super.draw(tickManager, perspective, renderBox);
   }

   /** Order-sensitive hash of item identity and amount, used only to detect changes. */
   private static long signatureOf(List<InventoryItem> items) {
      long signature = 1L;
      for (InventoryItem item : items) {
         signature = signature * 31L + item.item.getStringID().hashCode();
         signature = signature * 31L + item.getAmount();
      }
      return signature;
   }

   @Override
   public void onWindowResized(GameWindow window) {
      super.onWindowResized(window);
      // The preset's own form is the panel that gets drawn and positioned; the tab contents live
      // inside it and the tab buttons position themselves against it.
      ContainerComponent.setPosFocus(this.tabs.form);
   }

   @Override
   public boolean shouldOpenInventory() {
      return true;
   }
}
