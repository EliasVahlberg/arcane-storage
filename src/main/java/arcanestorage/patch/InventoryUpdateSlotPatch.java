package arcanestorage.patch;

import arcanestorage.network.IndexedInventories;
import necesse.engine.modLoader.annotations.ModMethodPatch;
import necesse.inventory.Inventory;
import net.bytebuddy.asm.Advice;

/**
 * Reports every inventory change to the network index that cares about it.
 *
 * <p><b>Patching is the last resort here, not the first instinct, and the alternatives were checked.</b>
 * {@code Inventory.addSlotUpdateListener} exists and looks like the right seam, but {@code updateSlot} notifies
 * only the <i>first</i> listener in its list -- the source has an {@code if}, not a {@code while} -- so
 * attaching to a container this mod does not own would either be ignored or would steal the notification the
 * game's own form depends on. There is no change counter to poll either. That leaves the funnel every mutation
 * inside {@code Inventory} passes through.
 *
 * <p>Woven at method exit, because the slot's new contents are in place before {@code updateSlot} is called at
 * every one of its fifteen call sites -- {@code this.items[slot] = item;} then {@code this.updateSlot(slot);} --
 * so the hook can read what is there now and compare it with what the index last saw.
 *
 * <p>The signature is {@code updateSlot(int)} on {@code Inventory} itself, a public method on a concrete core
 * class. It is stable, but a method patch binds to an exact signature, so {@code IndexedInventories.verifyHook}
 * proves at load that the advice was woven in rather than assuming it. A patch that quietly fails to apply
 * would leave the index believing in items that are gone, with nothing in any log to say so.
 */
@ModMethodPatch(target = Inventory.class, name = "updateSlot", arguments = {int.class})
public class InventoryUpdateSlotPatch {

   @Advice.OnMethodExit
   static void onExit(@Advice.This Inventory inventory, @Advice.Argument(0) int slot) {
      IndexedInventories.slotChanged(inventory, slot);
   }
}
