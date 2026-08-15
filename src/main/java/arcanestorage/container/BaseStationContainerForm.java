package arcanestorage.container;

import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.List;

import arcanestorage.band.BandState;
import arcanestorage.band.ChannelRow;
import arcanestorage.objectentity.ArcaneBaseStationObjectEntity;
import arcanestorage.ui.ArcanePanel;
import necesse.engine.gameLoop.tickManager.TickManager;
import necesse.engine.localization.Localization;
import necesse.engine.network.client.Client;
import necesse.entity.mobs.PlayerMob;
import necesse.gfx.GameColor;
import necesse.gfx.forms.components.FormComponent;
import necesse.gfx.forms.components.FormContentBox;
import necesse.gfx.forms.components.FormFlow;
import necesse.gfx.forms.components.FormLabel;
import necesse.gfx.forms.components.localComponents.FormLocalLabel;
import necesse.gfx.forms.presets.containerComponent.ContainerForm;
import necesse.gfx.gameFont.FontOptions;

/**
 * The Base Station's panel: the band's number, and its channels from 1 to N.
 *
 * <p>A list rather than a summary, and free channels are rows too. The question a player walks to a station to ask is
 * "what is on my band, and where can the next silo go" -- a count would answer neither half.
 *
 * <p>Nothing here is clickable. See {@link BaseStationContainer} for why that is the design and not a stage of it.
 * The consequence for this file is that it has no state: every frame it reads the rows off the object entity, which
 * the server pushes when they change, so a silo renamed by another player updates here with no request of any kind.
 */
public class BaseStationContainerForm<T extends BaseStationContainer> extends ContainerForm<T> {

   private static final int WIDTH = 320;

   private static final int PADDING = 5;

   /** Two lines of text plus breathing room, which is what a row with a name and a position needs. */
   private static final int ROW_HEIGHT = 32;

   /** Eight rows visible; a Fallen band's sixteen scroll. Taller than a window has any business being otherwise. */
   private static final int VISIBLE_ROWS = 8;

   private final FormLabel title;

   private final FormLabel status;

   private final FormContentBox list;

   private final List<FormComponent> rowComponents = new ArrayList<>();

   /** What the list was last built for, so it is rebuilt when it changes rather than every frame. */
   private String builtFor = "";

   public BaseStationContainerForm(Client client, T container) {
      super(client, WIDTH, 120, container);
      this.setBackground(ArcanePanel.of());

      FormFlow flow = new FormFlow(PADDING);
      this.title = this.addComponent(flow.nextY(
            new FormLabel("", new FontOptions(16), -1, PADDING, 0), 4));
      this.addComponent(flow.nextY(new FormLocalLabel("ui", "arcanestorage_band_stationhint",
            new FontOptions(12), -1, PADDING, 0, WIDTH - PADDING * 2), 4));
      this.status = this.addComponent(flow.nextY(
            new FormLabel("", new FontOptions(12), -1, PADDING, 0, WIDTH - PADDING * 2), 4));

      int listY = flow.next(0);
      this.list = this.addComponent(new FormContentBox(
            PADDING, listY, WIDTH - PADDING * 2, ROW_HEIGHT * VISIBLE_ROWS));

      // Below the list rather than beside the title, because it is the one thing here that changes the world and it
      // should not sit where a player is aiming to read.
      int buttonY = listY + ROW_HEIGHT * VISIBLE_ROWS + PADDING;
      this.addComponent(new necesse.gfx.forms.components.localComponents.FormLocalTextButton(
            "ui", "arcanestorage_band_upgrade", PADDING, buttonY, WIDTH - PADDING * 2,
            necesse.gfx.forms.components.FormInputSize.SIZE_24, necesse.gfx.ui.ButtonColor.BASE))
            .onClicked(e -> this.container.openUpgradeAction.runAndSend());

      this.setHeight(buttonY + 28 + PADDING);
   }

   @Override
   public void draw(TickManager tickManager, PlayerMob perspective, Rectangle renderBox) {
      ArcaneBaseStationObjectEntity station = this.container.station;

      this.title.setText(station == null || station.getBandId() <= 0
            ? Localization.translate("object", "arcanestoragebasestation")
            : Localization.translate("ui", "arcanestorage_band_title",
                  "band", String.valueOf(station.getBandId())));

      BandState state = station == null ? BandState.NO_TRANSCEIVER : station.getState();
      this.status.setText(state.isActive() || state.localeKey == null
            ? Localization.translate("ui", "arcanestorage_band_transmitting")
            : GameColor.RED.getColorCode() + Localization.translate("ui", state.localeKey),
            WIDTH - PADDING * 2);

      if (station != null) {
         this.rebuild(station);
      }

      super.draw(tickManager, perspective, renderBox);
   }

   /** Rebuilt only when the rows differ, keyed by a cheap signature rather than by comparing components. */
   private void rebuild(ArcaneBaseStationObjectEntity station) {
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
      for (FormComponent component : this.rowComponents) {
         this.list.removeComponent(component);
      }

      this.rowComponents.clear();

      int width = this.list.getWidth() - this.list.getScrollBarWidth() - 2;
      for (int i = 0; i < rows.size(); i++) {
         ChannelRow row = rows.get(i);
         int y = i * ROW_HEIGHT;

         String channel = Localization.translate("ui", "arcanestorage_band_channel",
               "n", String.valueOf(row.channel));
         this.rowComponents.add(this.list.addComponent(
               new FormLabel(channel, new FontOptions(14), -1, 2, y + 2, width)));

         String detail;
         if (row.isFree()) {
            detail = GameColor.GREEN.getColorCode() + Localization.translate("ui", "arcanestorage_band_available");
         } else {
            String where = Localization.translate("ui", "arcanestorage_band_at",
                  "name", row.name(station.getBandId()),
                  "x", String.valueOf(row.tileX), "y", String.valueOf(row.tileY),
                  "distance", String.valueOf(row.distance));
            detail = row.state.isActive() ? where : GameColor.RED.getColorCode() + where;
         }

         this.rowComponents.add(this.list.addComponent(
               new FormLabel(detail, new FontOptions(12), -1, 12, y + 18, width - 12)));
      }

      this.list.setContentBox(new Rectangle(width, rows.size() * ROW_HEIGHT));
   }
}
