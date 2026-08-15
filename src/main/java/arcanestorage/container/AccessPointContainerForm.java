package arcanestorage.container;

import java.awt.Rectangle;

import arcanestorage.ArcaneStorage;
import arcanestorage.band.BandOption;
import arcanestorage.band.BandState;
import arcanestorage.objectentity.ArcaneAccessPointObjectEntity;
import arcanestorage.ui.ArcanePanel;
import arcanestorage.ui.ArcaneStyles;
import arcanestorage.ui.ArcaneText;
import necesse.engine.gameLoop.tickManager.TickManager;
import necesse.engine.localization.Localization;
import necesse.engine.localization.message.GameMessage;
import necesse.engine.localization.message.LocalMessage;
import necesse.engine.localization.message.StaticMessage;
import necesse.engine.network.client.Client;
import necesse.entity.mobs.PlayerMob;
import necesse.gfx.forms.components.FormDropdownSelectionButton;
import necesse.gfx.forms.components.FormFlow;
import necesse.gfx.forms.components.FormInputSize;
import necesse.gfx.forms.components.FormLabel;
import necesse.gfx.forms.components.localComponents.FormLocalLabel;
import necesse.gfx.forms.components.FormTextInput;
import necesse.gfx.forms.components.localComponents.FormLocalTextButton;
import necesse.gfx.forms.presets.containerComponent.ContainerForm;
import necesse.gfx.gameFont.FontOptions;
import necesse.gfx.ui.ButtonColor;

/**
 * The Access Point's panel: pick a band, pick a channel, give the silo a name.
 *
 * <p><b>Two dropdowns and one button, rather than applying on each selection.</b> A band without a channel is not a
 * state worth being able to reach, so the choice is assembled locally and sent as one request -- which also means the
 * refusals the server can give ("that channel was taken", "too far") arrive in answer to a deliberate act rather than
 * to a half-finished one.
 *
 * <p>The bands come from the open packet and are a snapshot; what the device has actually got comes from the object
 * entity, which syncs itself. So the status line and the sprite always agree, and a channel that someone else claimed
 * a second ago is caught by the server rather than by the picker.
 */
public class AccessPointContainerForm<T extends AccessPointContainer> extends ContainerForm<T> {

   private static final int WIDTH = 300;

   private static final int PADDING = 5;

   private final FormTextInput nameInput;

   private final FormDropdownSelectionButton<Integer> bandButton;

   private final FormDropdownSelectionButton<Integer> channelButton;

   private final FormLabel status;

   /** Held so its colour can change with what the line says; a label keeps the instance it is given. */
   private final FontOptions statusFont;

   /** The selection being assembled, which is not what the device has until the button is pressed. */
   private int chosenBand;

   private int chosenChannel;

   public AccessPointContainerForm(Client client, T container) {
      super(client, WIDTH, 210, container);
      // Before a single component is added: ComponentList.add copies the parent's style at add time, so a
      // component added first would keep the player's global style and the window would end up half in each.
      ArcaneStyles.apply(this);
      this.setBackground(ArcanePanel.of());

      ArcaneAccessPointObjectEntity point = container.point;
      this.chosenBand = point == null ? 0 : point.getBandId();
      this.chosenChannel = point == null ? 0 : point.getChannel();

      FormFlow flow = new FormFlow(PADDING);
      this.addComponent(flow.nextY(new FormLocalLabel("object", "arcanestorageaccesspoint",
            ArcaneText.body(this, 16), -1, PADDING, 0), 4));
      this.addComponent(flow.nextY(new FormLocalLabel("ui", "arcanestorage_accesspointtip",
            ArcaneText.dim(this, 12), -1, PADDING, 0, WIDTH - PADDING * 2), 6));

      // The name row first, as on a bus, because it is the label a player reads in the station's list and the only
      // handle they have on which silo this is.
      this.addComponent(new FormLocalLabel("ui", "arcanestorage_devicename",
            ArcaneText.body(this, 12), -1, PADDING, flow.next(0) + 4));
      this.nameInput = this.addComponent(new FormTextInput(PADDING + 70, flow.next(28),
            FormInputSize.SIZE_24, WIDTH - PADDING * 2 - 70, -1, ArcaneAccessPointObjectEntity.MAX_NAME_LENGTH));
      this.nameInput.setText(point == null ? "" : point.getCustomName());
      // Submitted rather than applied as it is typed, which is what a bus's name row does: a rename is a decision,
      // and sending one packet per keystroke would put a half-typed name into the station's list.
      this.nameInput.onSubmit(e -> this.container.setNameAction.runAndSend(this.nameInput.getText()));

      int bandY = flow.next(30);
      this.addComponent(new FormLocalLabel("ui", "arcanestorage_band_band",
            ArcaneText.body(this, 12), -1, PADDING, bandY + 6));
      this.bandButton = this.addComponent(new FormDropdownSelectionButton<>(
            PADDING + 70, bandY, FormInputSize.SIZE_24, ButtonColor.BASE, WIDTH - PADDING * 2 - 70));
      this.bandButton.onSelected(event -> {
         this.chosenBand = event.value == null ? 0 : event.value;
         this.chosenChannel = 0;
         this.refreshButtons();
      });

      int channelY = flow.next(30);
      this.addComponent(new FormLocalLabel("ui", "arcanestorage_band_channelrow",
            ArcaneText.body(this, 12), -1, PADDING, channelY + 6));
      this.channelButton = this.addComponent(new FormDropdownSelectionButton<>(
            PADDING + 70, channelY, FormInputSize.SIZE_24, ButtonColor.BASE, WIDTH - PADDING * 2 - 70));
      this.channelButton.onSelected(event -> {
         this.chosenChannel = event.value == null ? 0 : event.value;
         this.refreshButtons();
      });

      int buttonY = flow.next(30);
      this.addComponent(new FormLocalTextButton("ui", "arcanestorage_band_tune",
            PADDING, buttonY, (WIDTH - PADDING * 3) / 2, FormInputSize.SIZE_24, ButtonColor.BASE))
            .onClicked(e -> {
               this.container.refusal = null;
               this.container.tuneAction.runAndSend(this.chosenBand, this.chosenChannel);
            });
      this.addComponent(new FormLocalTextButton("ui", "arcanestorage_band_disconnect",
            PADDING * 2 + (WIDTH - PADDING * 3) / 2, buttonY, (WIDTH - PADDING * 3) / 2,
            FormInputSize.SIZE_24, ButtonColor.RED))
            .onClicked(e -> {
               this.container.refusal = null;
               this.chosenBand = 0;
               this.chosenChannel = 0;
               this.container.tuneAction.runAndSend(0, 0);
               this.refreshButtons();
            });

      this.statusFont = ArcaneText.body(this, 12);
      this.status = this.addComponent(new FormLabel("", this.statusFont, -1,
            PADDING, flow.next(20), WIDTH - PADDING * 2));

      this.setHeight(flow.next(PADDING));
      this.refreshButtons();
   }

   /**
    * Rebuilds both dropdowns from the snapshot and the local selection.
    *
    * <p>A taken channel is listed and refused rather than hidden. Hiding it would leave a player counting gaps to
    * work out why 3 is missing, and the list is short enough that a row saying "in use" is the clearer answer.
    */
   private void refreshButtons() {
      int range = ArcaneStorage.SETTINGS.bandRange;

      this.bandButton.options.clear();
      this.bandButton.options.add(0, new LocalMessage("ui", "arcanestorage_band_none"));
      BandOption chosen = null;
      for (BandOption band : this.container.bands) {
         GameMessage label = new StaticMessage(Localization.translate("ui", "arcanestorage_band_option",
               "band", String.valueOf(band.id),
               "x", String.valueOf(band.stationX), "y", String.valueOf(band.stationY),
               "distance", String.valueOf(band.distance))
               + (band.inRange(range) ? "" : " " + Localization.translate("ui", "arcanestorage_band_outofrangeshort")));
         this.bandButton.options.add(band.id, label);
         if (band.id == this.chosenBand) {
            chosen = band;
         }
      }

      this.bandButton.setSelected(this.chosenBand, chosen == null
            ? new LocalMessage("ui", "arcanestorage_band_none")
            : new StaticMessage(Localization.translate("ui", "arcanestorage_band_short",
                  "band", String.valueOf(chosen.id))));

      this.channelButton.options.clear();
      final BandOption band = chosen;
      if (band != null) {
         for (int channel = 1; channel <= band.channelCount(); channel++) {
            final int n = channel;
            this.channelButton.options.add(n, new StaticMessage(
                  Localization.translate("ui", "arcanestorage_band_channel", "n", String.valueOf(n))
                     + (band.isTaken(n) ? " " + Localization.translate("ui", "arcanestorage_band_inuse") : "")),
                  null, () -> !band.isTaken(n));
         }
      }

      this.channelButton.setSelected(this.chosenChannel, this.chosenChannel <= 0
            ? new LocalMessage("ui", "arcanestorage_band_none")
            : new StaticMessage(Localization.translate("ui", "arcanestorage_band_channel",
                  "n", String.valueOf(this.chosenChannel))));
   }

   @Override
   public void draw(TickManager tickManager, PlayerMob perspective, Rectangle renderBox) {
      ArcaneAccessPointObjectEntity point = this.container.point;

      // A refusal outranks the state, because it answers what the player just did. The state is a standing fact and
      // will still be true once they have read the refusal.
      String message;
      boolean good = false;
      if (this.container.refusal != null) {
         message = this.container.refusal;
      } else if (point == null) {
         message = "";
      } else {
         BandState state = point.getState();
         good = state.isActive();
         message = good
            ? Localization.translate("ui", "arcanestorage_band_connected",
                  "band", String.valueOf(point.getBandId()), "channel", String.valueOf(point.getChannel()))
            : Localization.translate("ui", state.localeKey);
      }

      // Recoloured in place rather than prefixed with a colour code. A code is chat and tooltip syntax -- the text
      // renderer behind a form label does not parse it, so the panels showed the paragraph marks themselves.
      this.statusFont.color(good ? ArcaneText.successColor(this) : ArcaneText.errorColor(this));
      this.status.setText(message, WIDTH - PADDING * 2);

      // A rename by anything else lands in the box; typing does not, since the name is not applied until submitted.
      if (point != null && !this.nameInput.isTyping()) {
         this.nameInput.setText(point.getCustomName());
      }

      super.draw(tickManager, perspective, renderBox);
   }
}
