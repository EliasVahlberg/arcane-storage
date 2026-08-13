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
import arcanestorage.object.StorageUnitObject;
import arcanestorage.objectentity.BusObjectEntity;
import arcanestorage.objectentity.ImportBusObjectEntity;
import arcanestorage.objectentity.StorageTerminalObjectEntity;
import necesse.engine.modLoader.annotations.ModEntry;
import necesse.engine.registries.ContainerRegistry;
import necesse.engine.registries.ObjectRegistry;
import necesse.engine.registries.RecipeTechRegistry;
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

   public void init() {
      ObjectRegistry.registerObject(TERMINAL_STRING_ID, new StorageTerminalObject(), 10.0F, true);
      ObjectRegistry.registerObject(UNIT_STRING_ID, new StorageUnitObject(), 10.0F, true);
      CONDUIT = new StorageConduitObject();
      ObjectRegistry.registerObject(CONDUIT_STRING_ID, CONDUIT, 4.0F, true);
      ObjectRegistry.registerObject(IMPORT_BUS_STRING_ID, new ImportBusObject(), 8.0F, true);
      ObjectRegistry.registerObject(EXPORT_BUS_STRING_ID, new ExportBusObject(), 8.0F, true);

      TERMINAL_CONTAINER = ContainerRegistry.registerOEContainer(
         (client, uniqueSeed, objectEntity, content) -> new StorageTerminalContainerForm<>(
            client, new StorageTerminalContainer(client.getClient(), uniqueSeed, (StorageTerminalObjectEntity)objectEntity)
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
   }

   public void initResources() {
      // Client-only. objects/arcanestorageterminal.png is loaded automatically by
      // InventoryObject.loadTextures, so there is nothing to load by hand yet.
   }

   public void postInit() {
      // Proven rather than assumed, and at load rather than when it matters. A method patch binds to an exact
      // signature, so a game update can stop it applying -- and the consequence is invisible: an index that
      // believes in items somebody carried off, with nothing in any log. The check costs one throwaway
      // inventory. When it fails the index falls back to recounting on a timer, which is slower but correct.
      IndexedInventories.recordVerification(IndexedInventories.verifyHook());

      // Placeholder costs so both objects can be obtained in a fresh world for testing.
      // Progression and balance are Phase 6.
      Recipes.registerModRecipe(
         new Recipe(TERMINAL_STRING_ID, 1, RecipeTechRegistry.NONE, new Ingredient[]{new Ingredient("anylog", 8)})
      );
      Recipes.registerModRecipe(
         new Recipe(UNIT_STRING_ID, 1, RecipeTechRegistry.NONE, new Ingredient[]{new Ingredient("anylog", 8)})
      );
      Recipes.registerModRecipe(
         new Recipe(CONDUIT_STRING_ID, 4, RecipeTechRegistry.NONE, new Ingredient[]{new Ingredient("anylog", 2)})
      );
      Recipes.registerModRecipe(
         new Recipe(IMPORT_BUS_STRING_ID, 1, RecipeTechRegistry.NONE,
            new Ingredient[]{new Ingredient("anylog", 4), new Ingredient("ironbar", 2)})
      );
      Recipes.registerModRecipe(
         new Recipe(EXPORT_BUS_STRING_ID, 1, RecipeTechRegistry.NONE,
            new Ingredient[]{new Ingredient("anylog", 4), new Ingredient("ironbar", 2)})
      );

      // Drives the scenario harness. Registered unconditionally: it needs owner permission,
      // and being present in a normal game is harmless.
      // Adds this mod's verbs and assertions to the harness's command, when the harness is
      // installed. There is no command of our own any more: every verb was either generic enough to
      // belong to the harness or specific enough to register into it.
      //
      // THE GUARD MUST BE HERE, at the call site, and not inside ArcaneStorageVerbs. The harness is
      // an optional dependency, and when it is absent the JVM throws NoClassDefFoundError while
      // *resolving* ArcaneStorageVerbs -- before a single line of its code runs, so a try/catch
      // written inside it never executes. Verified by booting a server with the harness removed: the
      // error escaped, and the mod loader then died with an unrelated-looking NullPointerException
      // about a null dispose method, which is what this failure looks like from the outside.
      //
      // Throwable rather than Exception, because NoClassDefFoundError is an Error.
      try {
         arcanestorage.harness.ArcaneStorageVerbs.register();
      } catch (Throwable harnessAbsent) {
         // Expected for the release jar, which excludes arcanestorage/harness/** -- so this says which
         // build is running rather than implying the harness is missing from the game. It logged
         // "harness not present" in a session where the harness plainly had loaded, which is
         // confusing at exactly the wrong moment.
         System.out.println("Arcane Storage: built without the harness bridge (use 'make testjar' for the"
               + " test build); test verbs not registered ("
            + harnessAbsent.getClass().getSimpleName() + ")");
      }
   }
}
