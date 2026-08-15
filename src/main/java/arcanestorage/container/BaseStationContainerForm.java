package arcanestorage.container;

import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.List;

import arcanestorage.band.BandState;
import arcanestorage.band.ChannelRow;
import arcanestorage.objectentity.ArcaneBaseStationObjectEntity;
import arcanestorage.ui.ArcanePanel;
import arcanestorage.ui.ArcaneStyles;
import arcanestorage.ui.ArcaneText;
import necesse.engine.gameLoop.tickManager.TickManager;
import necesse.engine.localization.Localization;
import necesse.engine.network.client.Client;
import necesse.entity.mobs.PlayerMob;
import necesse.gfx.forms.Form;
import necesse.gfx.forms.components.FormContentBox;
import necesse.gfx.forms.components.FormFlow;
import necesse.gfx.forms.components.FormInputSize;
import necesse.gfx.forms.components.FormLabel;
import necesse.gfx.forms.components.FormTextButton;
import necesse.gfx.forms.components.localComponents.FormLocalLabel;
import necesse.gfx.forms.components.localComponents.FormLocalTextButton;
import necesse.gfx.forms.presets.containerComponent.ContainerForm;
import necesse.gfx.gameFont.FontOptions;
import necesse.gfx.ui.ButtonColor;

/**
 * The Base Station's panel: the band's channels down the left, whatever is on the selected one to the right.
 *
 * <p>Master-detail, and the same shape as the terminal's logistics tab on purpose -- these are the two windows in the
 * mod that answer "what is attached to this network and is it working", so they should not be two different windows to
 * learn. The first attempt was a single column of two-line text rows, which read as a wall: a channel's name, position,
 * distance and reason for being dark do not fit on a row narrow enough to scan, and putting them there meant the list
 * could be neither scanned nor read.
 *
 * <p>Nothing here writes to a link. See {@link BaseStationContainer} for why that is the design rather than a stage of
 * it; the consequence for this file is that selecting a row is a local act with no packet behind it, and the one
 * control that does change the world -- the upgrade button -- sits under the list where nobody is aiming to read.
 *
 * <p>The rows themselves are otherwise stateless: every frame reads them off the object entity, which the server pushes
 * when they change, so a silo renamed by another player updates here with no request of any kind.
 */
public class BaseStationContainerForm<T extends BaseStationContainer> extends ContainerForm<T> {

   private static final int PADDING = 5;

   /** The logistics tab's own list width, so the two windows line up when opened one after the other. */
   private static final int LIST_WIDTH = 208;

   private static final int DETAIL_WIDTH = 264;

   private static final int WIDTH = PADDING * 3 + LIST_WIDTH + DETAIL_WIDTH;

   /** The logistics tab's row pitch. */
   private static final int ROW_PITCH = 26;

   /**
    * The title's slot: one line at font 20, plus the gap below it.
    *
    * <p>One line is enough for every string that can appear here. The longest is a tier's own object name, and
    * "Demonic Arcane Base Station" measures well under the 477 px the label is given to wrap in, so the max width is
    * there to stop a name running off the panel edge rather than because a second line is expected.
    */
   private static final int TITLE_HEIGHT = 20 + 4;

   /** The status line's slot: one line at font 12, plus the gap below it. Every state message fits one line. */
   private static final int STATUS_HEIGHT = 12 + 6;

   /** Eight rows before it scrolls, which is a Tungsten band whole and half of a Fallen one. */
   private static final int VISIBLE_ROWS = 8;

   private static final int LIST_HEIGHT = ROW_PITCH * VISIBLE_ROWS;

   private final FormLabel title;

   private final FormLabel status;

   /** Held rather than rebuilt so its colour can change with the state; a label keeps the instance it is given. */
   private final FontOptions statusFont;

   private final FormContentBox list;

   private final List<FormTextButton> rowButtons = new ArrayList<>();

   /** The detail column. A nested form, so it draws the vanilla panel and its text needs no recolouring. */
   private Form detail;

   /** What the list was last built for, so it is rebuilt when it changes rather than every frame. */
   private String builtFor = "";

   /** What the detail column was last built for: the selection, and the row behind it. */
   private String detailBuiltFor = "";

   private int selectedChannel;

   public BaseStationContainerForm(Client client, T container) {
      super(client, WIDTH, 120, container);
      // Before a single component is added: ComponentList.add copies the parent's style at add time, so a
      // component added first would keep the player's global style and the window would end up half in each.
      ArcaneStyles.apply(this);
      this.setBackground(ArcanePanel.of());

      FormFlow flow = new FormFlow(PADDING);
      // Both the title and the status line are built empty and filled in draw(), so their slots are reserved rather
      // than measured. FormLabel.getHeight() is lines.size() times the font size, and an empty string breaks into no
      // lines at all -- so measuring one with flow.nextY reserves nothing but the trailing gap. That is what put the
      // hint on top of the title, with 4 px reserved for a 20 px line, and pushed the green status line 6 px into the
      // list and detail boxes below it. AccessPointContainerForm already reserves its status slot this way.
      this.title = this.addComponent(new FormLabel("", ArcaneText.body(this, 20), -1, PADDING,
            flow.next(TITLE_HEIGHT), WIDTH - PADDING * 2));
      this.addComponent(flow.nextY(new FormLocalLabel("ui", "arcanestorage_band_stationhint",
            ArcaneText.dim(this, 12), -1, PADDING, 0, WIDTH - PADDING * 2), 4));

      this.statusFont = ArcaneText.body(this, 12);
      this.status = this.addComponent(new FormLabel("", this.statusFont, -1, PADDING,
            flow.next(STATUS_HEIGHT), WIDTH - PADDING * 2));

      int contentTop = flow.next(0);
      this.list = this.addComponent(new FormContentBox(PADDING, contentTop, LIST_WIDTH, LIST_HEIGHT));

      int buttonY = contentTop + LIST_HEIGHT + PADDING;
      this.addComponent(new FormLocalTextButton("ui", "arcanestorage_band_upgrade",
            PADDING, buttonY, LIST_WIDTH, FormInputSize.SIZE_24, ButtonColor.BASE))
            .onClicked(e -> this.container.openUpgradeAction.runAndSend());

      int contentHeight = LIST_HEIGHT + PADDING + FormInputSize.SIZE_24.height;
      this.detail = this.addComponent(new Form(DETAIL_WIDTH, contentHeight));
      this.detail.setBackground(ArcanePanel.of());
      this.detail.setPosition(PADDING * 2 + LIST_WIDTH, contentTop);

      this.setHeight(contentTop + contentHeight + PADDING);
   }

   @Override
   public void draw(TickManager tickManager, PlayerMob perspective, Rectangle renderBox) {
      ArcaneBaseStationObjectEntity station = this.container.station;

      // The tier's own name, not the bottom rung's: hardcoding the Demonic key would head a Fallen station's panel
      // "Demonic Arcane Base Station" for as long as it had no band, which is exactly when a player is looking.
      this.title.setText(station == null || station.getBandId() <= 0
            ? Localization.translate("object",
                  station == null ? "arcanestoragebasestation" : station.tier().baseStationId())
            : Localization.translate("ui", "arcanestorage_band_title",
                  "band", String.valueOf(station.getBandId())),
            // The width has to be passed here, not only at construction: setText(String) resolves to setText(text,
            // -1), so every frame would otherwise re-break the text with no bound and a long tier name would run off
            // the panel edge.
            WIDTH - PADDING * 2);

      BandState state = station == null ? BandState.NO_TRANSCEIVER : station.getState();
      boolean transmitting = state.isActive() || state.localeKey == null;
      // The instance the label holds, recoloured in place. FormLabel keeps no copy and exposes no setter, so this is
      // the whole mechanism for a line whose colour is part of what it says.
      this.statusFont.color(transmitting ? ArcaneText.successColor(this)
            : ArcaneText.errorColor(this));
      this.status.setText(transmitting
            ? Localization.translate("ui", "arcanestorage_band_transmitting")
            : Localization.translate("ui", state.localeKey), WIDTH - PADDING * 2);

      if (station != null) {
         this.rebuildList(station);
         this.rebuildDetail(station);
      }

      super.draw(tickManager, perspective, renderBox);
   }

   /** Rebuilt only when the rows differ, keyed by a cheap signature rather than by comparing components. */
   private void rebuildList(ArcaneBaseStationObjectEntity station) {
      List<ChannelRow> rows = station.getRows();
      StringBuilder signature = new StringBuilder();
      for (ChannelRow row : rows) {
         signature.append(row.channel).append(':').append(row.tileX).append(',').append(row.tileY)
               .append(':').append(row.customName).append(':').append(row.state).append('|');
      }

      String key = signature.toString();
      if (key.equals(this.builtFor)) {
         return;
      }

      this.builtFor = key;
      for (FormTextButton button : this.rowButtons) {
         this.list.removeComponent(button);
      }

      this.rowButtons.clear();
      if (this.selectedChannel <= 0 && !rows.isEmpty()) {
         // Opens on something worth reading: the first silo if there is one, and channel 1 if the band is empty.
         this.selectedChannel = rows.stream().filter(row -> !row.isFree()).map(row -> row.channel)
               .findFirst().orElse(rows.get(0).channel);
      }

      int width = LIST_WIDTH - this.list.getScrollBarWidth() - 2;
      for (int i = 0; i < rows.size(); i++) {
         ChannelRow row = rows.get(i);
         String label = row.isFree()
               ? Localization.translate("ui", "arcanestorage_band_rowfree", "n", String.valueOf(row.channel))
               : Localization.translate("ui", "arcanestorage_band_row",
                     "n", String.valueOf(row.channel), "name", row.name(station.getBandId()));

         // Red for a silo that has stopped, exactly as a stopped bus's row is red and its sprite goes grey. A free
         // channel is not a problem, so it stays base-coloured and says so in words.
         FormTextButton button = this.list.addComponent(new FormTextButton(
               label, 0, i * ROW_PITCH, width, FormInputSize.SIZE_24,
               row.isFree() || row.state.isActive() ? ButtonColor.BASE : ButtonColor.RED));
         int channel = row.channel;
         button.onClicked(e -> this.selectedChannel = channel);
         this.rowButtons.add(button);
      }

      this.list.setContentBox(new Rectangle(width, rows.size() * ROW_PITCH));
   }

   /**
    * Builds the detail column for the selected channel.
    *
    * <p>Rebuilt rather than repointed because it is a handful of labels whose count depends on what is being shown,
    * and keyed on the selection plus that row's own signature so a silo going dark updates the column it is shown in.
    */
   private void rebuildDetail(ArcaneBaseStationObjectEntity station) {
      ChannelRow selected = null;
      for (ChannelRow row : station.getRows()) {
         if (row.channel == this.selectedChannel) {
            selected = row;
            break;
         }
      }

      String key = selected == null ? "none"
            : selected.channel + ":" + selected.tileX + "," + selected.tileY + ":" + selected.customName
                  + ":" + selected.state + ":" + selected.distance + ":" + station.getBandId();
      if (key.equals(this.detailBuiltFor)) {
         return;
      }

      this.detailBuiltFor = key;
      this.detail.clearComponents();

      int width = DETAIL_WIDTH - PADDING * 2;
      FormFlow flow = new FormFlow(PADDING);
      if (selected == null) {
         this.detail.addComponent(flow.nextY(new FormLocalLabel("ui", "arcanestorage_band_pickchannel",
               new FontOptions(14), -1, PADDING, 0, width), 4));
         return;
      }

      this.detail.addComponent(flow.nextY(new FormLabel(
            Localization.translate("ui", "arcanestorage_band_channel", "n", String.valueOf(selected.channel)),
            new FontOptions(16), -1, PADDING, 0, width), 6));

      if (selected.isFree()) {
         this.detail.addComponent(flow.nextY(new FormLocalLabel("ui", "arcanestorage_band_available",
               new FontOptions(14).color(this.getInterfaceStyle().successTextColor), -1, PADDING, 0, width), 6));
         this.detail.addComponent(flow.nextY(new FormLabel(
               Localization.translate("ui", "arcanestorage_band_freehint",
                     "band", String.valueOf(station.getBandId()), "n", String.valueOf(selected.channel)),
               new FontOptions(12).color(this.getInterfaceStyle().inactiveTextColor), -1, PADDING, 0, width), 4));
         return;
      }

      this.detail.addComponent(flow.nextY(new FormLabel(selected.name(station.getBandId()),
            new FontOptions(14), -1, PADDING, 0, width), 6));
      this.detail.addComponent(flow.nextY(new FormLabel(
            Localization.translate("ui", "arcanestorage_band_where",
                  "x", String.valueOf(selected.tileX), "y", String.valueOf(selected.tileY),
                  "distance", String.valueOf(selected.distance)),
            new FontOptions(12).color(this.getInterfaceStyle().inactiveTextColor), -1, PADDING, 0, width), 6));

      boolean active = selected.state.isActive() || selected.state.localeKey == null;
      this.detail.addComponent(flow.nextY(new FormLabel(
            active ? Localization.translate("ui", "arcanestorage_band_transmitting")
                  : Localization.translate("ui", selected.state.localeKey),
            new FontOptions(12).color(active
                  ? this.getInterfaceStyle().successTextColor
                  : this.getInterfaceStyle().errorTextColor), -1, PADDING, 0, width), 4));
   }
}
