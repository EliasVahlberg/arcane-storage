package arcanestorage;

import java.util.LinkedHashMap;
import java.util.Map;

import arcanestorage.recipe.CostTable;
import necesse.engine.modLoader.ModSettings;
import necesse.engine.save.LoadData;
import necesse.engine.save.SaveData;

/**
 * The mod's config file, at {@code <cfg>/mods/elias.arcanestorage.cfg}, written and read by the game.
 *
 * <p>Returned from {@code ArcaneStorage.initSettings()}, which the loader calls by name; the game then writes these
 * out and loads them back, the same mechanism it uses for mod key bindings. So this is not settings infrastructure
 * invented here -- it is the engine's, and it costs one class and one method.
 *
 * <h2>Which side reads which setting</h2>
 *
 * <p>{@code Settings.loadModSettings} is called from {@code GlobalData.loadGame(isServer)}, so <b>a dedicated server
 * loads this file too</b>, from its own config directory. That matters for what may safely live here:
 *
 * <ul>
 *   <li><b>Presentation</b> is read by whichever side draws it, which is the client. A mismatch is invisible.</li>
 *   <li><b>Reach</b> is read only where access is decided, which is the server. The client never checks it, so a
 *       mismatch can at worst put a wrong number in a tooltip.</li>
 *   <li><b>Costs</b> are read by both sides independently, and the engine sends no recipe data. A mismatch shows
 *       the client ingredient lists its server refuses -- see {@link CostTable}.</li>
 * </ul>
 */
public class ArcaneStorageSettings extends ModSettings {

   /**
    * How the terminal's item grid is ordered: {@code group}, {@code name} or {@code amount}.
    *
    * <p>Here rather than attached to the player, and the reasoning is the same as for the theme. This decides what
    * one client draws and nothing else -- the withdrawal path names an item and never a position -- so a server has
    * no interest in it, and putting it in player data would mean a packet and a permanent save format commitment for
    * a purely visual choice. The cost of the config file is that it is per machine, so two people sharing a computer
    * share a sort order.
    *
    * <p>Defaults to {@code group}, the engine's own category-then-name order, which is what the player's inventory
    * sort button produces. An unrecognised value reads as {@code group} rather than failing.
    */
   public String sortMode = "group";

   /**
    * How deep the storage tab's category tree goes: {@code none}, {@code coarse}, or {@code fine}.
    *
    * <p>Same persistence reasoning as {@link #sortMode} -- a per-machine visual preference, not
    * something worth a packet or a save-format commitment. Defaults to {@code fine}, today's only
    * behaviour before this setting existed, so an upgrade changes nothing until a player opens the
    * new dropdown.
    */
   public String storageGroupingMode = "fine";

   /** Crafting's own version of {@link #storageGroupingMode}, independent per Elias's own instruction. */
   public String craftingGroupingMode = "fine";

   /**
    * Whether Arcane Storage offers the network to Build Menu, when that mod is installed.
    *
    * <p>On by default. Read only on the server, because that is the one side that decides what a
    * placement may take; the client's "can afford" display is Build Menu's own and is unaffected.
    * Turning it off here leaves Build Menu with exactly its vanilla behaviour (inventory, cloud and
    * settlement chests), so this is a safe escape hatch if the two mods ever disagree.
    */
   public boolean buildMenuCompat = true;

   /**
    * Which interface style the mod's own windows are drawn with: {@code slate}, {@code dark}, or {@code vanilla}.
    *
    * <p>Defaults to a theme rather than to vanilla, because the difference is a storage terminal that looks like
    * part of this mod against one that looks like a chest with more buttons. It stays a setting because the cost
    * falls on a specific player: Necesse ships several interface styles and shows a selector when it has more
    * than one, so anyone who deliberately themed their game has that choice overridden for these windows.
    * {@code vanilla} hands it back and is a real path, not a fallback — the mod then draws exactly what any other
    * container would.
    *
    * <p>This replaces an earlier {@code useCustomPanel} boolean, which chose between the player's style and one
    * purple panel texture. An unrecognised value reads as {@code slate}, so an old config loses its preference
    * rather than failing.
    */
   public String theme = arcanestorage.ui.ArcaneStyles.Theme.SLATE.settingValue();

   /**
    * Tiles a Demonic pairing reaches on the transceiver's own level.
    *
    * <p>120 is a starting number rather than a derived one, and it is in the config because it wants tuning in play.
    * The reasoning behind the magnitude: vanilla's own craft-from-nearby-containers reaches 5 to 9 tiles, so any
    * three-digit number is already a different kind of feature; a region is 16 tiles, so 120 is between seven and
    * eight of them; and the planned wireless connector is meant to span about 100, which puts the first rung of
    * remote access and the first rung of remote wiring in the same neighbourhood. It should cover a base and its
    * immediate surroundings and fall short of the next biome over.
    */
   public int wirelessRangeDemonic = 120;

   /**
    * Tiles a Tungsten pairing reaches on the transceiver's own level. Negative means no limit.
    *
    * <p>Unlimited by default, which is the rung's whole point: the same level, however far across it you have
    * wandered. A number here turns it into a bigger Demonic instead, which is a valid thing to want and the reason
    * this is a setting rather than a constant.
    */
   public int wirelessRangeTungsten = -1;

   /**
    * Tiles a Fallen pairing reaches on the transceiver's own level. Negative means no limit.
    *
    * <p>Fallen also crosses levels, and <b>that</b> is not configurable: which rung stops being local is the shape
    * of the ladder rather than a number to tune. Kept as a setting at all for symmetry, so that a server wanting a
    * flatter progression can pull all three in without one of them being a special case.
    */
   public int wirelessRangeFallen = -1;

   /**
    * Tiles an Access Point may stand from its Base Station.
    *
    * <p>200 rather than the transceiver's 120, and the difference is the point: a band is meant to reach across a
    * base and its outbuildings -- the farm, the mine head, the smelter row -- while a wireless pairing is meant to
    * reach the base from wherever the player has wandered. One is architecture and the other is a commute.
    *
    * <p>Not tiered, unlike wireless reach. The Base Station's tier buys channels, which is how many silos a band
    * carries; making it buy distance as well would mean the first rung could not span the base it was built in.
    */
   public int bandRange = 200;

   /**
    * Channels a Demonic, Tungsten and Fallen Base Station's band offers -- one silo each.
    *
    * <p>Four at the first rung because four is enough to be worth building: grain by the farm, ore by the mine,
    * timber by the trees, and one spare. Doubling from there matches every other ladder in the mod, and the top rung
    * is deliberately more than anyone needs, since the interesting limit at that point is how many silos a player
    * wants to walk between rather than how many the band can carry.
    *
    * <p>Lowering these in the config does not move an Access Point that is already on a channel above the new count.
    * The claim survives in the save and the station reports it as an overflow, because dropping a player's silo to
    * enforce a number they just edited would be the wrong way round.
    */
   public int bandChannelsDemonic = 4;

   public int bandChannelsTungsten = 8;

   public int bandChannelsFallen = 16;

   /** How many channels a Base Station of this tier offers. Never fewer than one, whatever the file says. */
   public int bandChannels(arcanestorage.object.UnitTier tier) {
      switch (tier) {
         case FALLEN:
            return this.bandChannelsFallen;
         case TUNGSTEN:
            return this.bandChannelsTungsten;
         default:
            return this.bandChannelsDemonic;
      }
   }

   /**
    * Recipe and upgrade costs, keyed as in {@code recipes.properties}, defaulting to what ships in the jar.
    *
    * <p>Populated from {@link CostTable} rather than written out here, so the config file cannot list a key the mod
    * does not read or miss one it does. Loading is separate from applying: these strings are collected here and
    * handed to {@link CostTable#override} from the mod's {@code init}, because the item registry does not exist yet
    * when this file is read and a bad ingredient ID has to fail where it can be attributed.
    */
   public final Map<String, String> costs = new LinkedHashMap<>();

   /**
    * Whether a settler's "craft until N" order counts stock kept in this mod's Storage Units inside the settlement.
    *
    * <p>On by default, because the alternative is the bug it fixes: a settlement chest with an export bus beside it
    * sends an order's results into units, the engine counts only assigned storage, and the order crafts forever. Off
    * is vanilla exactly -- nothing is counted that the engine would not count -- which matters because this changes
    * when an order stops, and an order that stops early is a surprise of a different kind.
    *
    * <p>Read by the server, because that is where a settler's job selection runs. A client never reads it, so a
    * mismatch between the two cannot show anything wrong in an interface.
    */
   public boolean settlerOrderCountsUnits = true;

   public ArcaneStorageSettings() {
      // Safe this early: the table is a jar resource and needs no registry. load() is idempotent for exactly this
      // reason -- the loader constructs this object before init runs, and init loads the table again.
      CostTable.load();

      for (String key : CostTable.keys()) {
         this.costs.put(key, CostTable.rawDefault(key));
      }
   }

   @Override
   public void addSaveData(SaveData save) {
      save.addSafeString("theme", this.theme,
            "Interface style for this mod's windows: slate, dark, or vanilla to use the game's own");
      save.addSafeString("sortMode", this.sortMode,
            "How the terminal orders its grid: group, name or amount. Set from the terminal's sort button");
      save.addSafeString("storageGroupingMode", this.storageGroupingMode,
            "How deep the storage tab's category tree goes: none, coarse or fine");
      save.addSafeString("craftingGroupingMode", this.craftingGroupingMode,
            "How deep the crafting tab's category tree goes: none, coarse or fine");
      save.addBoolean("buildMenuCompat", this.buildMenuCompat,
            "Whether Arcane Storage offers the network to Build Menu for blueprint placement, when that mod is installed");
      save.addBoolean("settlerOrderCountsUnits", this.settlerOrderCountsUnits,
            "Whether a settler's 'craft until N' order counts items kept in this mod's Storage Units inside the"
               + " settlement. Set to false to count only settlement storage, as the game does on its own");

      SaveData reach = new SaveData("REACH");
      reach.addInt("demonic", this.wirelessRangeDemonic,
            "Tiles a Demonic wireless pairing reaches, on the transceiver's own level only");
      reach.addInt("tungsten", this.wirelessRangeTungsten,
            "Tiles a Tungsten pairing reaches on its own level. Negative for no limit");
      reach.addInt("fallen", this.wirelessRangeFallen,
            "Tiles a Fallen pairing reaches on its own level. Negative for no limit. Fallen also reaches other "
               + "levels, which is not configurable");
      save.addSaveData(reach);

      SaveData band = new SaveData("BAND");
      band.addInt("range", this.bandRange,
            "Tiles an Arcane Access Point may stand from its Base Station");
      band.addInt("channelsDemonic", this.bandChannelsDemonic,
            "Channels a Demonic Base Station's band offers -- one Access Point each");
      band.addInt("channelsTungsten", this.bandChannelsTungsten, "Channels a Tungsten Base Station offers");
      band.addInt("channelsFallen", this.bandChannelsFallen, "Channels a Fallen Base Station offers");
      save.addSaveData(band);

      // One section per cost, holding one line per ingredient, rather than a single comma-separated string. The
      // file's own format separates entries with commas and would need the value escaped -- and an escaped
      // ingredient list in a file meant to be edited by hand defeats the point of putting it there.
      // The warning rides on the section, which is the only place it reaches the one person who can cause the
      // problem, at the moment they are causing it. Recipes are built from this table when the mod registers them,
      // independently on each side, and the engine sends no recipe data -- so a server whose costs differ from a
      // client's has a client showing an ingredient list its server will refuse. Nothing detects that yet.
      SaveData costs = new SaveData("COSTS",
            "Recipe and upgrade costs. IF YOU CHANGE THESE ON A SERVER, every player connecting must use the same "
               + "values, or their game will show costs the server refuses. Distribute this file with the modpack.");
      for (Map.Entry<String, String> entry : this.costs.entrySet()) {
         SaveData one = new SaveData(entry.getKey());

         for (String pair : entry.getValue().split(",")) {
            String[] parts = pair.trim().split("\\s+");
            if (parts.length == 2) {
               one.addInt(parts[0], Integer.parseInt(parts[1]));
            }
         }

         costs.addSaveData(one);
      }

      save.addSaveData(costs);
   }

   @Override
   public void applyLoadData(LoadData save) {
      this.theme = arcanestorage.ui.ArcaneStyles.Theme.of(save.getSafeString("theme", this.theme)).settingValue();

      // Not validated against the enum here: the enum is a private member of the terminal's form, which is client
      // code, and this class is also constructed on a dedicated server. An unrecognised value is resolved to group
      // where it is read instead, so a hand-edited typo costs the preference rather than the load.
      this.sortMode = save.getSafeString("sortMode", this.sortMode);
      this.storageGroupingMode = save.getSafeString("storageGroupingMode", this.storageGroupingMode);
      this.craftingGroupingMode = save.getSafeString("craftingGroupingMode", this.craftingGroupingMode);
      this.buildMenuCompat = save.getBoolean("buildMenuCompat", this.buildMenuCompat);

      // Missing is normal rather than suspicious: every config file written before this setting existed lacks the
      // key, and the default it falls back to is the behaviour being introduced. So the loader is told not to warn.
      this.settlerOrderCountsUnits = save.getBoolean("settlerOrderCountsUnits", this.settlerOrderCountsUnits, false);

      LoadData reach = save.getFirstLoadDataByName("REACH");
      if (reach != null) {
         // Zero would be a transceiver that cannot be used from the tile it stands on, which is never what someone
         // meant to type; negative is the documented way to say unlimited, so the floor is -1 rather than 0.
         this.wirelessRangeDemonic = reach.getInt("demonic", this.wirelessRangeDemonic, -1, Integer.MAX_VALUE);
         this.wirelessRangeTungsten = reach.getInt("tungsten", this.wirelessRangeTungsten, -1, Integer.MAX_VALUE);
         this.wirelessRangeFallen = reach.getInt("fallen", this.wirelessRangeFallen, -1, Integer.MAX_VALUE);
      }

      LoadData band = save.getFirstLoadDataByName("BAND");
      if (band != null) {
         // A floor of 1 on both: a range of zero is an Access Point that must occupy the same tile as its station,
         // and a band with no channels is a Base Station that cannot do the one thing it is for. Neither is a
         // configuration anyone meant to write, and both would be silent.
         this.bandRange = band.getInt("range", this.bandRange, 1, Integer.MAX_VALUE);
         this.bandChannelsDemonic = band.getInt("channelsDemonic", this.bandChannelsDemonic, 1, 999);
         this.bandChannelsTungsten = band.getInt("channelsTungsten", this.bandChannelsTungsten, 1, 999);
         this.bandChannelsFallen = band.getInt("channelsFallen", this.bandChannelsFallen, 1, 999);
      }

      LoadData costs = save.getFirstLoadDataByName("COSTS");
      if (costs == null) {
         return;
      }

      for (String key : this.costs.keySet()) {
         LoadData one = costs.getFirstLoadDataByName(key);
         if (one == null) {
            continue;
         }

         StringBuilder rebuilt = new StringBuilder();

         for (LoadData ingredient : one.getLoadData()) {
            if (rebuilt.length() > 0) {
               rebuilt.append(", ");
            }

            rebuilt.append(ingredient.getName()).append(' ').append(ingredient.getData());
         }

         // An emptied section is left alone rather than treated as a free recipe. CostTable refuses the value
         // anyway, but not turning an editing mistake into a cost of nothing is worth doing twice.
         if (rebuilt.length() > 0) {
            this.costs.put(key, rebuilt.toString());
         }
      }
   }
}
