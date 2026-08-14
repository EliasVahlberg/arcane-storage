package arcanestorage.remote;

import java.util.ArrayList;
import java.util.List;
import necesse.engine.network.PacketReader;
import necesse.engine.network.PacketWriter;
import necesse.inventory.InventoryItem;
import necesse.inventory.container.events.ContainerEvent;

/**
 * Slots that have changed since the remote client was last told, pushed from the server.
 *
 * <p>A batch rather than one event per slot: a single deposit-all moves dozens of slots in one tick, and forty
 * events would be forty packets carrying four bytes of payload each.
 *
 * <p>Push rather than poll, for the same reason the upgrade panel pushes: a player watching a remote terminal
 * while a settler hauls something into it should see the number move, and the mod already has one place that
 * knows an inventory changed ({@code IndexedInventories.slotChanged}). Asking the network what it holds on a
 * timer would be both later and more expensive.
 */
public class SlotMirrorEvent extends ContainerEvent {

   public final int[] indices;

   public final InventoryItem[] items;

   public SlotMirrorEvent(int[] indices, InventoryItem[] items) {
      this.indices = indices;
      this.items = items;
   }

   public SlotMirrorEvent(PacketReader reader) {
      int count = reader.getNextShortUnsigned();
      this.indices = new int[count];
      this.items = new InventoryItem[count];
      for (int i = 0; i < count; i++) {
         this.indices[i] = reader.getNextShortUnsigned();
         this.items[i] = InventoryItem.fromContentPacket(reader.getNextContentPacket());
      }
   }

   @Override
   public void write(PacketWriter writer) {
      writer.putNextShortUnsigned(this.indices.length);
      for (int i = 0; i < this.indices.length; i++) {
         writer.putNextShortUnsigned(this.indices[i]);
         writer.putNextContentPacket(InventoryItem.getContentPacket(this.items[i]));
      }
   }

   /** Collects changed slots, so a tick's worth of movement becomes one event. */
   public static final class Batch {

      private final List<Integer> indices = new ArrayList<>();

      private final List<InventoryItem> items = new ArrayList<>();

      public void add(int index, InventoryItem item) {
         this.indices.add(index);
         this.items.add(item);
      }

      public boolean isEmpty() {
         return this.indices.isEmpty();
      }

      public SlotMirrorEvent toEvent() {
         int[] indexArray = new int[this.indices.size()];
         InventoryItem[] itemArray = new InventoryItem[this.items.size()];
         for (int i = 0; i < indexArray.length; i++) {
            indexArray[i] = this.indices.get(i);
            itemArray[i] = this.items.get(i);
         }

         return new SlotMirrorEvent(indexArray, itemArray);
      }
   }
}
