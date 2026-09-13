package arcanestorage.ui;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

import necesse.engine.GameLog;
import necesse.entity.mobs.PlayerMob;
import necesse.gfx.GameResources;
import necesse.gfx.gameFont.FontManager;
import necesse.gfx.gameFont.FontOptions;
import necesse.inventory.InventoryItem;
import necesse.inventory.item.Item;

/**
 * Draws items belonging to other mods without letting a broken one take the client down.
 *
 * <p><b>Why this exists at all, given that no vanilla UI needs it.</b> A crafting station draws recipes for
 * its own techs; an inventory form draws the items the player is holding. Both are narrow, and both only ever
 * meet items the player has already seen elsewhere. This mod's terminal is different in kind: its recipe tab
 * streams {@code RecipeTechRegistry.ALL} and its grid shows whatever a network happens to contain, so between
 * them they will draw every item every installed mod registers. That makes us the first UI in the game to meet
 * an item nothing else ever renders -- and the first to crash on one.
 *
 * <p><b>The failure mode, from a 1.1.1 report.</b> {@code Item.getItemSprite} is
 * {@code new GameSprite(this.itemTexture)}, and that constructor reads {@code texture.getWidth()} straight
 * away, so an item whose texture never loaded throws NPE inside {@code GameSprite.<init>}. Note what this is
 * <i>not</i>: a missing PNG is harmless, because {@code GameTexture.fromFile} catches
 * {@code FileNotFoundException} and returns the pink placeholder. A null texture means
 * {@code loadItemTextures} never ran for that item, and there are two ways that happens -- an item built
 * outside {@code ItemRegistry} is never visited at all, or {@code GameResources.loadTextures} aborted partway
 * through, since its {@code for (Item item : ItemRegistry.getItems())} loop has no per-item try/catch and so
 * one item throwing leaves <i>every item after it in registry order</i> textureless.
 *
 * <p>That second route is why attributing such a crash to the mod that appears in the stack trace is usually
 * wrong, and why the log line below names the item: the offender is whichever mod threw during startup, which
 * may be nowhere near the item that ends up drawing.
 *
 * <p><b>Failures are remembered, not retried.</b> Draw runs every frame, so an unremembered failure means an
 * exception sixty times a second and a log line with it. Worse, the vanilla components register their hover
 * tooltips with {@code GameTooltipManager}, which renders them <i>after</i> the draw call returns -- outside
 * any try/catch we could put around it. So a cell known to be broken is skipped before it can register a
 * tooltip, rather than attempted and caught.
 */
public final class ItemDrawGuard {

   /**
    * Keyed by string ID rather than by {@link Item} instance, because a null texture is a property of the
    * game's load order and outlives any one component -- the forms rebuild their cells on every filter change
    * and search keystroke, and rediscovering the same breakage each time would defeat the point.
    */
   private static final Set<String> BROKEN = Collections.synchronizedSet(new HashSet<>());

   private ItemDrawGuard() {
   }

   /**
    * Draws a stack's icon and amount, returning whether the icon made it.
    *
    * <p>On failure the amount is still drawn, which matters for the storage grid: the icon and the count come
    * from the same vanilla call, so a bare catch would leave a cell looking empty when it is not. An invisible
    * stack is worse than an ugly one -- the player cannot tell there is anything there to withdraw.
    */
   public static boolean drawStack(InventoryItem stack, PlayerMob perspective, int x, int y) {
      if (stack == null) {
         return false;
      }

      String id = idOf(stack.item);
      if (!BROKEN.contains(id)) {
         try {
            stack.draw(perspective, x, y);
            return true;
         } catch (Throwable t) {
            report(id, "a stored stack", t);
         }
      }

      drawFallback(stack, x, y);
      return false;
   }

   /** Whether this item has already failed to draw once, and should not be attempted again. */
   public static boolean isBroken(Item item) {
      return BROKEN.contains(idOf(item));
   }

   /**
    * Records an item as undrawable and names it in the log, once.
    *
    * <p>The message is written for whoever reads it in a bug report rather than for us: it says which item,
    * that the item's own mod is not necessarily at fault, and that the rest of the mod still works. Silence
    * here would be the worst outcome -- the crash at least told somebody something.
    */
   public static void report(String id, String context, Throwable cause) {
      if (BROKEN.add(id)) {
         GameLog.warn.println("Arcane Storage: " + id + " could not be drawn as " + context
               + " and will be left blank, because " + cause
               + ". Everything else keeps working, and the item is still usable -- only its icon is missing."
               + " This is an asset fault in some installed mod, not necessarily the one owning this item:"
               + " if an item throws while textures load, every item registered after it loses its texture"
               + " too. Please report it with this line.");
      }
   }

   /** An item's string ID, or a placeholder -- this is called from failure paths and must not throw. */
   public static String idOf(Item item) {
      try {
         return item == null ? "<null item>" : item.getStringID();
      } catch (Throwable ignored) {
         return "<unidentifiable item>";
      }
   }

   /**
    * Draws the engine's own missing-asset placeholder and the stack count, for a cell whose icon failed.
    *
    * <p>The pink {@code [ER]} texture rather than a blank space, and that choice is not cosmetic. It is
    * already what this game shows for an item whose PNG is missing, since {@code GameTexture.fromFile} falls
    * back to it, so a player who has ever seen a broken texture in Necesse recognises this one immediately and
    * correctly. A blank cell would instead read as an empty slot, which is actively misleading here because
    * the item is present and can still be taken out. Drawn at its native size for the same reason: that is
    * how the placeholder appears when it arrives through the normal item path.
    *
    * <p>The count is reproduced from {@code InventoryItem.draw}'s own layout, same font and same
    * right-alignment inside 32px, so a broken cell still lines up with its neighbours. Spoil colouring is
    * left out, since a stack with no icon has a more pressing problem than its freshness.
    */
   private static void drawFallback(InventoryItem stack, int x, int y) {
      try {
         GameResources.error.initDraw().draw(x, y);

         int amount = stack.getAmount();
         if (amount > 1) {
            FontOptions options = Item.tipFontOptions;
            String text = String.valueOf(amount);
            int width = FontManager.bit.getWidthCeil(text, options);
            FontManager.bit.drawString(x + 32 - width, y, text, options);
         }
      } catch (Throwable ignored) {
         // Nothing left to fall back to, and this is already the failure path.
      }
   }
}
