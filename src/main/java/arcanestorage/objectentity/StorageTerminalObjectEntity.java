package arcanestorage.objectentity;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;

import arcanestorage.ArcaneStorage;
import arcanestorage.network.NetworkConductor;
import arcanestorage.network.NetworkStorage;
import arcanestorage.network.UnitNetwork;
import necesse.entity.objectEntity.InventoryObjectEntity;
import necesse.engine.network.PacketReader;
import necesse.engine.network.PacketWriter;
import necesse.entity.objectEntity.ObjectEntity;
import necesse.inventory.recipe.Tech;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import necesse.engine.GameLog;
import necesse.inventory.InventoryItem;
import necesse.inventory.item.placeableItem.objectItem.ObjectItem;
import necesse.level.gameObject.GameObject;
import necesse.level.gameObject.container.CraftingStationObject;
import necesse.level.maps.levelData.settlementData.SettlementWorkstationObject;
import necesse.level.maps.Level;

/**
 * Object entity behind the Storage Terminal.
 *
 * <p>Object entities are <b>not registered</b> anywhere in Necesse — there is no
 * {@code ObjectEntityRegistry}. {@code ObjectEntitySave.loadSave} rebuilds them by
 * calling {@code level.getLevelObject(x, y).getNewObjectEntity()} and then uses the
 * saved {@code stringID} purely as a consistency check against {@link #type}. So all
 * this class has to do is pick a stable, distinct type string.
 *
 * <p>{@link InventoryObjectEntity}'s only constructor hardcodes
 * {@code super(level, "inventory", x, y)}, so the type is reassigned here. {@code type}
 * is a public mutable field on {@code ObjectEntity} and is read nowhere else in the
 * game outside that save check.
 */
public class StorageTerminalObjectEntity extends InventoryObjectEntity {

   /** Must never change between versions: a mismatch makes the save data load as invalid. */
   public static final String TYPE = "arcanestorageterminal";

   /**
    * Ceiling on how many units one network may contain, and so on the container's slot
    * count — 40 slots each, so 64 units is 2560 slots.
    *
    * <p>A first guess, to be revised by measurement rather than argument. It bounds the
    * unit <i>count</i>, not the distance walked, so a very long thin chain can still reach
    * further than the client has level data loaded. That is tolerable: the two sides
    * disagreeing about membership shows stale contents until the terminal is reopened, and
    * cannot move items wrongly, because no network slot index is ever sent by the client —
    * withdrawal sends an item and the server re-resolves it against its own units.
    */
   public static final int MAX_UNITS = 64;

   /**
    * Ceiling on conducting tiles one network may be routed through.
    *
    * <p>Separate from {@link #MAX_UNITS} because conduits cost almost nothing to place and
    * hold no items: without their own bound, a long cheap run would make discovery expensive
    * for a network that stores nothing. Also the practical answer to "how far may a network
    * reach" — reach is now bounded by this rather than by unit count.
    */
   public static final int MAX_CONDUITS = 256;

   /**
    * How many crafting stations one terminal may hold.
    *
    * <p>Ten because that is one row at vanilla's slot pitch, and because the game has eight
    * station <i>families</i> -- workstation, anvil, carpenter, alchemy, cooking pot, roasting
    * station, forge, landscaping -- so ten covers every family at once with headroom. Tiers cost
    * nothing extra: an upgraded bench reports the lower techs as well as its own, so a Demonic
    * Workstation occupies one slot and answers for both.
    */
   public static final int STATION_SLOTS = 10;

   /**
    * Only crafting stations may be installed, one per slot, and only ones that do not need to be
    * placed to work.
    *
    * <p>Asked of the item rather than checked against a list of known benches:
    * {@link ObjectItem#getObject()} reaches the object, and {@code getCraftingTechs()} is declared on
    * {@link CraftingStationObject}, so extending it is the only way an object can say which recipes it
    * unlocks. A modded bench therefore works here without this mod knowing it exists, and the check is
    * as general as the game itself allows.
    */
   @Override
   public boolean isItemValid(int slot, InventoryItem item) {
      CraftingStationObject station = getCraftingStation(item);
      return station != null && !needsItsPlacement(station);
   }

   /**
    * The hooks a station overrides only because it needs to know where it is.
    *
    * <p>{@link SettlementWorkstationObject} is the game's own interface for "an object settlers can
    * craft at", and its default methods are a description of what a station needs from its tile:
    * whether it can craft right now, where its fuel lives, whether it processes over time. Every
    * default is the answer of a station that needs nothing -- {@code canCurrentlyCraft} returns true,
    * the fuel accessors return null, {@code isProcessingInventory} returns false. So a station that
    * overrides any of them is telling us, in the game's own vocabulary, that it cannot be reduced to an
    * item in a slot.
    *
    * <p>{@code getMaxCraftsAtOnce} is deliberately absent: a station may batch without caring where it
    * is, and refusing it would cost a capability for no correctness gain.
    */
   private static final String[][] PLACEMENT_HOOKS = {
      {"canCurrentlyCraft", "necesse.level.maps.Level", "int", "int", "necesse.inventory.recipe.Recipe"},
      {"getFuelRequestOptions", "necesse.level.maps.Level", "int", "int"},
      {"getFuelInventoryRange", "necesse.level.maps.Level", "int", "int"},
      {"isProcessingInventory", "necesse.level.maps.Level", "int", "int"},
      {"tickCrafting", "necesse.level.maps.Level", "int", "int", "necesse.inventory.recipe.Recipe"},
   };

   /** Reflection is stable for a class, and this is asked once per slot on every inventory change. */
   private static final Map<Class<?>, Boolean> PLACEMENT_DEPENDENT = new ConcurrentHashMap<>();

   /**
    * Whether a station's crafting depends on the tile it stands on, and so cannot be installed.
    *
    * <p>This closes a hole rather than expressing a preference, and the first version closed it too
    * narrowly. Fuel is enforced by {@code FueledCraftingStationContainer.applyCraftingAction}, which
    * refuses when the station is cold -- behaviour of the <i>container</i>, not of the object. A
    * terminal that installs a Forge inherits its techs and none of that, so smelting would cost no
    * fuel. The first fix asked {@code instanceof FueledCraftingStationObject}, which is correct for
    * vanilla and worthless for a mod: a modded smelter that keeps its own fuel without extending that
    * class would have installed and smelted for free.
    *
    * <p>So the question asked here is behavioural — does this station need to be somewhere? — and it
    * is answered from two places the game already maintains:
    *
    * <ul>
    *   <li>the {@link SettlementWorkstationObject} hooks above, because a station that keeps state has
    *       to override them or settlers could not use it correctly either; and
    *   <li>{@link GameObject#getNewObjectEntity}, because placed state <i>is</i> an object entity in
    *       this engine. Verified Aug 2026: all eight installable vanilla stations inherit the base
    *       implementation, which returns null, while {@code FueledCraftingStationObject} overrides it
    *       to create an {@code AnyLogFueledInventoryObjectEntity} -- so every fueled station in the
    *       game is caught by that half alone, including the Cooking Station, which overrides nothing
    *       itself.
    * </ul>
    *
    * <p>The honest limit: a modded station could keep placed state without touching either, and would
    * slip through. It would also be broken for settlers, which is the reason to expect the overlap
    * rather than to assume it.
    *
    * <p>This is also the seam where production stations belong when they are done properly: fuel,
    * crafting time and a request queue are a feature, not a slot. See the roadmap.
    */
   public static boolean needsItsPlacement(CraftingStationObject station) {
      return PLACEMENT_DEPENDENT.computeIfAbsent(station.getClass(),
            StorageTerminalObjectEntity::computePlacementDependent);
   }

   private static boolean computePlacementDependent(Class<?> type) {
      if (declaresBelow(type, GameObject.class, "getNewObjectEntity",
            "necesse.level.maps.Level", "int", "int")) {
         return true;
      }

      for (String[] hook : PLACEMENT_HOOKS) {
         String[] params = Arrays.copyOfRange(hook, 1, hook.length);
         if (declaresBelow(type, SettlementWorkstationObject.class, hook[0], params)) {
            return true;
         }
      }

      return false;
   }

   /**
    * Whether {@code type} overrides {@code name} somewhere below {@code base}.
    *
    * <p>A missing method is reported as an override rather than ignored: it means this mod is compiled
    * against a different version of the game than it is running on, and in that case refusing to
    * install is the safe direction -- a refused bench is a visible annoyance, free smelting is not.
    */
   private static boolean declaresBelow(Class<?> type, Class<?> base, String name, String... params) {
      try {
         Class<?>[] types = new Class<?>[params.length];
         for (int i = 0; i < params.length; i++) {
            types[i] = "int".equals(params[i]) ? int.class : Class.forName(params[i]);
         }

         return !type.getMethod(name, types).getDeclaringClass().equals(base);
      } catch (ReflectiveOperationException e) {
         GameLog.warn.println("arcane storage: cannot tell whether " + type.getName() + " needs its "
               + "placement (" + name + " not found), refusing to install it: " + e);
         return true;
      }
   }

   /** One bench per slot, so the slots read as an install list rather than as storage. */
   @Override
   public int getItemStackLimit(int slot, InventoryItem item) {
      return 1;
   }

   /**
    * The station an item would install, or null if it is not a station at all.
    */
   public static CraftingStationObject getCraftingStation(InventoryItem item) {
      if (item == null || !(item.item instanceof ObjectItem)) {
         return null;
      }

      GameObject object = ((ObjectItem) item.item).getObject();
      return object instanceof CraftingStationObject ? (CraftingStationObject) object : null;
   }

   /**
    * The techs the installed stations provide, in slot order.
    *
    * <p>Recomputed on each call rather than cached: the set changes whenever a slot does, and a
    * cache would be one more thing to invalidate for a loop over ten slots. Both sides can call
    * this and get the same answer, because {@code InventoryObjectEntity} already syncs its
    * inventory to the client in its content packet -- which is what lets the client show the right
    * recipes without a packet of our own.
    */
   public LinkedHashSet<Tech> getInstalledTechs() {
      LinkedHashSet<Tech> techs = new LinkedHashSet<>();

      for (int slot = 0; slot < this.inventory.getSize(); slot++) {
         CraftingStationObject station = getCraftingStation(this.inventory.getItem(slot));
         if (station != null) {
            techs.addAll(Arrays.asList(station.getCraftingTechs()));
         }
      }

      return techs;
   }

   public StorageTerminalObjectEntity(Level level, int x, int y, int slots) {
      super(level, x, y, slots);
      this.type = TYPE;
   }

   /**
    * The linked Storage Units, discovered fresh on each call.
    *
    * <p>Membership is connectivity: any unit reachable from this terminal through
    * orthogonally touching units and conduits. Units and conduits both conduct, so a chain or
    * block of them all belongs to one network, but a terminal never bridges two groups. A
    * conduit adds reach without adding capacity. See {@link UnitNetwork} for the
    * traversal and the reasoning behind its guarantees.
    *
    * <p>Still no persistence: the network is recomputed each time rather than stored, so
    * there is nothing to keep in sync with the world. That is why breaking a unit needs no
    * cleanup. Persisted membership only becomes necessary if linking stops being a pure
    * function of layout.
    *
    * <p>Membership is a role, not a type: any object entity implementing {@link NetworkStorage}
    * joins, and anything whose object implements {@link NetworkConductor} carries the walk onward. So
    * another mod's silo or pipe works here without this mod knowing it exists, and our own units are
    * the first users of that seam rather than a special case beside it.
    *
    * <p>Vanilla chests are still deliberately not scanned. They implement {@code OEInventory}, not
    * this mod's interface, so joining stays something an object opts into: silently absorbing a nearby
    * chest would be surprising, and a unit is distinguishable precisely because the player cannot open
    * it.
    */
   public List<NetworkStorage> getLinkedUnits() {
      final Level level = this.getLevel();
      if (level == null) {
         return new ArrayList<>();
      }

      return UnitNetwork.discover(this.tileX, this.tileY, (x, y) -> {
         ObjectEntity candidate = level.entityManager.getObjectEntity(x, y);
         if (candidate instanceof NetworkStorage) {
            NetworkStorage member = (NetworkStorage)candidate;
            return member.isOnNetwork() ? member : null;
         }

         return null;
      }, (x, y) -> level.getObject(x, y) instanceof NetworkConductor, MAX_UNITS, MAX_CONDUITS);
   }

   /**
    * Ticks between recounting the network's stopped devices, while somebody is looking.
    *
    * <p>Twenty is one second, which is as often as a bus re-evaluates itself, so the terminal cannot show a
    * problem the buses have not decided on yet.
    */
   private static final int PROBLEM_INTERVAL = 20;

   /** A ceiling on the survey, so a pathological network cannot make an unbounded packet. */
   private static final int MAX_BUSES = 128;

   /**
    * Every bus on the network, with what each is doing. Ordered by tile, so the list does not reshuffle.
    *
    * <p><b>Why the terminal carries this at all.</b> A grey sprite is only discoverable if the player walks
    * past it, and the reason a bus stopped is usually a rule set minutes earlier somewhere else. The terminal
    * is where a player goes when storage misbehaves, so it is the one surface that finds the problem for them,
    * and the only one that scales past a handful of buses.
    *
    * <p>All of them rather than only the stopped ones, because the logistics tab configures buses as well as
    * reporting on them -- and a list that showed a device only once it broke would be a strange place to go
    * and set it up.
    */
   private List<BusSummary> buses = new ArrayList<>();

   private int ticksUntilProblemCheck = PROBLEM_INTERVAL;

   /**
    * Recounts stopped devices, but <b>only while the terminal is open</b>.
    *
    * <p>Idle cost is the reason for that condition rather than tidiness: an unattended terminal walking its
    * network once a second is exactly the polling the transfer resolver exists to remove, and it would keep
    * the idle-cost test failing for a new reason after the old one is fixed.
    */
   @Override
   public void serverTick() {
      super.serverTick();
      if (!this.isServer()) {
         return;
      }

      // Armed rather than merely skipped while nobody is looking, so the first tick after a terminal is opened
      // surveys instead of waiting out the rest of an interval. A logistics tab that took most of a second to
      // list anything would read as an empty network.
      if (!this.isInUse()) {
         this.ticksUntilProblemCheck = 1;
         return;
      }

      if (--this.ticksUntilProblemCheck > 0) {
         return;
      }

      this.ticksUntilProblemCheck = PROBLEM_INTERVAL;

      List<BusSummary> found = this.surveyBuses();
      if (!sameAs(this.buses, found)) {
         this.buses = found;
         this.markDirty();
      }
   }

   /**
    * Whether two surveys say the same thing, so an unchanged network sends no packet.
    *
    * <p>Compared field by field rather than by identity, since every survey builds new summaries. Without
    * this the terminal would push its whole bus list to every client once a second while open.
    */
   private static boolean sameAs(List<BusSummary> a, List<BusSummary> b) {
      if (a.size() != b.size()) {
         return false;
      }

      for (int i = 0; i < a.size(); i++) {
         BusSummary one = a.get(i);
         BusSummary two = b.get(i);
         if (one.tileX != two.tileX || one.tileY != two.tileY || one.importing != two.importing
               || one.state != two.state || one.conflictX != two.conflictX || one.conflictY != two.conflictY
               || !java.util.Objects.equals(one.conflictItemID, two.conflictItemID)) {
            return false;
         }
      }

      return true;
   }

   /**
    * Every bus reachable from this terminal, in tile order.
    *
    * <p>Buses conduct, so they are all visited by the same walk that finds the units. The conductor test is
    * the place to notice them; there is no second traversal.
    */
   private List<BusSummary> surveyBuses() {
      Level level = this.getLevel();
      if (level == null) {
         return new ArrayList<>();
      }

      List<BusObjectEntity> found = new ArrayList<>();
      UnitNetwork.discover(this.tileX, this.tileY, (x, y) -> {
         ObjectEntity candidate = level.entityManager.getObjectEntity(x, y);
         return candidate instanceof NetworkStorage && ((NetworkStorage)candidate).isOnNetwork()
            ? (NetworkStorage)candidate
            : null;
      }, (x, y) -> {
         if (!(level.getObject(x, y) instanceof NetworkConductor)) {
            return false;
         }

         ObjectEntity at = level.entityManager.getObjectEntity(x, y);
         if (at instanceof BusObjectEntity && found.size() < MAX_BUSES) {
            found.add((BusObjectEntity)at);
         }

         return true;
      }, MAX_UNITS, MAX_CONDUITS);

      // Sorted so the tab's rows keep their places between surveys. A list in discovery order would
      // reshuffle whenever the walk started somewhere else, and a player would lose the row they were reading.
      found.sort((a, b) -> a.tileY != b.tileY ? Integer.compare(a.tileY, b.tileY)
            : Integer.compare(a.tileX, b.tileX));

      List<BusSummary> summaries = new ArrayList<>(found.size());
      for (BusObjectEntity bus : found) {
         summaries.add(bus.summary());
      }

      return summaries;
   }

   /**
    * The bus at these coordinates, if it is on this network.
    *
    * <p>The membership test is the point, not a convenience: the logistics tab edits rules by coordinate, so
    * without it a client could send any coordinates it liked and rewrite a bus belonging to somebody else's
    * base. A walk per edit is affordable because edits are things players do occasionally.
    */
   public BusObjectEntity busOnNetwork(int x, int y) {
      Level level = this.getLevel();
      if (level == null) {
         return null;
      }

      ObjectEntity at = level.entityManager.getObjectEntity(x, y);
      if (!(at instanceof BusObjectEntity)) {
         return null;
      }

      for (BusSummary summary : this.surveyBuses()) {
         if (summary.tileX == x && summary.tileY == y) {
            return (BusObjectEntity)at;
         }
      }

      return null;
   }

   /** Every bus on this network, as the terminal last saw them. Read by the form. */
   public List<BusSummary> getBuses() {
      return this.buses;
   }

   @Override
   public void setupContentPacket(PacketWriter writer) {
      super.setupContentPacket(writer);
      writer.putNextShortUnsigned(this.buses.size());
      for (BusSummary summary : this.buses) {
         summary.writePacket(writer);
      }
   }

   @Override
   public void applyContentPacket(PacketReader reader) {
      super.applyContentPacket(reader);
      int count = reader.getNextShortUnsigned();
      List<BusSummary> read = new ArrayList<>(count);
      for (int i = 0; i < count; i++) {
         read.add(BusSummary.readPacket(reader));
      }

      this.buses = read;
   }
}
