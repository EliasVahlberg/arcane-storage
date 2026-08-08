package arcanestorage.container;

import necesse.engine.network.client.Client;
import necesse.engine.window.GameWindow;
import necesse.gfx.forms.ContainerComponent;
import necesse.gfx.forms.Form;
import necesse.gfx.forms.components.FormFlow;
import necesse.gfx.forms.components.containerSlot.FormContainerSlot;
import necesse.gfx.forms.components.localComponents.FormLocalLabel;
import necesse.gfx.forms.presets.containerComponent.ContainerFormSwitcher;
import necesse.gfx.gameFont.FontOptions;

/**
 * Client-side UI for the Storage Terminal. Step 1 draws a plain chest grid; the
 * aggregated view replaces the slot grid in step 3.
 */
public class StorageTerminalContainerForm<T extends StorageTerminalContainer> extends ContainerFormSwitcher<T> {

   private static final int SLOT_SIZE = 40;
   private static final int SLOTS_PER_ROW = 10;
   private static final int PADDING = 4;

   public final Form mainForm = this.addComponent(new Form(PADDING * 2 + SLOTS_PER_ROW * SLOT_SIZE, 100));
   public final FormContainerSlot[] slots;

   public StorageTerminalContainerForm(Client client, T container) {
      super(client, container);
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

      int slotCount = container.TERMINAL_END - container.TERMINAL_START + 1;
      this.slots = new FormContainerSlot[slotCount];
      int rowY = flow.next();

      for (int i = 0; i < slotCount; i++) {
         int column = i % SLOTS_PER_ROW;
         if (column == 0) {
            rowY = flow.next(SLOT_SIZE);
         }

         this.slots[i] = this.mainForm
            .addComponent(new FormContainerSlot(client, container, container.TERMINAL_START + i, PADDING + column * SLOT_SIZE, rowY));
      }

      flow.next(PADDING);
      this.mainForm.setHeight(flow.next());
      this.makeCurrent(this.mainForm);
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
