package arcanestorage.container;

import java.awt.Rectangle;
import java.util.List;

import necesse.engine.input.InputEvent;
import necesse.engine.network.client.Client;
import necesse.engine.gameLoop.tickManager.TickManager;
import necesse.engine.window.GameWindow;
import necesse.entity.mobs.PlayerMob;
import necesse.gfx.forms.ContainerComponent;
import necesse.gfx.forms.Form;
import necesse.gfx.forms.components.FormFlow;
import necesse.gfx.forms.components.lists.FormItemList;
import necesse.gfx.forms.components.localComponents.FormLocalLabel;
import necesse.gfx.forms.presets.containerComponent.ContainerFormSwitcher;
import necesse.gfx.gameFont.FontOptions;
import necesse.inventory.InventoryItem;

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

   public final Form mainForm;
   public final FormItemList itemList;

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
      this.mainForm
         .addComponent(
            flow.nextY(
               new FormLocalLabel(
                  container.terminal.getInventoryName(),
                  new FontOptions(20),
                  0,
                  this.mainForm.getWidth() / 2,
                  0,
                  this.mainForm.getWidth() - PADDING * 2
               ),
               PADDING
            )
         );

      this.itemList = this.mainForm
         .addComponent(
            new FormItemList(PADDING, flow.next(ROWS * CELL_SIZE), COLUMNS * CELL_SIZE, ROWS * CELL_SIZE, FormItemList.UpdateMode.CONCURRENT_CONTINUOUS) {
               @Override
               public void addAllItems(List<InventoryItem> list) {
                  list.addAll(container.getAggregatedItems());
               }

               @Override
               public void onItemClicked(InventoryItem item, InputEvent event) {
                  // Step 4. Withdrawing has to be a server-validated custom action, not a
                  // client-side inventory edit, so there is deliberately nothing here yet.
               }
            }
         );

      flow.next(PADDING);
      this.mainForm.setHeight(flow.next());
      this.makeCurrent(this.mainForm);
   }

   /**
    * Rebuilds the grid when the network's contents change. The slots behind the list are
    * real and engine-synced, so a unit edited by anything else — another player, a broken
    * unit, a settler — shows up here without any extra messaging.
    */
   @Override
   public void draw(TickManager tickManager, PlayerMob perspective, Rectangle renderBox) {
      long signature = signatureOf(this.getContainer().getAggregatedItems());
      if (signature != this.shownSignature) {
         this.shownSignature = signature;
         this.itemList.reset();
      }

      this.itemList.populateIfNotAlready();
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
