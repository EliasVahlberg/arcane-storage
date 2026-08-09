package arcanestorage;

import arcanestorage.command.ArcaneStorageCommand;
import arcanestorage.container.StorageTerminalContainer;
import arcanestorage.container.StorageTerminalContainerForm;
import arcanestorage.object.StorageTerminalObject;
import arcanestorage.object.StorageConduitObject;
import arcanestorage.object.StorageUnitObject;
import arcanestorage.objectentity.StorageTerminalObjectEntity;
import necesse.engine.commands.CommandsManager;
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

   public void init() {
      ObjectRegistry.registerObject(TERMINAL_STRING_ID, new StorageTerminalObject(), 10.0F, true);
      ObjectRegistry.registerObject(UNIT_STRING_ID, new StorageUnitObject(), 10.0F, true);
      CONDUIT = new StorageConduitObject();
      ObjectRegistry.registerObject(CONDUIT_STRING_ID, CONDUIT, 4.0F, true);

      TERMINAL_CONTAINER = ContainerRegistry.registerOEContainer(
         (client, uniqueSeed, objectEntity, content) -> new StorageTerminalContainerForm<>(
            client, new StorageTerminalContainer(client.getClient(), uniqueSeed, (StorageTerminalObjectEntity)objectEntity)
         ),
         (client, uniqueSeed, objectEntity, content, serverObject) -> new StorageTerminalContainer(
            client, uniqueSeed, (StorageTerminalObjectEntity)objectEntity
         )
      );
   }

   public void initResources() {
      // Client-only. objects/arcanestorageterminal.png is loaded automatically by
      // InventoryObject.loadTextures, so there is nothing to load by hand yet.
   }

   public void postInit() {
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

      // Drives the scenario harness. Registered unconditionally: it needs owner permission,
      // and being present in a normal game is harmless.
      CommandsManager.registerServerCommand(new ArcaneStorageCommand());
   }
}
