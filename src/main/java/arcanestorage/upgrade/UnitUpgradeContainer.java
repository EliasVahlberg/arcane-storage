package arcanestorage.upgrade;

import arcanestorage.network.NetworkStorage;
import arcanestorage.object.UnitTier;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import necesse.engine.network.NetworkClient;
import necesse.engine.network.Packet;
import necesse.engine.network.PacketReader;
import necesse.engine.network.PacketWriter;
import necesse.engine.network.packet.PacketOpenContainer;
import necesse.engine.network.server.ServerClient;
import necesse.engine.registries.ContainerRegistry;
import necesse.entity.objectEntity.InventoryObjectEntity;
import necesse.entity.objectEntity.ObjectEntity;
import necesse.inventory.Inventory;
import necesse.inventory.PlayerInventory;
import necesse.inventory.container.Container;
import necesse.inventory.container.customAction.ContainerCustomAction;
import necesse.inventory.recipe.Ingredient;
import necesse.level.maps.Level;

/**
 * The right-click panel for upgrading a unit in place.
 *
 * <h2>Why this pushes rather than polls</h2>
 *
 * <p>The panel shows two things that change without the player touching this interface: how full the unit is,
 * and how many of each material they can reach. Both are sums, and a sum has no slot to be synchronised with --
 * which is the whole difference between this and the terminal. The terminal's slots <i>are</i> the unit
 * inventories, so when one player deposits, the engine's own container-slot synchronisation delivers it to every
 * other player with the terminal open, and nothing in this mod is involved. Nothing in the engine syncs a
 * derived total, so this one is sent explicitly.
 *
 * <p>It is sent, not asked for. A timer would be either too slow to be honest or too frequent to be cheap, and
 * it would make this the one interface in the mod where a change is noticed because somebody looked rather than
 * because it happened. The change hook that already exists is enough: {@code Inventory.updateSlot} is patched at
 * every one of its engine call sites and reaches {@code IndexedInventories.slotChanged}, which is how a network's
 * counts stay correct in the first place. This class hangs off the same signal.
 *
 * <h2>What stops it becoming a flood</h2>
 *
 * <p>A single deposit of forty stacks is forty slot changes, and each one is a reason to redraw. So the signal
 * only sets a flag, and {@link #tick()} does the work at most once per tick -- and then only sends if the numbers
 * actually differ from what this client was last told. An idle network sends nothing, a busy one sends twenty
 * updates a second at the very most, and a change that cancels out sends none.
 */
public class UnitUpgradeContainer extends Container {

   /**
    * Server-side containers currently open, so a slot change can find them.
    *
    * <p>Small by nature -- one per player looking at a unit -- and iterated only when something in a watched
    * inventory changes.
    */
   private static final Set<UnitUpgradeContainer> OPEN = new HashSet<>();

   public final int tileX;

   public final int tileY;

   /** The tier on the tile when the panel opened, and the cost of leaving it. */
   public final UnitTier tier;

   public final Ingredient[] cost;

   public final boolean station;

   public final UpgradeAction upgradeAction;

   /** The latest state, on both sides: computed on the server, received on the client. */
   private UpgradeStateEvent state;

   /**
    * Inventories whose changes matter to this panel.
    *
    * <p>Membership is a set rather than a walk because the alternative is scanning the network on every slot
    * change anywhere in the world, and a Fallen-tier network is twenty thousand slots.
    */
   private final Set<Inventory> watched = new HashSet<>();

   private boolean dirty = true;

   public UnitUpgradeContainer(NetworkClient client, int uniqueSeed, ObjectEntity objectEntity, Packet content) {
      super(client, uniqueSeed);
      this.tileX = objectEntity.tileX;
      this.tileY = objectEntity.tileY;

      Level level = objectEntity.getLevel();
      this.tier = UnitUpgrade.tierAt(level, this.tileX, this.tileY);
      this.station = UnitUpgrade.isStation(level, this.tileX, this.tileY);
      Ingredient[] resolved = UnitUpgrade.cost(level, this.tileX, this.tileY);
      this.cost = resolved == null ? new Ingredient[0] : resolved;

      this.upgradeAction = this.registerAction(new UpgradeAction());

      if (client.isServer()) {
         OPEN.add(this);
         this.rebuildWatchList(level, client.getServerClient());
         this.state = this.measure(level, client.getServerClient());
      } else {
         // The opening packet carries the first state, so the panel is correct the instant it appears rather
         // than blank until something happens to change it.
         this.state = content == null ? null : new UpgradeStateEvent(new PacketReader(content));

         this.subscribeEvent(
            UpgradeStateEvent.class,
            event -> event.tileX == this.tileX && event.tileY == this.tileY,
            () -> true
         );
         this.onEvent(UpgradeStateEvent.class, event -> this.state = event);
      }
   }

   /** What the form draws. Null only in the moment before the first state arrives. */
   public UpgradeStateEvent getState() {
      return this.state;
   }

   /** How many of requirement {@code index} the player can reach, or -1 before the first state arrives. */
   public int availableFor(int index) {
      UpgradeStateEvent current = this.state;
      return current == null || index < 0 || index >= current.available.length ? -1 : current.available[index];
   }

   /** Whether requirement {@code index} is satisfied, which is what highlights its icon. */
   public boolean satisfied(int index) {
      return index >= 0 && index < this.cost.length
         && this.availableFor(index) >= this.cost[index].getIngredientAmount();
   }

   /** Whether the Upgrade button should be usable. */
   public boolean canUpgrade() {
      UpgradeStateEvent current = this.state;
      return this.cost.length > 0 && current != null && current.affordable;
   }

   /** Called from the change hook for every inventory that changes anywhere. Must stay cheap. */
   public static void inventoryChanged(Inventory inventory) {
      if (OPEN.isEmpty()) {
         return;
      }

      for (UnitUpgradeContainer container : OPEN) {
         if (container.watched.contains(inventory) || inventory instanceof PlayerInventory) {
            container.dirty = true;
         }
      }
   }

   /** Forgets everything, for the harness between scenarios. */
   public static void forget() {
      OPEN.clear();
   }

   /**
    * A player's own inventory is matched by type rather than by identity.
    *
    * <p>{@code PlayerInventoryManager} holds several {@code PlayerInventory} objects and hands them out by ID,
    * so pinning down exactly which ones belong to this viewer is more code than the imprecision costs: another
    * player's backpack change marks this panel dirty, and the panel then recomputes once and sends nothing
    * because its numbers did not move.
    */
   private void rebuildWatchList(Level level, ServerClient client) {
      this.watched.clear();

      for (NetworkStorage unit : UnitUpgrade.pool(level, this.tileX, this.tileY)) {
         Inventory inventory = unit.getInventory();
         if (inventory != null) {
            this.watched.add(inventory);
         }
      }
   }

   private UpgradeStateEvent measure(Level level, ServerClient client) {
      ObjectEntity entity = level == null ? null : level.entityManager.getObjectEntity(this.tileX, this.tileY);
      int used = 0;
      int total = 0;

      if (entity instanceof InventoryObjectEntity) {
         Inventory inventory = ((InventoryObjectEntity)entity).getInventory();
         used = inventory.getUsedSlots();
         total = inventory.getSize();
      }

      Ingredient[] current = UnitUpgrade.cost(level, this.tileX, this.tileY);
      Ingredient[] measuring = current == null ? new Ingredient[0] : current;
      List<NetworkStorage> pool = UnitUpgrade.pool(level, this.tileX, this.tileY);
      int[] available = new int[measuring.length];
      boolean affordable = measuring.length > 0;

      for (int i = 0; i < measuring.length; i++) {
         available[i] = UnitUpgrade.available(level, client.playerMob, pool, measuring[i]);
         if (available[i] < measuring[i].getIngredientAmount()) {
            affordable = false;
         }
      }

      return new UpgradeStateEvent(this.tileX, this.tileY, used, total, affordable, available);
   }

   @Override
   public void tick() {
      super.tick();
      if (!this.client.isServer() || !this.dirty) {
         return;
      }

      this.dirty = false;
      ServerClient serverClient = this.client.getServerClient();
      Level level = serverClient.getLevel();
      UpgradeStateEvent fresh = this.measure(level, serverClient);

      if (fresh.sameAs(this.state)) {
         return;
      }

      this.state = fresh;
      fresh.applyAndSendToClient(serverClient);
   }

   @Override
   public void onClose() {
      super.onClose();
      OPEN.remove(this);
   }

   /** The button. Validation is entirely the server's, because the client's copy of the numbers is only a view. */
   public class UpgradeAction extends ContainerCustomAction {

      public void runAndSend() {
         this.runAndSendAction(new Packet());
      }

      @Override
      public void executePacket(PacketReader reader) {
         // Runs on both sides, and must do nothing on the client.
         //
         // ContainerCustomAction.runAndSendAction sends the packet and then calls executePacket locally, so the
         // clicking client executes this too -- where getServerClient() throws a ClassCastException, because a
         // NetworkClient on that side is a ClientClient. Observed in game: the upgrade itself succeeded, since
         // the packet is sent before the local call and the server's copy ran normally, but the client logged
         // an error for work it had no business doing.
         //
         // Every action in StorageTerminalContainer already opens with this guard. This one did not, and no test
         // could have caught it: the harness has no client, so client.isServer() is always true there and the
         // client branch is unreachable. That is the second bug in this mod to hide in exactly that gap -- the
         // first was the bus panel decoding its filter from a double-wrapped packet.
         if (!UnitUpgradeContainer.this.client.isServer()) {
            return;
         }

         ServerClient serverClient = UnitUpgradeContainer.this.client.getServerClient();
         Level level = serverClient.getLevel();

         UnitUpgrade.Result result = UnitUpgrade.attempt(
            level, UnitUpgradeContainer.this.tileX, UnitUpgradeContainer.this.tileY, serverClient
         );

         if (result.ok()) {
            // The tile is a different object now, so the panel's own cost and tier are stale. Closing is
            // honest and cheap: reopening shows the next rung, and a panel that silently rewrote itself into
            // a different upgrade would be a way to click the wrong one.
            serverClient.closeContainer(true);
         } else {
            UnitUpgradeContainer.this.dirty = true;
         }
      }
   }

   /**
    * The packet that opens the panel, carrying the first state so it is never drawn empty.
    *
    * <p>{@code PacketOpenContainer.ObjectEntity} wraps what it is given, so the state is written into a bare
    * packet here rather than being wrapped twice -- a double wrap is what once made the bus panel read a length
    * prefix as a filter and decode an empty one every time.
    */
   public static PacketOpenContainer openPacket(int containerID, ObjectEntity unit, ServerClient client) {
      Level level = unit.getLevel();
      Packet content = new Packet();
      PacketWriter writer = new PacketWriter(content);

      int used = 0;
      int total = 0;
      if (unit instanceof InventoryObjectEntity) {
         Inventory inventory = ((InventoryObjectEntity)unit).getInventory();
         used = inventory.getUsedSlots();
         total = inventory.getSize();
      }

      Ingredient[] cost = UnitUpgrade.cost(level, unit.tileX, unit.tileY);
      Ingredient[] measuring = cost == null ? new Ingredient[0] : cost;
      List<NetworkStorage> pool = UnitUpgrade.pool(level, unit.tileX, unit.tileY);
      int[] available = new int[measuring.length];
      boolean affordable = measuring.length > 0;

      for (int i = 0; i < measuring.length; i++) {
         available[i] = UnitUpgrade.available(level, client.playerMob, pool, measuring[i]);
         if (available[i] < measuring[i].getIngredientAmount()) {
            affordable = false;
         }
      }

      new UpgradeStateEvent(unit.tileX, unit.tileY, used, total, affordable, available).write(writer);
      return PacketOpenContainer.ObjectEntity(containerID, unit, content);
   }

   /** Opens the panel for one player. */
   public static void open(int containerID, ServerClient client, ObjectEntity unit) {
      ContainerRegistry.openAndSendContainer(client, openPacket(containerID, unit, client));
   }

   /** Unused, but kept symmetrical with the other containers here. */
   public List<Ingredient> requirements() {
      return new ArrayList<>(java.util.Arrays.asList(this.cost));
   }
}
