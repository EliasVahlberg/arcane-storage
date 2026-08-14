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

   private static final int WIDTH = 440;

   /** Height without any requirement rows; each row adds {@link #ROW_HEIGHT}. */
   private static final int BASE_HEIGHT = 208;

   private static final int ROW_HEIGHT = 28;

   private final FormLabel usage;

   private final FormFairTypeLabel[] rows;

   private final FormLocalTextButton upgradeButton;

   public UnitUpgradeContainerForm(Client client, T container, String nameKey) {
      super(client, WIDTH, BASE_HEIGHT + ROW_HEIGHT * container.cost.length, container);
      this.setBackground(ArcanePanel.of());

      FormFlow flow = new FormFlow(12);

      // Every label is coloured explicitly. A label's default is dark, and this form's background is the mod's
      // own deep purple panel rather than the engine's lighter default, so unstyled text arrives as near-black on
      // near-black -- present, correctly laid out, and unreadable. Taken from the interface style rather than
      // hard-coded so it follows the player's theme, which is also where the requirement rows get their colours.
      final java.awt.Color text = this.getInterfaceStyle().activeTextColor;

      this.addComponent(
         flow.nextY(
            new FormLocalLabel(
               new LocalMessage("object", nameKey), new FontOptions(24).color(text), 0, WIDTH / 2, 0
            ),
            8
         )
      );

      // Used/total, the one fact the chat readout got right and the reason a player right-clicks a unit at all.
      this.usage = this.addComponent(
         flow.nextY(new FormLabel("", new FontOptions(16).color(text), 0, WIDTH / 2, 0), 16)
      );

      this.rows = new FormFairTypeLabel[container.cost.length];

      if (container.cost.length == 0) {
         // Top of the ladder. Say so plainly rather than showing an empty cost list and a dead button.
         this.addComponent(
            flow.nextY(
               new FormLocalLabel(
                  new LocalMessage("ui", "arcanestorage_attoptier"), new FontOptions(16).color(text), 0,
                  WIDTH / 2, 0
               ),
               6
            )
         );

         this.upgradeButton = null;
      } else {
         this.addComponent(
            flow.nextY(
               new FormLocalLabel(
                  new LocalMessage("ui", "arcanestorage_upgradecost"), new FontOptions(16).color(text), -1, 14, 0
               ),
               4
            )
         );

         for (int i = 0; i < this.rows.length; i++) {
            FormFairTypeLabel row = new FormFairTypeLabel("", WIDTH / 2, 0);
            row.setMaxWidth(WIDTH - 32);
            row.setTextAlign(FairType.TextAlign.CENTER);
            this.rows[i] = this.addComponent(flow.nextY(row, 2));
         }

         flow.next(6);

         this.upgradeButton = this.addComponent(
            flow.nextY(
               new FormLocalTextButton("ui", "arcanestorage_upgrade", 20, flow.next(0), WIDTH - 40),
               8
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

      FontOptions costFont = new FontOptions(18);

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
