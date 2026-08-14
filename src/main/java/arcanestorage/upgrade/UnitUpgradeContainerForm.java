package arcanestorage.upgrade;

import arcanestorage.ui.ArcanePanel;
import java.awt.Rectangle;
import necesse.engine.gameLoop.tickManager.TickManager;
import necesse.engine.localization.Localization;
import necesse.engine.localization.message.LocalMessage;
import necesse.engine.network.client.Client;
import necesse.entity.mobs.PlayerMob;
import necesse.gfx.forms.components.FormFlow;
import necesse.gfx.forms.components.FormLabel;
import necesse.gfx.forms.components.localComponents.FormLocalLabel;
import necesse.gfx.forms.components.localComponents.FormLocalTextButton;
import necesse.gfx.forms.components.FormFairTypeLabel;
import necesse.gfx.forms.presets.containerComponent.ContainerForm;
import necesse.gfx.fairType.FairType;
import necesse.gfx.gameFont.FontOptions;
import necesse.inventory.recipe.Ingredient;

/**
 * The unit upgrade panel: what this is, how full it is, what the next rung costs, and one button.
 *
 * <p>It replaces a chat message. Right-clicking a unit used to print used/total to the chat log, which
 * answered the question and then scrolled away, and could not offer an action. The panel shows the same two
 * numbers and puts the upgrade next to them, where the player is already looking when they wonder whether the
 * unit is big enough.
 *
 * <p>The requirement rows are drawn with {@link Ingredient#getTooltipText}, which is what vanilla's own
 * Upgrade Station uses for exactly this job: it renders the item, the required amount and the amount held,
 * coloured with the interface's active colour when the requirement is met and its error colour when it is not.
 * Reusing it means a cost here reads identically to a cost anywhere else in the game, and the highlighting
 * follows the player's theme rather than a colour chosen here. It is rebuilt per frame because the numbers
 * behind it arrive by push while the panel is open -- see {@link UnitUpgradeContainer}.
 *
 * <p>Nothing in this file can be covered by a headless test: forms are client-side, and the harness has no
 * client. Layout, the highlight colours and the button's disabled state are verifiable only in game.
 */
public class UnitUpgradeContainerForm<T extends UnitUpgradeContainer> extends ContainerForm<T> {

   private static final int WIDTH = 220;

   /** Height without any requirement rows; each row adds {@link #ROW_HEIGHT}. */
   private static final int BASE_HEIGHT = 104;

   private static final int ROW_HEIGHT = 22;

   private final FormLabel usage;

   private final FormFairTypeLabel[] rows;

   private final FormLocalTextButton upgradeButton;

   public UnitUpgradeContainerForm(Client client, T container, String nameKey) {
      super(client, WIDTH, BASE_HEIGHT + ROW_HEIGHT * container.cost.length, container);
      this.setBackground(ArcanePanel.of());

      FormFlow flow = new FormFlow(6);

      this.addComponent(
         flow.nextY(new FormLocalLabel(new LocalMessage("object", nameKey), new FontOptions(16), 0, WIDTH / 2, 0), 4)
      );

      // Used/total, the one fact the chat readout got right and the reason a player right-clicks a unit at all.
      this.usage = this.addComponent(flow.nextY(new FormLabel("", new FontOptions(12), 0, WIDTH / 2, 0), 8));

      this.rows = new FormFairTypeLabel[container.cost.length];

      if (container.cost.length == 0) {
         // Top of the ladder. Say so plainly rather than showing an empty cost list and a dead button.
         this.addComponent(
            flow.nextY(
               new FormLocalLabel(
                  new LocalMessage("ui", "arcanestorage_attoptier"), new FontOptions(12), 0, WIDTH / 2, 0
               ),
               6
            )
         );

         this.upgradeButton = null;
      } else {
         this.addComponent(
            flow.nextY(
               new FormLocalLabel(
                  new LocalMessage("ui", "arcanestorage_upgradecost"), new FontOptions(12), -1, 8, 0
               ),
               4
            )
         );

         for (int i = 0; i < this.rows.length; i++) {
            FormFairTypeLabel row = new FormFairTypeLabel("", WIDTH / 2, 0);
            row.setMaxWidth(WIDTH - 16);
            row.setTextAlign(FairType.TextAlign.CENTER);
            this.rows[i] = this.addComponent(flow.nextY(row, 2));
         }

         flow.next(6);

         this.upgradeButton = this.addComponent(
            flow.nextY(
               new FormLocalTextButton("ui", "arcanestorage_upgrade", 10, flow.next(0), WIDTH - 20),
               4
            )
         );
         this.upgradeButton.onClicked(event -> this.container.upgradeAction.runAndSend());
      }
   }

   @Override
   public void draw(TickManager tickManager, PlayerMob perspective, Rectangle renderBox) {
      UpgradeStateEvent state = this.container.getState();

      this.usage.setText(
         state == null
            ? ""
            : Localization.translate(
               "ui", "arcanestorage_unitstatus",
               "used", String.valueOf(state.used),
               "total", String.valueOf(state.total)
            )
      );

      FontOptions costFont = new FontOptions(14);

      for (int i = 0; i < this.rows.length; i++) {
         Ingredient ingredient = this.container.cost[i];

         // -1 rather than 0 while the first state is still in flight: it is the engine's own "unknown" for a
         // held amount, so a row renders as not-yet-satisfied instead of claiming the player has none.
         int have = this.container.availableFor(i);

         this.rows[i].setCustomFairType(
            ingredient.getTooltipText(
               costFont,
               have,
               this.getInterfaceStyle().activeTextColor,
               this.getInterfaceStyle().errorTextColor,
               true,
               null
            )
         );
      }

      if (this.upgradeButton != null) {
         // The button's own state is the client's read of pushed numbers, which is a convenience only. The
         // server revalidates in UpgradeAction, so a stale or tampered client cannot upgrade for free.
         this.upgradeButton.setActive(this.container.canUpgrade());
      }

      super.draw(tickManager, perspective, renderBox);
   }
}
