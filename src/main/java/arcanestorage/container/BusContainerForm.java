package arcanestorage.container;

import java.awt.Rectangle;

import arcanestorage.objectentity.BusObjectEntity;
import necesse.engine.localization.Localization;
import necesse.engine.network.client.Client;
import necesse.gfx.forms.components.FormFlow;
import necesse.gfx.forms.components.FormInputSize;
import necesse.gfx.forms.components.FormLabel;
import necesse.gfx.forms.presets.ItemCategoriesFilterForm;
import necesse.gfx.forms.presets.containerComponent.ContainerForm;
import necesse.engine.gameLoop.tickManager.TickManager;
import necesse.entity.mobs.PlayerMob;
import necesse.engine.GameLog;
import necesse.gfx.GameColor;
import necesse.gfx.gameFont.FontOptions;
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

   /** The state and refusal line: its font, and how many wrapped lines the layout keeps clear for it. */
   private static final int STATE_FONT = 12;

   private static final int STATE_LINES = 3;

   public final ItemCategoriesFilterForm filterForm;

   private final BusRulesEditor rules;

   /**
    * Why the bus has stopped, or empty when it has not.
    *
    * <p>Refreshed while the panel is open rather than fixed at construction, because the reason a bus stopped
    * is usually a rule the player is editing right now: they should see it clear as they fix it.
    */
   private final FormLabel stateLabel;

   public BusContainerForm(Client client, T container, String nameKey, String explanationKey, String limitKey) {
      super(client, WIDTH, HEIGHT, container);
      final ItemCategoriesFilter filter = container.filter;
      FormFlow flow = new FormFlow(5);

      FormLabel title = new FormLabel(
         Localization.translate("object", nameKey), new FontOptions(20), -1, 6, 0);
      this.addComponent(flow.nextY(title, 4));

      FormLabel explanation = new FormLabel(
         Localization.translate("ui", explanationKey), new FontOptions(12), -1, 6, 0);
      this.addComponent(flow.nextY(explanation, 6));

      // A fixed block, reserved whether or not anything is wrong. The first version passed the empty label to
      // flow.nextY, which advances by a component's height *as it is then* -- and an empty label is zero lines
      // tall, so nothing was reserved and the text landed on the amount row below. Nothing here may depend on
      // the length of a message that is not set yet.
      int stateY = flow.next(STATE_FONT * STATE_LINES + 4);
      this.stateLabel = new FormLabel("", new FontOptions(STATE_FONT), -1, 6, stateY, WIDTH - 12);
      this.addComponent(this.stateLabel);

      // Measured against the longest thing that can appear there, with the widest plausible substitutions.
      // The reason names an item and a pair of coordinates, so its length is not under this file's control.
      FormLabel worstCase = new FormLabel(Localization.translate("ui", "arcanestorage_refused",
            "item", "Pearlescent Diamond Broadsword", "x", "-12345", "y", "-12345"),
            new FontOptions(STATE_FONT), -1, 0, 0, WIDTH - 12);
      if (worstCase.getHeight() > STATE_FONT * STATE_LINES) {
         GameLog.warn.println("Arcane Storage: the bus panel reserves " + STATE_LINES
               + " lines for its state line but the longest refusal needs "
               + (worstCase.getHeight() / STATE_FONT) + "; it will overlap the amount row.");
      }

      // Both surfaces that edit a bus's rules -- this panel and the terminal's logistics tab -- build the
      // same editor, so neither can drift from the other or from the validation both must obey.
      //
      // The mode dropdown the settlement panel puts beside its number stays absent: its four modes describe a
      // container, and two of them cap a container's entire item count, which for a network means "the network
      // may hold 20 things in total" -- zero headroom in any real network, so an import bus stops dead and an
      // export bus treats everything as surplus. Both were observed in game.
      int rulesY = flow.next(0);
      this.rules = BusRulesEditor.addTo(this, client, filter, limitKey, "arcanestoragebus",
            new Rectangle(0, rulesY, WIDTH, HEIGHT - rulesY), f -> {
               this.container.refusal = null;
               this.container.setFilterAction.runAndSend(f);
            });
      this.filterForm = this.rules.filterForm;
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
      } else if (this.rules.hasUnappliedEdits()) {
         message = Localization.translate("ui", "arcanestorage_unapplied");
      }

      // The wrap width goes with every set: setText(String) delegates to setText(text, -1), which wraps at
      // Integer.MAX_VALUE -- one long line off the side of the panel, which is what this used to draw.
      this.stateLabel.setText(message, WIDTH - 12);
      super.draw(tickManager, perspective, renderBox);
   }

}
