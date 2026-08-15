package arcanestorage.container;

import java.awt.Rectangle;

import arcanestorage.objectentity.BusObjectEntity;
import arcanestorage.ui.ArcanePanel;
import arcanestorage.ui.ArcaneStyles;
import necesse.engine.localization.Localization;
import necesse.engine.network.client.Client;
import necesse.gfx.forms.components.FormFlow;
import necesse.gfx.forms.components.FormInputSize;
import necesse.gfx.forms.components.FormLabel;
import necesse.gfx.forms.presets.ItemCategoriesFilterForm;
import necesse.gfx.forms.ContainerComponent;
import necesse.gfx.forms.presets.containerComponent.ContainerForm;
import necesse.engine.gameLoop.tickManager.TickManager;
import necesse.entity.mobs.PlayerMob;
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

   /**
    * The panel's height with nothing wrong.
    *
    * <p>It grows from here when the status line wraps, rather than reserving room for the longest message a
    * device can produce. The reservation approach was wrong twice -- it was sized in whole lines against a
    * message whose length is not under this file's control, since it names an item, a pair of coordinates and
    * a name the player chose -- and it cost every panel that space even when nothing was wrong.
    */
   private static final int BASE_HEIGHT = 420 + BusRulesEditor.NAME_ROW_HEIGHT;

   public final ItemCategoriesFilterForm filterForm;

   private final BusRulesEditor rules;

   /** The height the window is currently set to, so it is only re-anchored when it actually changes. */
   private int shownHeight = BASE_HEIGHT;

   /** Where the editor starts, so the window's height can be measured from the same origin the editor uses. */
   private final int rulesTop;

   public BusContainerForm(Client client, T container, String nameKey, String explanationKey, String limitKey) {
      super(client, WIDTH, BASE_HEIGHT, container);
      final ItemCategoriesFilter filter = container.filter;
      FormFlow flow = new FormFlow(5);

      // No title label: the name row at the top of the editor is the title, and it is editable. A device is
      // addressed by coordinates, which a player cannot relate to anything they can see, so the name is their
      // only handle on which bus this is -- and the same row appears in the terminal's logistics tab.
      FormLabel explanation = new FormLabel(
         Localization.translate("ui", explanationKey), new FontOptions(12), -1, 6, 0);
      // The same panel as the terminal, so a bus's rules read as the same interface reached a different
      // way rather than as a vanilla dialogue that happens to be nearby.
      // Before a single component is added: ComponentList.add copies the parent's style at add time, so a
      // component added first would keep the player's global style and the window would end up half in each.
      ArcaneStyles.apply(this);
      this.setBackground(ArcanePanel.of());

      this.addComponent(flow.nextY(explanation, 6));

      // Both surfaces that edit a bus's rules -- this panel and the terminal's logistics tab -- build the
      // same editor, so neither can drift from the other or from the validation both must obey. The status
      // line belongs to the editor too, directly under the name row, for the same reason.
      //
      // The mode dropdown the settlement panel puts beside its number stays absent: its four modes describe a
      // container, and two of them cap a container's entire item count, which for a network means "the network
      // may hold 20 things in total" -- zero headroom in any real network, so an import bus stops dead and an
      // export bus treats everything as surplus. Both were observed in game.
      int rulesY = flow.next(0);
      this.rulesTop = rulesY;
      this.rules = BusRulesEditor.addTo(this, client, filter, limitKey, "arcanestoragebus",
            new Rectangle(0, rulesY, WIDTH, BASE_HEIGHT - rulesY),
            container.bus == null ? Localization.translate("object", nameKey) : container.bus.name(),
            container.setNameAction::runAndSend,
            f -> {
               this.container.refusal = null;
               this.container.setFilterAction.runAndSend(f);
            },
            BusRulesEditor.Scroll.OWN_LIST, null);
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
      boolean problem = false;
      if (this.container.refusal != null) {
         message = this.container.refusal;
         problem = true;
      } else if (bus != null && bus.isInactive()) {
         message = bus.stateMessage();
         problem = true;
      } else if (this.rules.hasUnappliedEdits()) {
         message = Localization.translate("ui", "arcanestorage_unapplied");
      }

      // The wrap width goes with every set: setText(String) delegates to setText(text, -1), which wraps at
      // Integer.MAX_VALUE -- one long line off the side of the panel, which is what this used to draw.
      // A rename by anything else -- another player at the terminal -- lands in the box; typing does not, since
      // the name does not change until it is submitted.
      if (bus != null) {
         this.rules.refreshName(bus.name());
      }

      // The editor owns the status line and reflows itself around it, so the only thing left for the window is
      // to be tall enough to contain the result and to re-anchor when that changes -- setPosFocus computes the
      // offset from the height once, so a form that grows without re-anchoring grows downward through the
      // hotbar instead of upward.
      //
      // Measured from the top of the editor rather than from the top of the window: the editor's natural height
      // does not include the explanation line above it, and leaving that out made the window grow by less than
      // the status line pushed the controls down, which put the Apply button below the panel's own bottom edge.
      this.rules.setStatus(message, problem);
      if (this.rules.consumeHeightChanged()) {
         int wanted = Math.max(BASE_HEIGHT, this.rulesTop + this.rules.getNaturalHeight());
         if (wanted != this.shownHeight) {
            this.shownHeight = wanted;
            this.setHeight(wanted);
            ContainerComponent.setPosFocus(this);
         }
      }

      super.draw(tickManager, perspective, renderBox);
   }

}
