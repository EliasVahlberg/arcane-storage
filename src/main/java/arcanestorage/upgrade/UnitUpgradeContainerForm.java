package arcanestorage.upgrade;

import arcanestorage.container.FormColorFill;
import arcanestorage.ui.ArcanePanel;
import java.awt.Color;
import java.awt.Rectangle;
import necesse.engine.gameLoop.tickManager.TickManager;
import necesse.engine.localization.Localization;
import necesse.engine.localization.message.LocalMessage;
import necesse.engine.network.client.Client;
import necesse.entity.mobs.PlayerMob;
import necesse.gfx.forms.components.FormFlow;
import necesse.gfx.forms.components.FormInputSize;
import necesse.gfx.forms.components.FormLabel;
import necesse.gfx.forms.components.localComponents.FormLocalLabel;
import necesse.gfx.forms.components.localComponents.FormLocalTextButton;
import necesse.gfx.forms.components.FormFairTypeLabel;
import necesse.gfx.forms.presets.containerComponent.ContainerForm;
import necesse.gfx.fairType.FairType;
import necesse.gfx.gameFont.FontOptions;
import necesse.gfx.ui.ButtonColor;
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

   /**
    * The font of a requirement row, shared by layout and drawing.
    *
    * <p>Shared deliberately. The row's height is measured once at construction and its text is rebuilt every frame;
    * if those two used different font sizes the panel would be laid out for one row height and draw another, which
    * is the same class of fault as the bug this replaced and harder to see.
    */
   private static final FontOptions COST_FONT = new FontOptions(18);

   /**
    * Drawn over the panel's centre, inset so the purple frame still reads as a border.
    *
    * <p>The alternative was to keep the panel's own brightness and pick a text colour to suit it, which is what the
    * first attempt did by taking the interface style's active colour. That is theme-correct and was still hard to
    * read, because the panel is the mod's own art and darker than the engine's, and the style's colours are chosen
    * for the engine's. Fixing the background instead means the text colour can simply be white and stay legible
    * whatever theme is set.
    */
   private static final Color BACKING = new Color(10, 7, 16, 235);

   private static final Color TEXT = Color.WHITE;

   /** The slot for the used/total line: one line at its font size, plus breathing room below it. */
   private static final int USAGE_HEIGHT = 16 + 12;

   /** Bottom padding below the last component, and the only thing that makes the panel's height not exact. */
   private static final int BOTTOM_PADDING = 12;

   private final FormColorFill backing;

   private final FormLabel usage;

   private final FormFairTypeLabel[] rows;

   private final FormLocalTextButton upgradeButton;

   public UnitUpgradeContainerForm(Client client, T container, String nameKey) {
      // A provisional height. The real one is measured from the laid-out content at the end of this constructor,
      // which is why no arithmetic here tries to predict it: the label heights depend on font metrics, and the row
      // count depends on the tier. Vanilla does the same in ConfirmationForm -- build, then setHeight -- and the
      // resize is safe here because nothing has positioned or drawn the form yet.
      super(client, WIDTH, 200, container);
      this.setBackground(ArcanePanel.of());

      // First, so it draws behind every label. Sized at the end, once the content's height is known.
      this.backing = this.addComponent(new FormColorFill(4, 4, WIDTH - 8, 0, BACKING));

      FormFlow flow = new FormFlow(12);

      final Color text = TEXT;

      // Wrapped, and at 20 rather than 24. "Demonic Wireless Transceiver" is three words and wider than the panel at
      // any size worth reading, and a centred label with no max width does not wrap -- it runs off both edges at
      // once. The flow measures the laid-out label, so a name that takes two lines simply pushes the rest down.
      this.addComponent(
         flow.nextY(
            new FormLocalLabel(
               new LocalMessage("object", nameKey), new FontOptions(20).color(text), 0, WIDTH / 2, 0,
               WIDTH - 24
            ),
            8
         )
      );

      // Used/total, the one fact the chat readout got right and the reason a player right-clicks a unit at all.
      //
      // Its slot is reserved too. This label is also built empty and filled in draw(), and FormLabel's height is
      // its line count times its font size -- so whether an empty one measures as one line or none is a detail of
      // GameMessage.breakMessage that the layout should not depend on either way.
      this.usage = this.addComponent(
         new FormLabel("", new FontOptions(16).color(text), 0, WIDTH / 2, flow.next(USAGE_HEIGHT))
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

            // Its real text, before the flow measures it. This is the whole fix, and it is what vanilla's Upgrade
            // Station does -- setCustomFairType then nextY. A row is not a line of text but a line containing a
            // 32 px item icon, so its height is the icon's and no constant chosen here would have been right; the
            // reserved 26 px that replaced the previous zero-height bug was still short enough to slide the last
            // row under the button. The amount passed is only a placeholder, since draw() rebuilds this every
            // frame with the pushed count -- what matters at this point is that the row has its true height.
            row.setCustomFairType(
               container.cost[i].getTooltipText(COST_FONT, 0, TEXT, this.getInterfaceStyle().errorTextColor, true, null)
            );

            this.rows[i] = this.addComponent(flow.nextY(row, 4));
         }

         flow.next(10);

         this.upgradeButton = this.addComponent(
            flow.nextY(
               new FormLocalTextButton(
                  "ui", "arcanestorage_upgrade", 20, 0, WIDTH - 40, FormInputSize.SIZE_32, ButtonColor.BASE
               )
            )
         );
         this.upgradeButton.onClicked(event -> this.container.upgradeAction.runAndSend());
      }

      // Fit the panel to what was actually laid out. The flow's position after the last component is the content's
      // height, so this is exact apart from the padding below it, and it removes the guessed constant that made
      // the panel both too tall and, for the top tier with no rows at all, mostly empty.
      this.setHeight(flow.next(BOTTOM_PADDING));
      this.backing.setSize(WIDTH - 8, this.getHeight() - 8);
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

      for (int i = 0; i < this.rows.length; i++) {
         Ingredient ingredient = this.container.cost[i];

         // -1 rather than 0 while the first state is still in flight: it is the engine's own "unknown" for a
         // held amount, so a row renders as not-yet-satisfied instead of claiming the player has none.
         int have = this.container.availableFor(i);

         this.rows[i].setCustomFairType(
            ingredient.getTooltipText(
               COST_FONT,
               have,
               TEXT,
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
