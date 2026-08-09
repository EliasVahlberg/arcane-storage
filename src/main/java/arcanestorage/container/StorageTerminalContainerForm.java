package arcanestorage.container;

import java.awt.Rectangle;
import java.util.List;

import necesse.engine.gameLoop.tickManager.TickManager;
import necesse.engine.input.Control;
import necesse.engine.input.InputEvent;
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
import necesse.gfx.forms.components.FormLabel;
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
import necesse.inventory.item.ItemSearchTester;

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

   private static final int CELL_SIZE = 36;
   private static final int COLUMNS = 10;
   private static final int ROWS = 6;
   private static final int PADDING = 4;
   private static final int SEARCH_WIDTH = 160;

   public final Form mainForm;
   public final FormItemList itemList;
   public final FormTextInput searchInput;
   public final FormLabel capacityLabel;

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
    * Signature of the aggregated list the grid was last built from. The list only populates
    * when empty, so it has to be rebuilt deliberately — but rebuilding every frame would
    * reset the player's scroll position, so it is rebuilt only when the contents change.
    */
   private long shownSignature = Long.MIN_VALUE;

   public StorageTerminalContainerForm(Client client, T container) {
      super(client, container);
      int width = PADDING * 2 + COLUMNS * CELL_SIZE;
      this.mainForm = this.addComponent(new Form(width, 100));

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

      this.itemList = this.mainForm
         .addComponent(
            new FormItemList(PADDING, flow.next(ROWS * CELL_SIZE), COLUMNS * CELL_SIZE, ROWS * CELL_SIZE, FormItemList.UpdateMode.WAIT_FULl) {
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
                  for (InventoryItem item : container.getAggregatedItems()) {
                     if (StorageTerminalContainerForm.this.searchTester.matches(item, client.getPlayer(), blackboard)) {
                        list.add(item);
                     }
                  }
               }

               @Override
               public void onItemClicked(InventoryItem item, InputEvent event) {
                  // Follow normal inventory conventions: plain click picks up onto the
                  // cursor, INV_QUICK_MOVE (shift by default) transfers into the inventory.
                  boolean quickMove = Control.INV_QUICK_MOVE.isDown();
                  container.withdrawAction
                     .runAndSend(item, Math.min(item.getAmount(), item.item.getStackSize()), !quickMove);
                  event.use();
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

      // Capacity sits on the same row, in slots, because slots are what run out. A label rather
      // than a bar: a bar needs art and reads as decorative, while "312 / 480 slots" is the
      // number a player needs when deciding whether to place another unit.
      this.capacityLabel = this.mainForm
         .addComponent(new FormLabel("", new FontOptions(12), -1, PADDING, controlY + 6));
      this.updateCapacityLabel();

      this.mainForm.setHeight(flow.next(PADDING));
      this.makeCurrent(this.mainForm);

      // Must happen before the form can receive input, not lazily in draw().
      // FormItemList.reset() does not call super.reset(), so FormGeneralList.elements stays
      // null from construction until the first updateList — and input events are handled
      // earlier in the frame than any draw, so a click on the first frame would hit null.
      this.refreshList();
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
   private void updateCapacityLabel() {
      T container = this.getContainer();
      this.capacityLabel
         .setText(
            Localization
               .translate(
                  "ui",
                  "arcanestorage_capacity",
                  "used",
                  String.valueOf(container.getUsedSlots()),
                  "total",
                  String.valueOf(container.getTotalSlots())
               )
         );
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
      this.shownSignature = signatureOf(this.getContainer().getAggregatedItems());
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
      if (signatureOf(this.getContainer().getAggregatedItems()) != this.shownSignature) {
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
      ContainerComponent.setPosFocus(this.mainForm);
   }

   @Override
   public boolean shouldOpenInventory() {
      return true;
   }
}
