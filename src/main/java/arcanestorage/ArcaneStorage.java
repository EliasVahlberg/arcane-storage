package arcanestorage;

import necesse.engine.modLoader.annotations.ModEntry;

/**
 * Arcane Storage — unified, searchable storage and crafting for Necesse.
 *
 * <p>Registration happens in {@link #init()} in the order the engine expects:
 * tiles, objects, items, containers, then packets. Recipes are registered in
 * {@link #postInit()} because they must resolve against every mod's items,
 * including ones loaded after this mod.
 *
 * <p>{@link #initResources()} is <b>client-only</b> — a dedicated server never
 * calls it, so nothing that affects game state may live there.
 */
@ModEntry
public class ArcaneStorage {

    /** Mod ID, matching {@code project.ext.modID} in build.gradle. */
    public static final String MOD_ID = "elias.arcanestorage";

    public void init() {
        // Registration goes here. See docs/MOD_BRIEF.md in the workspace for
        // the intended build order.
    }

    public void initResources() {
        // Client-only. Textures and sounds not auto-loaded from resources/.
    }

    public void postInit() {
        // Recipes, loot tables, chat commands.
    }
}
