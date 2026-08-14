package arcanestorage.container;

import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import arcanestorage.ui.ArcanePanel;
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
import necesse.gfx.forms.components.containerSlot.FormContainerSlot;
import necesse.inventory.recipe.Tech;
import necesse.engine.registries.ItemRegistry;
import java.util.HashSet;
import java.util.Set;
import necesse.gfx.forms.components.FormCheckBox;
import necesse.gfx.forms.components.FormContentBox;
import necesse.gfx.forms.components.FormContainerRecipe;
import necesse.inventory.recipe.CanCraft;
import arcanestorage.ArcaneStorage;
import arcanestorage.objectentity.BusSummary;
import necesse.inventory.itemFilter.ItemCategoriesFilter;
import necesse.gfx.forms.components.FormTextButton;
import necesse.engine.ItemCategoryExpandedSetting;
import java.util.LinkedHashMap;
import necesse.engine.window.WindowManager;
import necesse.engine.localization.Localization;
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
import necesse.gfx.GameColor;
import necesse.engine.localization.message.GameMessage;
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
import necesse.gfx.gameFont.FontManager;
import necesse.gfx.gameFont.FontOptions;
import necesse.inventory.InventoryItem;
import necesse.inventory.container.Container;
import necesse.inventory.container.ContainerAction;
import necesse.inventory.item.ItemCategory;
import necesse.inventory.item.ItemSearchTester;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import necesse.gfx.camera.GameCamera;
import necesse.level.maps.Level;
import necesse.gfx.GameResources;
import necesse.gfx.shader.FormShader;
import java.awt.Point;
import necesse.engine.GlobalData;
import necesse.engine.window.GameWindow;
import necesse.engine.window.WindowManager;
import necesse.gfx.Renderer;
import necesse.engine.GameLog;
import necesse.engine.registries.RecipeTechRegistry;
import necesse.gfx.forms.components.FormContainerCraftingListContentBox;
import necesse.gfx.forms.components.localComponents.FormLocalCheckBox;
import necesse.gfx.forms.presets.TabbedFormPreset;
import necesse.inventory.container.ContainerRecipe;
import necesse.inventory.recipe.RecipeFilter;

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

   /**
    * Puts a form on the mod's own panel, if the player has not turned that off.
    *
    * <p>Applied per tab rather than once, because each tab is a separate {@link Form} with its own
    * background -- {@code TabbedFormPreset} hands back a new one per tab and the tab strip itself
    * draws outside the panel, at {@code form.getY() - offset}. Missing one would leave a single tab
    * looking like a different mod's interface.
    */
   private static Form styled(Form form) {
      form.setBackground(ArcanePanel.of());
      return form;
   }

   private static final int PADDING = 4;
   private static final int SEARCH_WIDTH = 220;

   /** Vanilla's slot pitch: FormContainerSlot draws 32px of slot with a 40px stride. */
   private static final int SLOT_PITCH = 40;

   /**
    * Two rows of source tickboxes, which is what fits above the recipe list without crowding it.
    * A third row scrolls.
    */
   private static final int BENCH_STRIP_HEIGHT = 66;

   /**
    * Sized for the names rather than for a tidy count. {@code FormCheckBox.setText} *wraps* at its
    * max width rather than truncating -- which is why the first version overflowed: at 106px wide,
    * "Demonic Workstation" became two lines inside a 24px panel. At 156 the common names fit on one
    * line and the few long ones ("Caveglow Alchemy Table") wrap into a panel tall enough to hold two.
    * Truncating with an ellipsis was the alternative, and rejected: a station's name is the whole
    * content of the control.
    */
   private static final int BENCH_PANEL_WIDTH = 156;

   private static final int BENCH_PANEL_HEIGHT = 32;

   /** 32px recipe icon plus 2px padding either side, matching the base class's own arithmetic. */
   private static final int RECIPE_ELEMENT_SIZE = 36;

   /** Vanilla's crafting bench depth, so sections here are as broad as a bench's. */
   private static final int CRAFTING_CATEGORY_DEPTH = 1;

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

   /** The logistics tab's left column: the list of devices. The rest of the width is the selected one's rules. */
   private static final int DEVICE_LIST_WIDTH = 208;

   private static final int DEVICE_ROW_HEIGHT = 24;

   private static final int DEVICE_ROW_PITCH = 26;

   /** The issues panel: its font, and how many wrapped lines of reasons it shows before it scrolls. */
   /** How thick the world marker's outline is. Two pixels reads as a line at any zoom the game allows. */
   private static final int MARKER_EDGE = 2;

   private static final int ISSUE_FONT = 14;

   /** One row of the issues area: the summary line, and the pitch the per-device boxes wrap at. */
   private static final int ISSUE_ROW = 22;

   /** Chrome around a box's name, so the text is not flush against its own edge. */
   private static final int ISSUE_BOX_PADDING = 16;

   private static final int COPY_BUTTON_WIDTH = 56;

   /** Translucent so the panel beneath still reads as part of the interface rather than a hole cut in it. */
   private static final Color ISSUE_BACKING = new Color(150, 40, 36, 150);

   public final Form mainForm;
   public final FormItemList itemList;
   public final FormTextInput searchInput;
   public final TabbedFormPreset tabs;

   /** Which tab is the logistics one, so the world marker only shows while a player is looking at it. */
   private int logisticsTabIndex = -1;

   /** The tile the world marker is over, or -1: written each frame, read by the marker as it draws. */
   private int markerX = -1;

   private int markerY = -1;

   /** Which device the marker has already reported drawing, so the diagnostic is one line and not one a frame. */
   private long markerLogged = Long.MIN_VALUE;

   private long markerLastTraced = 0;

   private long markerGuardLastTraced = 0;

   private long markerDrawFailed = Long.MIN_VALUE;
   public final Form craftingForm;

   public final Form stationsForm;

   public final Form logisticsForm;

   /** The red-backed list of stopped devices, and its backing. Hidden together when nothing is wrong. */
   private FormColorFill issueBacking;

   private FormLabel issueLabel;

   /** Where the issues area starts, and the invisible form its per-device boxes are flowed into. */
   private int issuesY;

   private Form issueBoxGroup;

   private final List<FormTextButton> issueBoxes = new ArrayList<>();

   private FormLocalTextButton copyButton;

   /** Every stopped device's reason, newline separated, as the copy button would put it on the clipboard. */
   private String issueClipboardText = "";

   /** Which stopped devices the issues area was last built for, so it is rebuilt only when that changes. */
   private String shownIssues = "\u0000";

   /** Where the lists start and how tall they are, both following the issues area's height. */
   private int contentTop;

   private int contentHeight;

   private FormContentBox deviceListBox;

   private final List<FormTextButton> deviceButtons = new ArrayList<>();

   /**
    * The bus whose rules the right-hand pane is editing, as a tile key, or {@link #NO_DEVICE}.
    *
    * <p>One at a time rather than every bus expanded at once. The rules editor contains a scrolling category
    * tree, and a column of those inside another scrolling list means two nested scroll regions and a
    * hit-testing hazard that has already cost a day here once.
    */
   private long selectedDevice = NO_DEVICE;

   /** Which bus the pane was built for, so it is rebuilt when the selection changes or its rules arrive. */
   private long paneBuiltFor = NO_DEVICE;

   private Form devicePane;

   /** The scroll box the whole pane lives in, so the editor gets its natural height instead of being squeezed. */
   private FormContentBox devicePaneBox;

   private BusRulesEditor deviceRules;

   /** What the device list was built from, so it is rebuilt when the network changes and not every frame. */
   private String shownDevices = "";

   private static final long NO_DEVICE = Long.MIN_VALUE;

   /**
    * Sources the player has unticked. Transient by the same reasoning as the search and the
    * craftable-only toggle: the interface forgets its filters when it closes.
    */
   private final Set<Tech> hiddenBenches = new HashSet<>();

   private final List<Form> benchPanels = new ArrayList<>();

   private boolean benchesChanged;
   public final FormProgressBarText capacityBar;
   public final FormLabel summaryLabel;

   /**
    * How many devices on this network have stopped, with the reasons on hover.
    *
    * <p>The state itself lives on each bus and travels through the terminal's own object entity, so this is
    * only a view. Blank when nothing is wrong, which is the normal case and should cost the player no
    * attention.
    */
   public final FormLabel problemsLabel;
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
      this.mainForm = styled(this.tabs.addLocalizedTab(new LocalMessage("ui", "arcanestorage_tab_storage"), null));

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

      // Centred on the category row, which is empty between the dropdown and the item count. Short by
      // design, with the detail on hover: the terminal is where a player comes when storage misbehaves, and a
      // gray sprite on a bus behind a wall is not something they can find. Placed as an overlay on an
      // existing row rather than as a new one, so the tab's fixed height still adds up.
      this.problemsLabel = this.mainForm
         .addComponent(new FormLabel("", new FontOptions(12), 0,
               this.mainForm.getWidth() / 2, categoryY + 4, this.mainForm.getWidth() / 2));

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
      //
      // Read with a second, zero-height step, because FormFlow.next(add) returns the position
      // *before* advancing -- that is what makes `int y = flow.next(rowHeight)` give a row its own
      // top. The first version of this check read the final next() directly and so compared the
      // last row's start against the total, reporting a 4px discrepancy that did not exist. It cried
      // wolf in Elias's log for a day.
      flow.next(PADDING);
      int layoutHeight = flow.next();
      if (layoutHeight != FORM_HEIGHT) {
         GameLog.warn.println("Arcane Storage: storage tab wants " + layoutHeight
               + "px but FORM_HEIGHT is " + FORM_HEIGHT + "px; the tab will clip or leave a gap.");
      }

      this.craftingForm = this.buildCraftingTab(client, container);
      this.stationsForm = this.buildStationsTab(client, container);
      this.logisticsForm = this.buildLogisticsTab(client, container);
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
    * Which section a recipe belongs in, by vanilla's own rule.
    *
    * <p>A recipe may name its own crafting category; otherwise the category comes from the result
    * item and is walked up to the depth a crafting bench uses. Depth 1 is what
    * {@code CraftingStationObject.getCraftingCategoryDepth} returns, so the terminal's sections are
    * the same breadth a bench's are -- a deeper cut would give a section per handful of recipes.
    */
   private static ItemCategory craftingCategoryOf(ContainerRecipe recipe) {
      ItemCategory category = recipe.recipe.getCraftingCategory();
      if (category != null) {
         return category;
      }

      category = ItemCategory.craftingManager.getItemsCategory(recipe.recipe.resultItem.item);
      while (category != null && category.parent != null && category.depth > CRAFTING_CATEGORY_DEPTH) {
         category = category.parent;
      }

      return category;
   }

   /**
    * What to call a crafting source in the tickbox strip.
    *
    * <p>The station's own item name, not the tech's display name, because the tech names have gaps:
    * the game's locale has 26 `tech` entries and no `tech/transmutation`, so a Transmutation Station
    * logged "Translation of tech.transmutation is not found" and would have shown the raw key. That
    * is a vanilla omission, not something to work around with a private label -- and a tech's
    * {@code itemStringID} points at the station that provides it, whose name is in the `object`
    * locale and complete. It is also the name the player recognises, since it is what the item in
    * their inventory is called.
    *
    * <p>Falls back to the tech name for sources with no item behind them, which is how hand recipes
    * come out as "Inventory": their tech's itemStringID is "inventory", which is not a real item.
    */
   private static String sourceLabel(Tech tech) {
      return ItemRegistry.getItemID(tech.itemStringID) == -1
            ? tech.displayName.translate()
            : ItemRegistry.getLocalization(tech.itemStringID).translate();
   }

   /**
    * Rebuilds the source tickboxes from the installed stations.
    *
    * <p>Sources are {@code Tech}s rather than bench items, because that is what a recipe carries, and
    * it makes tiering read correctly: a Demonic Workstation installs two techs and so contributes two
    * tickboxes, which is the distinction a player wants when hunting for a recipe. Hand recipes get a
    * box like any other, labelled with the game's own name for that tech -- "Inventory" -- so nothing
    * here special-cases them.
    *
    * <p>New sources arrive ticked. Anything the player unticked stays unticked, so installing a
    * second bench does not quietly undo a choice they made about the first.
    */
   private void rebuildBenchStrip(FormContentBox box, List<Tech> sources) {
      for (Form panel : this.benchPanels) {
         box.removeComponent(panel);
      }

      this.benchPanels.clear();
      this.hiddenBenches.retainAll(sources);

      int columns = Math.max(1, (box.getWidth() - box.getScrollBarWidth()) / BENCH_PANEL_WIDTH);
      int rows = (sources.size() + columns - 1) / columns;

      for (int i = 0; i < sources.size(); i++) {
         Tech source = sources.get(i);
         Form panel = box.addComponent(new Form(BENCH_PANEL_WIDTH - 2, BENCH_PANEL_HEIGHT - 2));
         panel.setPosition(i % columns * BENCH_PANEL_WIDTH, i / columns * BENCH_PANEL_HEIGHT);

         FormCheckBox tick = panel.addComponent(new FormCheckBox(sourceLabel(source), 4, 4,
               BENCH_PANEL_WIDTH - 12, !this.hiddenBenches.contains(source)));
         tick.onClicked(event -> {
            if (event.from.checked) {
               this.hiddenBenches.remove(source);
            } else {
               this.hiddenBenches.add(source);
            }

            this.benchesChanged = true;
         });

         this.benchPanels.add(panel);
      }

      box.setContentBox(new Rectangle(box.getWidth() - box.getScrollBarWidth(), rows * BENCH_PANEL_HEIGHT));
   }

   /**
    * The stations tab: ten slots, and an explanation.
    *
    * <p>Plain container slots, so installing a bench is dragging an item into a slot -- the same
    * gesture as any other inventory in the game, with the engine moving the item and the terminal's
    * own {@code isItemValid} refusing anything that is not a station. Nothing here validates
    * anything, which is the point: a slot that rejects the wrong item without a special case cannot
    * disagree with the server about what is installed.
    */
   private Form buildStationsTab(Client client, T container) {
      Form form = styled(this.tabs.addLocalizedTab(new LocalMessage("ui", "arcanestorage_tab_stations"), null));

      FormFlow flow = new FormFlow(PADDING);
      int headerY = flow.next(FormInputSize.SIZE_24.height + PADDING);
      form.addComponent(new FormLocalLabel("ui", "arcanestorage_tab_stations", new FontOptions(20), -1,
            PADDING, headerY + 4, FORM_WIDTH - PADDING * 2));

      // Sockets come from Station Units now, so the count is whatever the network offers rather than a
      // fixed ten, and zero is a normal state rather than an error to guard against. Wrapped onto rows,
      // because the ladder tops out at eight per unit and a network may hold several: a single row would
      // run off the form long before the design ran out of sockets.
      int sockets = container.STATION_START == -1 ? 0 : container.STATION_END - container.STATION_START + 1;
      int perRow = Math.max(1, (FORM_WIDTH - PADDING * 2) / SLOT_PITCH);

      if (sockets == 0) {
         // Deliberately says what to do, not what went wrong. An empty tab with no explanation reads as
         // a broken feature, and this is the one state every new network starts in.
         form.addComponent(new FormLocalLabel("ui", "arcanestorage_stations_none", new FontOptions(16), -1,
               PADDING, flow.next(48), FORM_WIDTH - PADDING * 2));
      } else {
         // FormFlow.next(add) returns the position *before* advancing, so a row's Y is captured when the
         // row opens rather than read back afterwards -- the flow has no getter for where it is.
         int rowY = 0;
         for (int i = 0; i < sockets; i++) {
            int column = i % perRow;
            if (column == 0) {
               rowY = flow.next(SLOT_PITCH);
            }

            form.addComponent(new FormContainerSlot(client, container, container.STATION_START + i,
                  PADDING + column * SLOT_PITCH, rowY));
         }

         flow.next(PADDING);
      }

      // Wrapped over the width of the form rather than sized to the text, because the explanation is
      // three sentences and the tab is otherwise empty -- there is no layout pressure to save space.
      form.addComponent(new FormLocalLabel("ui", "arcanestorage_stations_help", new FontOptions(16), -1,
            PADDING, flow.next(64), FORM_WIDTH - PADDING * 2));

      return form;
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
    * <p>Recipes are streamed with {@link RecipeTechRegistry#ALL} and then narrowed to what the
    * installed stations allow, because the container registers every recipe in the game to keep
    * recipe IDs stable -- see {@code StorageTerminalContainer.applyCraftingAction}, which refuses
    * the rest server-side. So this list is a view, and the server is the authority.
    */
   private Form buildCraftingTab(Client client, T container) {
      Form form = styled(this.tabs.addLocalizedTab(new LocalMessage("ui", "arcanestorage_tab_crafting"), null));

      // A fresh filter per open, deliberately not Settings.getRecipeFilterSetting, which keeps one
      // per key for the session and would therefore remember the last search and toggle.
      //
      // This reverses an earlier decision of mine to share vanilla's craftingListOnlyCraftable so
      // the choice would carry between a bench and the terminal. Elias asked for the opposite, and
      // he is right on both counts: the storage tab already forgets its search and category on
      // close, so a crafting tab that remembered them would be inconsistent within one interface;
      // and sharing meant the terminal wrote a preference belonging to benches. Starting clean also
      // guarantees the show-all default, which is what stops an empty list reading as a broken tab.
      RecipeFilter filter = new RecipeFilter();

      FormFlow flow = new FormFlow(PADDING);
      int headerY = flow.next(FormInputSize.SIZE_24.height + PADDING);
      form.addComponent(
            new FormLocalLabel("ui", "arcanestorage_tab_crafting", new FontOptions(20), -1, PADDING, headerY + 4,
                  FORM_WIDTH - PADDING * 3 - SEARCH_WIDTH));

      FormTextInput search = form.addComponent(
            new FormTextInput(FORM_WIDTH - PADDING - SEARCH_WIDTH, headerY, FormInputSize.SIZE_24, SEARCH_WIDTH, -1, 100));
      search.placeHolder = new LocalMessage("ui", "searchtip");
      search.rightClickToClear = true;
      search.onChange(event -> filter.setSearchFilter(search.getText()));

      // One tickbox per source, all on, in a strip above the list -- Elias's design, replacing a
      // dropdown I had put here first. Two reasons it is better: several benches can be shown at
      // once, which a single-select dropdown cannot express, and every source is visible without
      // opening a menu, so the strip doubles as "what can this terminal build from".
      //
      // In a scrolling box because the count is not bounded by the ten slots: an upgraded bench
      // reports the lower techs too, so ten benches can be twenty-odd sources.
      // Session-scoped, exactly like vanilla's: Settings keeps these in a map that is never written
      // to the settings file, so which sections a player left open survives reopening the terminal
      // and not restarting the game. Keyed on our own string ID so it cannot collide with a bench's.
      ItemCategoryExpandedSetting expanded = Settings.getItemCategoryExpandedSetting(
            ArcaneStorage.TERMINAL_STRING_ID + "crafting", ItemCategory.craftingMasterCategory, true);

      FormContentBox benchBox = form.addComponent(
            new FormContentBox(PADDING, flow.next(BENCH_STRIP_HEIGHT + PADDING), FORM_WIDTH - PADDING * 2,
                  BENCH_STRIP_HEIGHT));

      // Filled here as well as from draw, because input is handled earlier in the frame than any
      // draw: a strip built lazily would be unclickable on the frame it appeared.
      List<Tech> initialSources = new ArrayList<>();
      initialSources.add(RecipeTechRegistry.NONE);
      initialSources.addAll(container.terminal.getInstalledTechs());
      this.rebuildBenchStrip(benchBox, initialSources);

      // Sits over the top of the list area rather than in the flow, because it is only ever visible
      // when the list is empty -- and an empty list leaves that space blank anyway.
      FormLabel emptyHint = form.addComponent(
            new FormLabel("", new FontOptions(16), 0, FORM_WIDTH / 2, flow.next(0) + 24, FORM_WIDTH - PADDING * 4));

      int controlHeight = 16 + PADDING;
      int listY = flow.next(0);
      int listHeight = FORM_HEIGHT - listY - controlHeight - PADDING;

      FormContainerCraftingListContentBox craftingList = form.addComponent(
            new FormContainerCraftingListContentBox(
                  PADDING, listY, FORM_WIDTH - PADDING * 2, listHeight, client, false, false, false) {
               private final Supplier<Boolean> filterChanged = filter.addMonitor(this);
               private boolean craftabilityChanged;
               private List<Tech> knownBenches;

               @Override
               public Stream<ContainerRecipe> streamAllRecipes() {
                  List<ContainerRecipe> registered = container.streamRecipes(RecipeTechRegistry.ALL)
                        .filter(cr -> container.isRecipeAvailable(cr.recipe))
                        .filter(cr -> !StorageTerminalContainerForm.this.hiddenBenches.contains(cr.recipe.tech))
                        .collect(Collectors.toList());
                  List<ContainerRecipe> shown = filter.getFilteredRecipes(registered, container);

                  // An empty crafting list is ambiguous in a way an empty storage grid is not: it
                  // could mean the filter hid everything, or that no bench is installed. Saying
                  // which is the whole point -- a player who cannot tell assumes the tab is broken.
                  if (!shown.isEmpty()) {
                     emptyHint.setText("");
                  } else if (registered.isEmpty()) {
                     // Reachable by selecting a bench and then uninstalling it, and -- once modded
                     // stations exist -- by a bench whose recipes are all hidden.
                     emptyHint.setText(Localization.translate("ui", "arcanestorage_no_recipes"));
                  } else {
                     emptyHint.setText(Localization.translate("ui", "arcanestorage_no_recipes_shown",
                           "filter", Localization.translate("ui", "filteronlycraftable")));
                  }

                  return shown.stream();
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

                  // Polled rather than pushed. The obvious alternative -- a listener on the
                  // terminal's inventory -- would have to be unregistered when the form is disposed,
                  // and installing a bench is not a hot path: this is ten slot reads per frame
                  // against a list that is almost always identical. The container's own dirty-check
                  // cannot help here, because the station slots are deliberately kept out of the
                  // crafting pool.
                  List<Tech> sources = new ArrayList<>();
                  sources.add(RecipeTechRegistry.NONE);
                  sources.addAll(container.terminal.getInstalledTechs());

                  if (!sources.equals(this.knownBenches)) {
                     this.knownBenches = sources;
                     StorageTerminalContainerForm.this.rebuildBenchStrip(benchBox, sources);
                     this.updateRecipes();
                  }

                  if (StorageTerminalContainerForm.this.benchesChanged) {
                     StorageTerminalContainerForm.this.benchesChanged = false;
                     this.updateRecipes();
                  }

                  if (this.filterChanged.get() || membershipMayHaveChanged) {
                     this.updateRecipes();
                  }

                  super.draw(tickManager, perspective, renderBox);
               }

               /**
                * Lays the recipes out in collapsible category sections, the way a vanilla bench does.
                *
                * <p>Overriding {@code updateList} and nothing else is what makes this affordable.
                * Vanilla's own categorised view lives in {@code CraftingStationContainerForm}'s
                * {@code protected} inner classes, which cannot be instantiated from here -- but the
                * valuable part is not the layout, it is {@link FormContainerRecipe}: can-craft state,
                * the per-ingredient have/missing tooltip, the "3 of 5" count and click-to-craft. Those
                * components are built exactly as the base class builds them, including the canCraft
                * override, so everything above the positions is unchanged and
                * {@code forceUpdateCraftable} keeps working on the list this leaves behind.
                */
               @Override
               public void updateList() {
                  if (!ArcaneStorage.SETTINGS.groupCraftingByCategory) {
                     super.updateList();
                     return;
                  }

                  this.clearComponents();
                  Container c = client.getContainer();
                  this.recipeComponents = new ArrayList<>();

                  LinkedHashMap<ItemCategory, List<CraftableRecipe>> byCategory = new LinkedHashMap<>();
                  List<CraftableRecipe> shownRecipes = new ArrayList<>();
                  for (CraftableRecipe cr : this.allRecipes) {
                     if (cr.shouldShow) {
                        shownRecipes.add(cr);
                     }
                  }

                  // Sorted so sections keep a stable order between rebuilds; ItemCategory's own
                  // ordering is what the creative menu and the crafting benches use.
                  List<ItemCategory> ordered = new ArrayList<>();
                  for (CraftableRecipe cr : shownRecipes) {
                     ItemCategory category = craftingCategoryOf(cr.recipe);
                     if (!byCategory.containsKey(category)) {
                        byCategory.put(category, new ArrayList<>());
                        ordered.add(category);
                     }

                     byCategory.get(category).add(cr);
                  }

                  ordered.sort(null);

                  int availableWidth = this.getWidth() - this.getScrollBarWidth();
                  int elementWidth = RECIPE_ELEMENT_SIZE;
                  int perRow = Math.max(1, availableWidth / elementWidth);
                  int y = 0;

                  for (ItemCategory category : ordered) {
                     List<CraftableRecipe> recipes = byCategory.get(category);
                     ItemCategoryExpandedSetting setting = expanded.getChild(category);
                     boolean isExpanded = setting == null || setting.isExpanded();

                     // The header carries the count, so a collapsed section still says how much is
                     // inside -- otherwise collapsing hides the information you collapsed to find.
                     FormTextButton header = this.addComponent(new FormTextButton(
                           (isExpanded ? "- " : "+ ") + category.displayName.translate() + "  (" + recipes.size() + ")",
                           0, y, availableWidth, FormInputSize.SIZE_20, ButtonColor.BASE));
                     header.onClicked(event -> {
                        if (setting != null) {
                           setting.setExpanded(!setting.isExpanded());
                        }

                        this.updateList();
                     });

                     y += FormInputSize.SIZE_20.height + 2;
                     if (!isExpanded) {
                        continue;
                     }

                     for (int i = 0; i < recipes.size(); i++) {
                        CraftableRecipe cr = recipes.get(i);
                        FormContainerRecipe comp = this.addComponent(
                              new FormContainerRecipe(client, c, cr.recipe, 0, 0) {
                                 @Override
                                 public CanCraft getCanCraft() {
                                    return cr.canCraft;
                                 }
                              });
                        comp.setPosition(i % perRow * elementWidth + 2, y + i / perRow * elementWidth);
                        this.recipeComponents.add(comp);
                     }

                     y += (recipes.size() + perRow - 1) / perRow * elementWidth + 4;
                  }

                  this.setContentBox(new Rectangle(this.getWidth(), y));
                  WindowManager.getWindow().submitNextMoveEvent();
               }
            });

      // Unticked on every open: showing everything is the honest default, because a list narrowed to
      // what the network can build is indistinguishable from a tab that does not work yet -- which is
      // exactly what a player would see before installing their first bench.
      //
      // The label is vanilla's, so it reads the same here as it does at a bench even though the
      // state behind it is ours and transient.
      FormLocalCheckBox onlyCraftable = form.addComponent(
            new FormLocalCheckBox("ui", "filteronlycraftable", PADDING, FORM_HEIGHT - 16 - PADDING, false),
            100);
      onlyCraftable.onClicked(event -> filter.setCraftableOnly(event.from.checked));

      // This one *does* persist, unlike the filters, because it is a preference about how the
      // interface looks rather than a filter over what it shows -- you set it once and expect it to
      // stay. It goes through the engine's own mod settings file; saveClientSettings writes mod
      // settings too.
      FormLocalCheckBox groupByCategory = form.addComponent(
            new FormLocalCheckBox("ui", "arcanestorage_group_by_category", PADDING + 170,
                  FORM_HEIGHT - 16 - PADDING, ArcaneStorage.SETTINGS.groupCraftingByCategory),
            100);
      groupByCategory.onClicked(event -> {
         ArcaneStorage.SETTINGS.groupCraftingByCategory = event.from.checked;
         Settings.saveClientSettings();
         craftingList.updateList();
      });

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

      this.updateProblems();
      this.updateLogistics(this.client);

      // World marker disabled: see docs/QA_BACKLOG.md ("world marker for the selected logistics
      // device does not render"). The method, its diagnostics and the explanation of the shader
      // clip-state bug that was found are left in place for whoever picks this back up.
      // this.drawWorldMarker();
      super.draw(tickManager, perspective, renderBox);
   }


   /**
    * The logistics tab: what every bus on the network is doing, and the rules of whichever one is selected.
    *
    * <p>This exists because the notice it replaces did not work. A stopped device was reported by a single line
    * squeezed into the storage tab's category row, whose height is fixed, so the line could say how many devices
    * had stopped and where -- and nothing about why. A player who saw it still had to walk to each device.
    *
    * <p>Two jobs in one place, which is the point: the issues panel says what is wrong, and the device list is
    * also how rules are set, so the fix is where the diagnosis is. Rules written here go through the same
    * validation as rules written at the bus, deliberately -- there must be no way to reach a contradictory
    * configuration by choosing the more convenient of two interfaces.
    *
    * <p>The list is master-detail rather than a column of expanding rows. That is a departure from the shape
    * this was asked for, and the reason is the rules editor: it contains a scrolling category tree, and putting
    * one inside each row of another scrolling list means two nested scroll regions. A content box claims the
    * mouse over its whole rectangle once clicked, which is exactly how the Apply button came to be inert, and
    * nesting two of them invites the same class of fault where it is hardest to see. One bus at a time gives
    * the same two capabilities with one scroll region each.
    */
   private Form buildLogisticsTab(Client client, T container) {
      this.logisticsTabIndex = this.tabs.getTabCount();
      Form form = styled(this.tabs.addLocalizedTab(new LocalMessage("ui", "arcanestorage_tab_logistics"), null));

      FormFlow flow = new FormFlow(PADDING);
      int headerY = flow.next(FormInputSize.SIZE_24.height + PADDING);
      form.addComponent(new FormLocalLabel("ui", "arcanestorage_tab_logistics", new FontOptions(20), -1,
            PADDING, headerY + 4, FORM_WIDTH - PADDING * 2));

      // The issues area takes the height it needs and no more. It was a fixed four-line block of prose naming
      // every stopped device with its full reason, which is more text than fits: two stopped buses already
      // clipped it. A reason is long because it names an item, a place and another device, and none of that is
      // worth reading until you know which device you care about -- so the area now shows one box per stopped
      // device carrying only its name, and the reason is a hover away.
      this.issuesY = flow.next(0);
      this.issueBacking = form.addComponent(new FormColorFill(PADDING, this.issuesY,
            FORM_WIDTH - PADDING * 2, ISSUE_ROW, ISSUE_BACKING));
      this.issueLabel = form.addComponent(new FormLabel("", new FontOptions(ISSUE_FONT), -1,
            PADDING * 2, this.issuesY + PADDING, FORM_WIDTH - PADDING * 4 - COPY_BUTTON_WIDTH - PADDING));

      // Only worth offering when there is something to copy, and it is the whole set rather than one device:
      // somebody asking for help wants to paste all of it at once. Added and removed rather than hidden --
      // FormComponent has no hidden flag, only Form does, and a button left in place would still take clicks.
      this.issueBoxGroup = form.addComponent(new Form(FORM_WIDTH - PADDING * 2, ISSUE_ROW));
      this.issueBoxGroup.drawBase = false;
      this.issueBoxGroup.setPosition(PADDING * 2, this.issuesY + ISSUE_ROW);

      this.contentTop = this.issuesY + ISSUE_ROW + PADDING;
      this.contentHeight = FORM_HEIGHT - this.contentTop - PADDING;
      this.deviceListBox = form.addComponent(
            new FormContentBox(PADDING, this.contentTop, DEVICE_LIST_WIDTH, this.contentHeight));

      return form;
   }

   /**
    * Lays out one box per stopped device, wrapped across as many rows as they need.
    *
    * <p>Rebuilt only when the set of stopped devices changes, which is what the caller's signature check is
    * for: this allocates buttons and measures text, and the tab is redrawn every frame.
    *
    * @return the height the issues area now occupies
    */
   private int rebuildIssues(List<BusSummary> buses) {
      for (FormTextButton box : this.issueBoxes) {
         this.issueBoxGroup.removeComponent(box);
      }

      this.issueBoxes.clear();

      StringBuilder clipboard = new StringBuilder();
      int stopped = 0;
      int available = FORM_WIDTH - PADDING * 4;
      int x = 0;
      int y = 0;
      FontOptions boxFont = new FontOptions(ISSUE_FONT);

      for (BusSummary bus : buses) {
         if (bus.state.isActive()) {
            continue;
         }

         stopped++;
         String reason = bus.name() + " " + bus.where() + " - " + bus.message();
         if (clipboard.length() > 0) {
            clipboard.append('\n');
         }

         clipboard.append(reason);

         // Sized to its own name, so the row packs as many as fit rather than reserving a column width for
         // the longest. Clamped to the available width, because a player may name a bus anything.
         int width = Math.min(available,
               FontManager.bit.getWidthCeil(bus.name(), boxFont) + ISSUE_BOX_PADDING);
         if (x > 0 && x + width > available) {
            x = 0;
            y += ISSUE_ROW;
         }

         long key = StorageTerminalContainer.key(bus.tileX, bus.tileY);
         FormTextButton box = this.issueBoxGroup.addComponent(new FormTextButton(
               bus.name(), reason, x, y, width, FormInputSize.SIZE_20, ButtonColor.RED));

         // Clicking the problem selects the device, so the fix is one click from the diagnosis rather than a
         // hunt down the list for a name you just read.
         box.onClicked(e -> this.selectDevice(key));
         this.issueBoxes.add(box);
         x += width + PADDING;
      }

      this.issueClipboardText = clipboard.toString();
      this.issueBacking.visible = stopped > 0;

      // Added and removed rather than hidden: FormComponent has no hidden flag, and a button left in place
      // would still take a click over the space where nothing is drawn.
      if (stopped == 0 && this.copyButton != null) {
         this.logisticsForm.removeComponent(this.copyButton);
         this.copyButton = null;
      } else if (stopped > 0 && this.copyButton == null) {
         this.copyButton = this.logisticsForm.addComponent(new FormLocalTextButton("ui",
               "arcanestorage_copy_issues", FORM_WIDTH - PADDING * 2 - COPY_BUTTON_WIDTH, this.issuesY + 2,
               COPY_BUTTON_WIDTH, FormInputSize.SIZE_20, ButtonColor.BASE));
         this.copyButton.setTooltip(Localization.translate("ui", "arcanestorage_copy_issues_tip"));
         this.copyButton.onClicked(e -> {
            GameWindow window = WindowManager.getWindow();
            if (window != null) {
               window.putClipboard(this.issueClipboardText);
            }
         });
      }

      this.issueLabel.setText(stopped == 0
            ? Localization.translate("ui", "arcanestorage_no_issues")
            : Localization.translate("ui", "arcanestorage_stopped_count", "count", String.valueOf(stopped)),
            FORM_WIDTH - PADDING * 4 - (stopped == 0 ? 0 : COPY_BUTTON_WIDTH + PADDING));

      int boxRows = stopped == 0 ? 0 : y / ISSUE_ROW + 1;
      int height = ISSUE_ROW + boxRows * ISSUE_ROW + (stopped == 0 ? 0 : PADDING);
      this.issueBoxGroup.setHeight(Math.max(1, boxRows * ISSUE_ROW));
      this.issueBacking.setSize(FORM_WIDTH - PADDING * 2, height);
      return height;
   }

   /**
    * Moves the device list and the rules pane to sit under an issues area of the given height.
    *
    * <p>The lists shrink rather than the issues area being capped, because a stopped device is the reason a
    * player opened this tab: it gets the room it needs and the configuration below gives it up.
    */
   private void reflowLogistics(int issuesHeight) {
      int top = this.issuesY + issuesHeight + PADDING;
      if (top == this.contentTop) {
         return;
      }

      this.contentTop = top;
      this.contentHeight = FORM_HEIGHT - top - PADDING;
      this.deviceListBox.setY(top);
      this.deviceListBox.setHeight(this.contentHeight);
      if (this.devicePane != null) {
         this.devicePane.setPosition(this.devicePane.getX(), top);
         this.devicePane.setHeight(this.contentHeight);
         if (this.devicePaneBox != null) {
            this.devicePaneBox.setHeight(this.contentHeight - PADDING);
         }
      }
   }

   /**
    * Rebuilds the device list, and only when the network has changed.
    *
    * <p>A row per bus, whether or not it is working, because this is where rules are set as well as where
    * problems are read -- a list that showed a device only once it broke would be a strange place to go and
    * configure one. A stopped device's row is red, which is the same signal as its sprite going grey: the
    * player learns one thing, not two.
    */
   private void rebuildDeviceList(List<BusSummary> buses) {
      for (FormTextButton button : this.deviceButtons) {
         this.deviceListBox.removeComponent(button);
      }

      this.deviceButtons.clear();

      for (int i = 0; i < buses.size(); i++) {
         BusSummary bus = buses.get(i);
         long key = StorageTerminalContainer.key(bus.tileX, bus.tileY);
         FormTextButton row = this.deviceListBox.addComponent(new FormTextButton(
               bus.name(), 0, i * DEVICE_ROW_PITCH,
               DEVICE_LIST_WIDTH - this.deviceListBox.getScrollBarWidth() - 2, FormInputSize.SIZE_24,
               bus.state.isActive() ? ButtonColor.BASE : ButtonColor.RED));
         row.onClicked(e -> this.selectDevice(key));
         this.deviceButtons.add(row);
      }

      this.deviceListBox.setContentBox(new Rectangle(
            DEVICE_LIST_WIDTH - this.deviceListBox.getScrollBarWidth(), buses.size() * DEVICE_ROW_PITCH));
   }

   /**
    * Points the right-hand pane at a bus, asking the server for its rules if they are not already here.
    *
    * <p>Rules are fetched per bus rather than sent with the terminal's summary: a filter is a category tree
    * with per-item entries, bus counts are not bounded, and a player who opened the terminal to look at storage
    * should not pay for every bus on the network.
    */
   private void selectDevice(long key) {
      if (this.selectedDevice == key) {
         return;
      }

      this.selectedDevice = key;
      this.getContainer().refusal = null;
      if (!this.getContainer().rules.containsKey(key)) {
         this.getContainer().requestRulesAction.runAndSend((int)(key >> 32), (int)key);
      }
   }

   /**
    * Builds the rules pane for the selected bus, or the hint that stands in for it.
    *
    * <p>Rebuilt rather than repointed, because the editor binds to one filter object at construction. The
    * whole pane is one nested form so that replacing it is a single remove.
    */
   private void rebuildDevicePane(Client client, List<BusSummary> buses) {
      if (this.devicePane != null) {
         this.logisticsForm.removeComponent(this.devicePane);
         this.devicePane = null;
         this.devicePaneBox = null;
         this.deviceRules = null;
      }

      this.paneBuiltFor = this.selectedDevice;

      int paneX = PADDING * 2 + DEVICE_LIST_WIDTH;
      int paneY = this.contentTop;
      int paneWidth = FORM_WIDTH - paneX - PADDING;
      int paneHeight = this.contentHeight;

      Form pane = this.logisticsForm.addComponent(new Form(paneWidth, paneHeight));
      pane.setPosition(paneX, paneY);
      this.devicePane = pane;

      BusSummary selected = null;
      for (BusSummary bus : buses) {
         if (StorageTerminalContainer.key(bus.tileX, bus.tileY) == this.selectedDevice) {
            selected = bus;
            break;
         }
      }

      ItemCategoriesFilter filter = this.getContainer().rules.get(this.selectedDevice);
      if (selected == null || filter == null) {
         // Covers three cases with one sentence, all of which mean "nothing to edit yet": nothing picked, the
         // rules still in flight, and a device that has left the network while its rules were on the way.
         pane.addComponent(new FormLocalLabel("ui",
               this.selectedDevice == NO_DEVICE ? "arcanestorage_pick_device" : "arcanestorage_fetching_rules",
               new FontOptions(16), -1, PADDING, PADDING * 2, paneWidth - PADDING * 2));
         return;
      }

      // The whole pane scrolls, rather than the category tree scrolling inside a pane that is too short for
      // the editor around it. Previously only the tree had a bar, so the amount row, the search box and the
      // Apply button were squeezed into whatever the pane had left -- and the pane is shorter than the bus's
      // own panel, which is what the editor's proportions were chosen against. One bar over everything also
      // avoids two scroll regions a few pixels apart, and the nested hit-testing hazard that comes with them.
      FormContentBox box = pane.addComponent(
            new FormContentBox(0, PADDING, paneWidth, paneHeight - PADDING));
      this.devicePaneBox = box;

      // No title label, and no message label either: the editor's first row is the name and its second is the
      // status line, so the pane has nothing of its own to draw. The coordinates that used to sit here are on
      // the device's own box in the issues area, which is where a player who has to go and find the thing is
      // looking.
      final BusSummary bus = selected;
      final int contentWidth = paneWidth - box.getScrollBarWidth();
      this.deviceRules = BusRulesEditor.addTo(box, client, filter,
            bus.importing ? "arcanestorage_importbuslimit" : "arcanestorage_exportbuslimit",
            "arcanestoragebus", new Rectangle(0, 0, contentWidth, paneHeight - PADDING),
            bus.name(),
            renamed -> this.getContainer().setNameAction.runAndSend(bus.tileX, bus.tileY, renamed),
            edited -> {
               this.getContainer().refusal = null;
               this.getContainer().setRulesAction.runAndSend(bus.tileX, bus.tileY, edited);
            },
            BusRulesEditor.Scroll.HOST_SCROLLS_ALL,
            () -> {
               if (this.deviceRules != null && this.devicePaneBox != null) {
                  this.devicePaneBox.setContentBox(
                        new Rectangle(contentWidth, this.deviceRules.getNaturalHeight() + PADDING));
               }
            });
   }

   /**
    * Marks the selected device in the world, so a player can see which one they are configuring.
    *
    * <p>The tab can only name a device, and a name is something the player chose or a number this mod handed
    * out -- neither tells them which of the boxes in front of them it is. This draws over the tile itself.
    *
    * <p>Drawn from this form rather than through the level's HUD manager, which was the first attempt. The HUD
    * route is what the settlement work-zone tool uses and it is the tidier-looking answer, but it made three
    * things true at once that all had to be right before anything appeared: an element registered from a
    * constructor whose level might not be set yet, a sort priority whose meaning is not local to this file, and
    * a lifetime managed by hand. A form's draw runs after the world is drawn, every frame, for exactly as long
    * as the panel is open -- so drawing here needs none of those to be true and cannot be silently skipped.
    *
    * <p>The engine's own zone highlight is not used either: {@code Zoning.addRectangleDrawOptions} feathers its
    * edge by 16px and clamps that to half the rectangle, so on a single 32px tile the fill collapses to nothing
    * and all that survives is four gradient corners. It is built for zones many tiles across. Four thin quads
    * give a crisp outline at this size.
    */
   private void drawWorldMarker() {
      // Unconditional and throttled by wall time rather than by selection, so a run that never selects a
      // device still shows whether this method is being called at all -- the previous version of this trace
      // fired only after a selection, and could not tell "never called" apart from "called, drew nothing".
      long now = System.currentTimeMillis();
      if (now - this.markerLastTraced > 2000) {
         this.markerLastTraced = now;
         GameLog.debug.println("Arcane Storage: drawWorldMarker called, markerX=" + this.markerX
               + " markerY=" + this.markerY + " selectedDevice=" + this.selectedDevice
               + " currentTab=" + this.tabs.getCurrentTabIndex() + " logisticsTab=" + this.logisticsTabIndex);
      }

      if (this.markerX < 0 || this.markerY < 0) {
         return;
      }

      GameCamera camera = GlobalData.getCurrentState() == null ? null : GlobalData.getCurrentState().getCamera();
      GameWindow window = WindowManager.getWindow();
      if (camera == null || window == null || window.getSceneWidth() <= 0) {
         // The first attempt at this diagnostic coupled its own condition to the unrelated trace above and
         // never actually fired -- fixed to log once per throttle window regardless, on its own timer.
         if (now - this.markerGuardLastTraced > 2000) {
            this.markerGuardLastTraced = now;
            GameLog.debug.println("Arcane Storage: drawWorldMarker stopped by its guard: camera=" + camera
                  + " window=" + window + " sceneWidth="
                  + (window == null ? "?" : String.valueOf(window.getSceneWidth())));
         }

         return;
      }

      // The world and the interface are rendered into two different buffers, at two different sizes: the level
      // goes into the scene buffer and everything drawn from a form into the hud buffer. A tile position minus
      // the camera is a scene coordinate, and using it here without converting puts the marker somewhere else
      // entirely -- which is what the first version of this did, and why nothing appeared.
      double toHudX = (double)window.getHudWidth() / window.getSceneWidth();
      double toHudY = (double)window.getHudHeight() / window.getSceneHeight();
      int x = (int)Math.round((this.markerX * 32 - camera.getX()) * toHudX);
      int y = (int)Math.round((this.markerY * 32 - camera.getY()) * toHudY);
      int width = (int)Math.round(32 * toHudX);
      int height = (int)Math.round(32 * toHudY);
      int edge = Math.max(MARKER_EDGE, (int)Math.round(MARKER_EDGE * toHudX));

      // Between a third and full strength, about once a second. Enough to catch the eye without being the
      // brightest thing on screen while somebody is trying to read the panel in front of it.
      double phase = (Math.sin(System.currentTimeMillis() / 160.0) + 1.0) / 2.0;
      int alpha = (int)(80 + phase * 150);
      Color edgeColor = new Color(170, 220, 255, alpha);

      // Once per marked device, because this is drawing outside any component's box using numbers from two
      // coordinate spaces, and neither the position nor the conversion can be seen in a headless test. If it is
      // ever invisible again, this line says whether the code ran and where it put it.
      if (this.markerLogged != this.selectedDevice) {
         this.markerLogged = this.selectedDevice;
         GameLog.debug.println("Arcane Storage: marking " + this.markerX + "," + this.markerY + " at hud "
               + x + "," + y + " size " + width + "x" + height + "; scene " + window.getSceneWidth() + "x"
               + window.getSceneHeight() + ", hud " + window.getHudWidth() + "x" + window.getHudHeight()
               + ", camera " + camera.getX() + "," + camera.getY());
      }

      // The real fault, found by reading FormShader rather than guessing again: every Form.draw() pushes a
      // shader state carrying an offset and a draw-limit rectangle, and startState *intersects* that rectangle
      // with whatever was already active rather than replacing it -- so drawing from here, right after one
      // form's state has ended and before the next has started, inherits whatever rectangle was left current.
      // That is usually some other component's small clip box, which silently clips these quads to nothing.
      // There is no exception, because clipping is not an error.
      //
      // The fix is the same one Form.draw() itself uses: push an explicit state before drawing and pop it
      // after, rather than drawing into whatever state happens to be active. Offset zero and the full hud
      // buffer as the limit is the root state -- the one FormShader.use() itself establishes once per frame.
      FormShader.FormShaderState state = GameResources.formShader.startState(
            new Point(0, 0), new Rectangle(0, 0, window.getHudWidth(), window.getHudHeight()));
      try {
         Renderer.initQuadDraw(width, height).color(new Color(90, 160, 255, alpha / 4)).draw(x, y);
         Renderer.initQuadDraw(width, edge).color(edgeColor).draw(x, y);
         Renderer.initQuadDraw(width, edge).color(edgeColor).draw(x, y + height - edge);
         Renderer.initQuadDraw(edge, height - edge * 2).color(edgeColor).draw(x, y + edge);
         Renderer.initQuadDraw(edge, height - edge * 2).color(edgeColor).draw(x + width - edge, y + edge);
      } catch (Throwable t) {
         if (this.markerDrawFailed != this.selectedDevice) {
            this.markerDrawFailed = this.selectedDevice;
            GameLog.err.println("Arcane Storage: drawWorldMarker's draw calls threw: " + t);
            t.printStackTrace(GameLog.err);
         }
      } finally {
         state.end();
      }
   }

   /**
    * Keeps the logistics tab current: the issues, the list, and which bus the pane is showing.
    *
    * <p>Per frame rather than on an event, because everything here arrives without the panel doing anything --
    * a device stops, a rule set is refused, a bus is broken by a passing mob.
    */
   private void updateLogistics(Client client) {
      List<BusSummary> buses = this.getContainer().terminal == null
            ? new ArrayList<>()
            : this.getContainer().terminal.getBuses();

      StringBuilder signature = new StringBuilder();
      StringBuilder issueSignature = new StringBuilder();
      for (BusSummary bus : buses) {
         signature.append(bus.tileX).append(',').append(bus.tileY).append(bus.state)
               .append(bus.ordinal).append(bus.customName).append(';');
         if (!bus.state.isActive()) {
            issueSignature.append(bus.tileX).append(',').append(bus.tileY).append(bus.state)
                  .append(bus.conflictItemID).append(bus.ordinal).append(bus.customName).append(';');
         }
      }

      // The issues area allocates buttons and measures text, so it is rebuilt only when the set of stopped
      // devices changes -- not every frame, which is how often this runs.
      if (!issueSignature.toString().equals(this.shownIssues)) {
         this.shownIssues = issueSignature.toString();
         this.reflowLogistics(this.rebuildIssues(buses));
      }

      if (!signature.toString().equals(this.shownDevices)) {
         this.shownDevices = signature.toString();
         this.rebuildDeviceList(buses);
      }

      // The pane is also rebuilt when rules arrive for the bus already selected, which is the ordinary case:
      // selecting sends a request and the answer lands a round trip later.
      boolean rulesArrived = this.deviceRules == null
            && this.getContainer().rules.containsKey(this.selectedDevice);
      if (this.paneBuiltFor != this.selectedDevice || rulesArrived || this.devicePane == null) {
         this.rebuildDevicePane(client, buses);
      }

      if (this.deviceRules != null) {
         BusSummary selected = null;
         for (BusSummary bus : buses) {
            if (StorageTerminalContainer.key(bus.tileX, bus.tileY) == this.selectedDevice) {
               selected = bus;
               break;
            }
         }

         // Same precedence as the bus's own panel: a refusal answers what the player just did, a stopped state
         // is a standing fact that will still be there afterwards.
         String message = "";
         if (this.getContainer().refusal != null) {
            message = GameColor.RED.getColorCode() + this.getContainer().refusal;
         } else if (selected != null && !selected.state.isActive()) {
            message = GameColor.RED.getColorCode() + selected.message();
         } else if (this.deviceRules.hasUnappliedEdits()) {
            message = Localization.translate("ui", "arcanestorage_unapplied");
         }

         // The editor owns its status line and reflows around it, in both surfaces, so the pane only has to
         // keep its scroll extent in step with the result.
         this.deviceRules.setStatus(message);
         if (this.deviceRules.consumeHeightChanged() && this.devicePaneBox != null) {
            this.devicePaneBox.setContentBox(new Rectangle(
                  this.devicePane.getWidth() - this.devicePaneBox.getScrollBarWidth(),
                  this.deviceRules.getNaturalHeight() + PADDING));
         }
      }
   }

   /**
    * Shows how many devices have stopped, and why, from the terminal's synced summary.
    *
    * <p>Per frame rather than once, because the reason a device stopped is usually a rule the player is about
    * to change: the line has to clear itself when they fix it, without reopening anything.
    */
   private void updateProblems() {
      // Still on the storage tab as well as in the logistics tab, because this is the line a player sees
      // without going looking. It now points at the tab that explains rather than trying to explain itself in
      // a row whose height is fixed -- which is what made it a poor notice.
      int stopped = 0;
      if (this.getContainer().terminal != null) {
         for (BusSummary summary : this.getContainer().terminal.getBuses()) {
            if (!summary.state.isActive()) {
               stopped++;
            }
         }
      }

      this.problemsLabel.setText(stopped == 0 ? "" : GameColor.RED.getColorCode()
            + Localization.translate("ui", "arcanestorage_problems", "count", String.valueOf(stopped)));
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
