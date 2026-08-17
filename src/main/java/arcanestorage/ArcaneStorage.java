package arcanestorage;

import arcanestorage.container.BusContainer;
import arcanestorage.container.BusContainerForm;
import arcanestorage.container.StorageTerminalContainer;
import arcanestorage.container.StorageTerminalContainerForm;
import arcanestorage.network.IndexedInventories;
import arcanestorage.object.StorageTerminalObject;
import arcanestorage.object.ExportBusObject;
import arcanestorage.object.ImportBusObject;
import arcanestorage.object.StorageConduitObject;
import arcanestorage.object.StationUnitObject;
import arcanestorage.object.StorageUnitObject;
import arcanestorage.object.UnitTier;
import arcanestorage.recipe.CostTable;
import arcanestorage.objectentity.BusObjectEntity;
import arcanestorage.objectentity.ImportBusObjectEntity;
import arcanestorage.objectentity.StorageTerminalObjectEntity;
import arcanestorage.ui.ArcanePanel;
import necesse.engine.modLoader.annotations.ModEntry;
import necesse.engine.registries.ContainerRegistry;
import necesse.engine.registries.ItemRegistry;
import necesse.engine.registries.ObjectRegistry;
import necesse.engine.registries.RecipeTechRegistry;
import necesse.inventory.item.Item;
import necesse.inventory.item.ItemCategory;
import necesse.level.gameObject.GameObject;
import necesse.inventory.recipe.Ingredient;
import necesse.inventory.recipe.Recipe;
import necesse.inventory.recipe.Recipes;

/**
 * Arcane Storage — unified, searchable storage and crafting for Necesse.
 *
 * <p>{@link #initResources()} is <b>client-only</b> — a dedicated server never calls it,
 * so nothing that affects game state may live there.
 */
@ModEntry
public class ArcaneStorage {

   /** Mod ID, matching {@code project.ext.modID} in build.gradle. */
   public static final String MOD_ID = "elias.arcanestorage";

   /** Registry string ID of the terminal object; also its texture and locale key. */
   public static final String TERMINAL_STRING_ID = "arcanestorageterminal";

   /** Registry string ID of the storage unit object; also its texture and locale key. */
   public static final String UNIT_STRING_ID = "arcanestorageunit";

   /** Registry string ID of the station unit object; also its texture and locale key. */
   public static final String STATION_UNIT_STRING_ID = "arcanestoragestationunit";

   /** Registry string ID of the conduit object; also its texture and locale key. */
   public static final String CONDUIT_STRING_ID = "arcanestorageconduit";

   /** Registry string ID of the import bus: a neighbouring container's contents flow into the network. */
   public static final String IMPORT_BUS_STRING_ID = "arcanestorageimportbus";

   /** Registry string ID of the export bus: the network's contents flow out, on a rule. */
   public static final String EXPORT_BUS_STRING_ID = "arcanestorageexportbus";

   /**
    * The registered conduit, kept so the traversal can match it by object ID.
    *
    * <p>A conduit has no object entity to test for, because it has no state, so recognising
    * one means comparing the object ID at a tile.
    */
   public static StorageConduitObject CONDUIT;

   /**
    * Container IDs are assigned sequentially by {@code ContainerRegistry}, which takes no
    * string ID — every container registers under the literal name "container". They are
    * never written to disk, only sent in packets, so this is safe as long as client and
    * server load the same mods in the same order.
    */
   public static int TERMINAL_CONTAINER = -1;

   /** Shared by both buses: the panel differs only in its two labels. */
   public static int BUS_CONTAINER = -1;

   /** The in-place upgrade panel, opened by right-clicking any tiered unit. */
   public static int UPGRADE_CONTAINER = -1;

   /** The wireless terminal's container. Registered plain, not as an OE container -- see RemoteTerminalContainer. */
   public static int REMOTE_TERMINAL_CONTAINER = -1;

   /** The Base Station's channel list. A view with no actions at all. */
   public static int BASE_STATION_CONTAINER = -1;

   /** The Access Point's band and channel panel. */
   public static int ACCESS_POINT_CONTAINER = -1;

   public static final String WIRELESS_TERMINAL_STRING_ID = "arcanestoragewirelessterminal";

   /**
    * The mod's client settings, handed to the loader from {@link #initSettings()} and persisted by
    * the game. Static because the UI reads it and there is exactly one.
    */
   public static ArcaneStorageSettings SETTINGS = new ArcaneStorageSettings();

   /**
    * Called by name by the mod loader, which then saves and loads what is returned.
    */
   public ArcaneStorageSettings initSettings() {
      return SETTINGS;
   }

   /**
    * The one category this mod's content lives in, in both the item tree and the crafting tree.
    *
    * <p>Flat rather than nested, and that is a deliberate choice with a cost. Nesting into storage, logistics and
    * wireless would read better in a long crafting list, but the terminal's own category filter is built from the
    * item tree, so nesting there would split this mod across three filter entries where one is what a player wants.
    * Nesting is additive and can be done later without moving anything; splitting a filter entry cannot be undone
    * for someone who has learned where to click.
    */
   public static final String CATEGORY = "arcanestorage";

   /**
    * Creates the mod's category in both trees, before anything is registered into it.
    *
    * <p>Order is not a style preference here. {@code ItemCategoryManager.getCategory} <b>throws</b>
    * {@code IllegalStateException} for a tree it does not know, and an object carries its category tree as plain
    * strings that are resolved when its item is generated during registration -- so a missing create is a crash at
    * load rather than a category that quietly fails to appear.
    *
    * <p>The sort strings are chosen to move nothing vanilla. Vanilla's item tree uses single letters A through G and
    * then Z for misc, so {@code H-A-A} lands after mobs and before misc. Vanilla's crafting tree runs to {@code
    * I-A-A} for misc and then pins its station list at {@code Z-Z-Z}, so {@code J-A-A} lands after misc and still
    * ahead of that pinned block. Touching {@code ItemCategory} at all is what forces its static initialiser to run,
    * so vanilla's whole tree is guaranteed built before either call.
    */
   private static void createCategories() {
      ItemCategory.masterManager.createCategory("H-A-A", CATEGORY);
      ItemCategory.craftingManager.createCategory("J-A-A", CATEGORY);
   }

   /**
    * Registers an object into this mod's category.
    *
    * <p>Exists so that adding an object cannot forget the category. Every object this mod ships is storage or
    * logistics, so there is no case where the default {@code objects} tree is the right answer, and a helper is
    * cheaper than a test that checks each call site.
    *
    * <p>The map flag is always true, matching every call this replaced.
    */
   private static void registerObject(String stringID, GameObject object, float lightLevel) {
      object.setItemCategory(CATEGORY);
      object.setCraftingCategory(CATEGORY);
      ObjectRegistry.registerObject(stringID, object, lightLevel, true);
   }

   /**
    * Registers an item into this mod's category, for the items that are not placeable objects.
    *
    * <p>A recipe with no category override of its own is grouped by its <b>result item's</b> crafting category
    * ({@code CraftingStationContainerForm:339-341}), so setting it here is what makes the recipe appear under Arcane
    * Storage. No recipe needs {@code setCraftingCategory} called on it.
    */
   private static void registerItem(String stringID, Item item, float brokerValue) {
      item.setItemCategory(CATEGORY);
      item.setItemCategory(ItemCategory.craftingManager, CATEGORY);
      ItemRegistry.registerItem(stringID, item, brokerValue, true);
   }

   public void init() {
      // Before anything can ask what something costs. Reading the file here rather than on first use means a
      // malformed entry stops the mod at startup, with the offending key named, instead of surfacing whenever a
      // player first opens an upgrade panel.
      CostTable.load();

      // Then anything the config file overrides, applied here rather than where the file is read: the settings load
      // before preInit, when the item registry does not exist yet, so a bad ingredient ID has to fail at
      // registration where it can be named. An unusable override is refused and logged, not fatal -- a typo in a
      // file the player edits should cost them that line and not the mod.
      for (java.util.Map.Entry<String, String> override : SETTINGS.costs.entrySet()) {
         CostTable.override(override.getKey(), override.getValue());
      }

      createCategories();

      registerObject(TERMINAL_STRING_ID, new StorageTerminalObject(), 10.0F);
      // Every rung of both ladders. The base tier keeps the original string IDs, which must never change,
      // and the upper three append their era. Registered in tier order so the crafting list reads as a ladder.
      for (UnitTier tier : UnitTier.values()) {
         registerObject(tier.storageId(), new StorageUnitObject(tier), 10.0F);
         registerObject(tier.stationId(), new StationUnitObject(tier), 10.0F);
      }
      CONDUIT = new StorageConduitObject();
      registerObject(CONDUIT_STRING_ID, CONDUIT, 4.0F);
      registerObject(IMPORT_BUS_STRING_ID, new ImportBusObject(), 8.0F);
      registerObject(EXPORT_BUS_STRING_ID, new ExportBusObject(), 8.0F);

      // The wireless ladder: three rungs of each, from the Demonic era up. The bottom rung keeps the unsuffixed
      // string IDs it was first registered under -- see UnitTier.wirelessSuffix -- and BASE has no rung at all.
      for (UnitTier tier : UnitTier.values()) {
         if (!tier.hasWireless()) {
            continue;
         }

         registerItem(tier.wirelessTerminalId(),
               new arcanestorage.remote.WirelessTerminalItem(tier), 200.0F);
         registerObject(tier.transceiverId(),
               new arcanestorage.object.WirelessTransceiverObject(tier), 12.0F);
         registerObject(tier.baseStationId(),
               new arcanestorage.object.ArcaneBaseStationObject(tier), 12.0F);
      }

      // One rung: an Access Point has nothing a tier could buy. See the class comment.
      registerObject(arcanestorage.object.ArcaneAccessPointObject.STRING_ID,
            new arcanestorage.object.ArcaneAccessPointObject(), 10.0F);

      // The band's own state, which is per level rather than per device -- see BandIndex. Registered here because
      // LevelDataRegistry closes with the others; the data itself is attached to a level on demand, the first time a
      // Base Station asks, so a world that predates this mod acquires one without migration.
      necesse.engine.registries.LevelDataRegistry.registerLevelData(
            arcanestorage.band.BandIndex.KEY, arcanestorage.band.BandIndex.class);

      // Registered with the plain variant on purpose. Every typed variant resolves the level as the player's own
      // (registerLevelContainer: client.getLevel() / world.getLevel(client)), which is the one assumption a
      // wireless terminal breaks -- so this one carries its level in the content packet and resolves it itself.
      REMOTE_TERMINAL_CONTAINER = ContainerRegistry.registerContainer(
         (client, uniqueSeed, content) -> new StorageTerminalContainerForm<>(
            client, new arcanestorage.remote.RemoteTerminalContainer(client.getClient(), uniqueSeed, content)
         ),
         (client, uniqueSeed, content, serverObject) -> new arcanestorage.remote.RemoteTerminalContainer(
            client, uniqueSeed, content
         )
      );

      TERMINAL_CONTAINER = ContainerRegistry.registerOEContainer(
         (client, uniqueSeed, objectEntity, content) -> new StorageTerminalContainerForm<>(
            client, new StorageTerminalContainer(client.getClient(), uniqueSeed, (StorageTerminalObjectEntity)objectEntity, content)
         ),
         (client, uniqueSeed, objectEntity, content, serverObject) -> new StorageTerminalContainer(
            client, uniqueSeed, (StorageTerminalObjectEntity)objectEntity
         )
      );

      // One container for both buses. They differ in which way items flow, which the entity decides, and
      // in two labels, which the form is told -- not in anything the container does.
      BUS_CONTAINER = ContainerRegistry.registerOEContainer(
         (client, uniqueSeed, objectEntity, content) -> {
            BusObjectEntity bus = (BusObjectEntity)objectEntity;
            boolean isImport = bus instanceof ImportBusObjectEntity;
            return new BusContainerForm<>(
               client, new BusContainer(client.getClient(), uniqueSeed, bus, content),
               isImport ? IMPORT_BUS_STRING_ID : EXPORT_BUS_STRING_ID,
               isImport ? "arcanestorage_importbusrule" : "arcanestorage_exportbusrule",
               isImport ? "arcanestorage_importbuslimit" : "arcanestorage_exportbuslimit"
            );
         },
         (client, uniqueSeed, objectEntity, content, serverObject) -> new BusContainer(
            client, uniqueSeed, (BusObjectEntity)objectEntity, content
         )
      );

      // One container for both ladders and every rung. What differs between a Storage Unit and a Station Unit
      // here is a label and which string ID the next tier is, both of which the container reads off the tile.
      UPGRADE_CONTAINER = ContainerRegistry.registerOEContainer(
         (client, uniqueSeed, objectEntity, content) -> {
            arcanestorage.upgrade.UnitUpgradeContainer container =
               new arcanestorage.upgrade.UnitUpgradeContainer(client.getClient(), uniqueSeed, objectEntity, content);
            // The tile says what it is. See UnitUpgrade.nameKeyAt: this used to pick between two names on a
            // boolean, and headed a Wireless Transceiver's panel "Demonic Storage Unit".
            return new arcanestorage.upgrade.UnitUpgradeContainerForm<>(
               client, container,
               container.nameKey == null ? UNIT_STRING_ID : container.nameKey
            );
         },
         (client, uniqueSeed, objectEntity, content, serverObject) ->
            new arcanestorage.upgrade.UnitUpgradeContainer(client, uniqueSeed, objectEntity, content)
      );

      // The band's two panels. Both are OE containers: each is opened by right-clicking a tile, and the entity at
      // that tile is what the panel is about.
      BASE_STATION_CONTAINER = ContainerRegistry.registerOEContainer(
         (client, uniqueSeed, objectEntity, content) -> new arcanestorage.container.BaseStationContainerForm<>(
            client, new arcanestorage.container.BaseStationContainer(client.getClient(), uniqueSeed,
                  (arcanestorage.objectentity.ArcaneBaseStationObjectEntity)objectEntity)
         ),
         (client, uniqueSeed, objectEntity, content, serverObject) -> new arcanestorage.container.BaseStationContainer(
            client, uniqueSeed, (arcanestorage.objectentity.ArcaneBaseStationObjectEntity)objectEntity
         )
      );

      ACCESS_POINT_CONTAINER = ContainerRegistry.registerOEContainer(
         (client, uniqueSeed, objectEntity, content) -> new arcanestorage.container.AccessPointContainerForm<>(
            client, new arcanestorage.container.AccessPointContainer(client.getClient(), uniqueSeed,
                  (arcanestorage.objectentity.ArcaneAccessPointObjectEntity)objectEntity, content)
         ),
         (client, uniqueSeed, objectEntity, content, serverObject) -> new arcanestorage.container.AccessPointContainer(
            client, uniqueSeed, (arcanestorage.objectentity.ArcaneAccessPointObjectEntity)objectEntity, content
         )
      );

      // Registered so the panel's numbers can be pushed rather than polled. The event carries derived totals,
      // which is the one thing the engine's container-slot synchronisation cannot deliver on its own -- a sum
      // has no slot to travel with. Vanilla registers its shop stock and wealth updates for the same reason.
      necesse.inventory.container.events.ContainerEventRegistry.registerUpdate(
         arcanestorage.upgrade.UpgradeStateEvent.class);

      // The wireless terminal's slot mirror. Registered for the same reason and with a sharper one behind it: a
      // remote client has no other route to a network's contents at all, since OEInventory syncs through
      // sendToClientsWithEntity, which is proximity-based.
      necesse.inventory.container.events.ContainerEventRegistry.registerUpdate(
         arcanestorage.remote.SlotMirrorEvent.class);
      necesse.inventory.container.events.ContainerEventRegistry.registerUpdate(
         arcanestorage.remote.BusMirrorEvent.class);
   }

   public void initResources() {
      // Client-only, which is why the panel is loaded here and not in init(): a dedicated server never
      // calls this, never builds a form, and has no business holding UI textures. Object and item
      // textures still need no hand-loading -- InventoryObject.loadTextures finds those by string ID.
      arcanestorage.ui.ArcaneStyles.load();
   }

   public void postInit() {
      // Proven rather than assumed, and at load rather than when it matters. A method patch binds to an exact
      // signature, so a game update can stop it applying -- and the consequence is invisible: an index that
      // believes in items somebody carried off, with nothing in any log. The check costs one throwaway
      // inventory. When it fails the index falls back to recounting on a timer, which is slower but correct.
      IndexedInventories.recordVerification(IndexedInventories.verifyHook());

      // Every cost below comes from resources/recipes.properties. None of these numbers are written in Java, and
      // the reasoning for each is in that file next to the value it explains.
      Recipes.registerModRecipe(
         new Recipe(TERMINAL_STRING_ID, CostTable.count("recipe.terminal"), RecipeTechRegistry.WORKSTATION,
            CostTable.materials("recipe.terminal"))
      );

      // The wireless ladder. Made at the era's workstation rather than by hand, unlike the earlier single-tier
      // version: a tiered recipe belongs where the other recipes of its tier are, and the station is where a player
      // goes when they have just mined the gemstone these ask for. Each upper rung consumes the one below, so a
      // player upgrades their terminal rather than accumulating three.
      for (UnitTier tier : UnitTier.values()) {
         if (!tier.hasWireless()) {
            continue;
         }

         UnitTier below = tier.below();
         boolean hasRungBelow = below != null && below.hasWireless();

         Recipes.registerModRecipe(new Recipe(
            tier.wirelessTerminalId(), 1, tier.tech(),
            UnitTier.withPrevious(CostTable.materials(tier.wirelessTerminalCostKey()),
                  hasRungBelow ? below.wirelessTerminalId() : null)
         ));
         Recipes.registerModRecipe(new Recipe(
            tier.transceiverId(), 1, tier.tech(),
            UnitTier.withPrevious(CostTable.materials(tier.transceiverCostKey()),
                  hasRungBelow ? below.transceiverId() : null)
         ));
         Recipes.registerModRecipe(new Recipe(
            tier.baseStationId(), 1, tier.tech(),
            UnitTier.withPrevious(CostTable.materials(tier.baseStationCostKey()),
                  hasRungBelow ? below.baseStationId() : null)
         ));
      }

      // Made at a plain Workstation rather than at the tier's own, unlike the station it tunes to. It is the part a
      // player places once per outbuilding, and sending them back to a Fallen workstation to make one more would tax
      // building out rather than cost anything meaningful.
      Recipes.registerModRecipe(new Recipe(
         arcanestorage.object.ArcaneAccessPointObject.STRING_ID, CostTable.count("recipe.accesspoint"),
         RecipeTechRegistry.WORKSTATION, CostTable.materials("recipe.accesspoint")
      ));

      // Both ladders, each rung consuming the one below it. Registered from a table rather than written out
      // eight times, so a change to the curve is a change in one place and the two ladders cannot drift apart.
      for (UnitTier tier : UnitTier.values()) {
         UnitTier below = tier.below();
         Recipes.registerModRecipe(new Recipe(
            tier.storageId(), 1, tier.tech(),
            tier.ingredients(below == null ? null : below.storageId())
         ));
         Recipes.registerModRecipe(new Recipe(
            tier.stationId(), 1, tier.tech(),
            tier.ingredients(below == null ? null : below.stationId())
         ));
      }
      // Made at a Workstation, like everything else here. These were hand recipes in 1.0.0 on the reasoning that a
      // player lays dozens of conduits and a walk back to a bench would tax building out -- but a hand recipe is not
      // a discount, it is an absent station, and it put the three most-used parts of the mod in the one crafting
      // menu that has no category and no search. Every workstation tier is cumulative (a Fallen Workstation reports
      // FALLEN, ADVANCED, DEMONIC and WORKSTATION), so no tier loses access by this being the base rung.
      Recipes.registerModRecipe(
         new Recipe(CONDUIT_STRING_ID, CostTable.count("recipe.conduit"), RecipeTechRegistry.WORKSTATION,
            CostTable.materials("recipe.conduit"))
      );
      Recipes.registerModRecipe(
         new Recipe(IMPORT_BUS_STRING_ID, CostTable.count("recipe.importbus"), RecipeTechRegistry.WORKSTATION,
            CostTable.materials("recipe.importbus"))
      );
      Recipes.registerModRecipe(
         new Recipe(EXPORT_BUS_STRING_ID, CostTable.count("recipe.exportbus"), RecipeTechRegistry.WORKSTATION,
            CostTable.materials("recipe.exportbus"))
      );

      // The harness's own verbs and assertions live in arcanestorage.harness and are not called from here. The harness
      // finds them itself: buildModJar ships that package as harnessbridge/**.classdata resources rather than as
      // classes, and necesseheadlessharness.ModBridges defines them and calls register() when the harness is running.
      //
      // Nothing is registered from this method because nothing can be. Those classes implement the harness's
      // interfaces, and a class whose interfaces cannot be resolved cannot be defined -- which is why they are hidden
      // from the mod loader in the first place. Reaching them from here would mean reflecting into a class loader the
      // harness owns, to do work the harness has already done.
   }
}
