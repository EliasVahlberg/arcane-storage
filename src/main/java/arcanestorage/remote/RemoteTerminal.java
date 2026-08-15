package arcanestorage.remote;

import arcanestorage.object.StorageTerminalObject;
import arcanestorage.objectentity.StorageTerminalObjectEntity;
import necesse.engine.network.server.ServerClient;
import necesse.engine.util.LevelIdentifier;
import necesse.engine.world.World;
import necesse.entity.objectEntity.ObjectEntity;
import necesse.level.maps.Level;
import necesse.level.maps.regionSystem.Region;

/**
 * Resolving a {@link RemoteBinding} to a live terminal, server-side, whether or not its level is loaded.
 *
 * <h2>Loading an unloaded level needs nothing invented</h2>
 *
 * <p>{@link World#getLevel(LevelIdentifier)} already does the whole job: it asks
 * {@code LevelManager} for the level, and when that returns null it calls {@code loadLevel}, which reads
 * the level file through {@code LevelSave.loadSave} and finishes with {@code onLoadingComplete} and
 * {@code simulateSinceLastWorldTime} — so a level that has been asleep for a week comes back having caught
 * up on world time. If there is no file at all it generates one. There is no cache to consult, no
 * asynchronous handle to wait on, and no separate "is it loaded" question worth asking first.
 *
 * <p>That was worth checking rather than assuming, because the alternative designs all start with
 * inventing a persistence sidecar — writing the network's contents onto the item, or into world data — and
 * every one of those would be a second copy of items that already exist somewhere authoritative. A second
 * copy of a player's belongings is the one thing this mod must never have.
 *
 * <h2>Keeping it loaded is the part that needs care</h2>
 *
 * <p>Loading is not enough on its own. {@code Server} unloads any level whose
 * {@code Level.unloadLevelBuffer} passes {@code Settings.unloadLevelsCooldown} (30 seconds by default),
 * and unloading calls {@code world.saveLevel} and drops the level from the manager's map. A container
 * holding slots on that level's inventories would keep writing into an object nothing saves again — the
 * player deposits into the void and only finds out later.
 *
 * <p>The buffer counts up once per {@code Level.serverTick} and is reset to zero whenever a client is on
 * the level. Vanilla keeps a level alive by resetting it directly:
 * {@code ArenaEntrancePortalMob}, {@code AscendedWizardMob} and {@code ServerSettlementData} all do
 * exactly {@code getLevel().unloadLevelBuffer = 0}. {@link #pin(Level)} is that same one line, and it has
 * to run every tick the container is open rather than once at open time.
 *
 * <p>A consequence worth knowing rather than discovering: while a wireless terminal is open, the paired
 * level ticks normally, because {@code LevelManager.serverTick} ticks every loaded level. Buses keep
 * moving items, crops keep growing. That is a feature here — the network being watched is live, not a
 * snapshot — but it does mean an open wireless terminal costs a whole level's tick.
 */
public final class RemoteTerminal {

   private RemoteTerminal() {
   }

   /** Why a remote open failed, or {@link #OK}. Kept as an enum so the caller decides the wording. */
   public enum Result {
      OK,
      /** The item has never been paired. */
      UNPAIRED,
      /** The stored level identifier is not one the engine accepts. */
      BAD_LEVEL,
      /** The level resolved, but nothing at the tile is a Storage Terminal any more. */
      GONE
   }

   /** A resolved terminal and its level, or a reason it could not be resolved. */
   public static final class Resolved {

      public final Result result;

      public final Level level;

      public final StorageTerminalObjectEntity terminal;

      private Resolved(Result result, Level level, StorageTerminalObjectEntity terminal) {
         this.result = result;
         this.level = level;
         this.terminal = terminal;
      }

      public boolean ok() {
         return this.result == Result.OK;
      }
   }

   private static final Resolved UNPAIRED = new Resolved(Result.UNPAIRED, null, null);

   private static final Resolved BAD_LEVEL = new Resolved(Result.BAD_LEVEL, null, null);

   /**
    * Resolves a binding, loading its level if it is not in memory.
    *
    * <p>Server-side only. There is no client equivalent and there cannot be one: a client holds exactly
    * the level it is standing on, so it has no way to look at another.
    */
   public static Resolved resolve(ServerClient client, RemoteBinding binding) {
      if (binding == null) {
         return UNPAIRED;
      }

      LevelIdentifier identifier = binding.identifier();
      if (identifier == null) {
         return BAD_LEVEL;
      }

      // Loads from disk when not in memory, and generates when there is no file. Both are wanted: the
      // second case is a binding to a level that was never visited, which cannot happen through pairing
      // but can through an edited save, and generating an empty level is a safer answer than a crash.
      Level level = client.getServer().world.getLevel(identifier);
      if (level == null) {
         return BAD_LEVEL;
      }

      // A loaded level is not a loaded tile, which is the correction that made this work. Object entities live
      // in regions, and regions unload on their own schedule -- the server logs "Unloading surface world region
      // 0x-1 from memory to file system" -- so a terminal on a level that is fully loaded still reads as gone
      // when nobody has been near it. It happens on the player's own level too, which is what ruled out the
      // level unload as the explanation. Loading is synchronous here and generates the region if there is no
      // file, and it is safe from this call site because a container ticks on the server thread, which is the
      // thread that does this work anyway.
      level.regionManager.getRegionByTile(binding.tileX, binding.tileY, true);

      ObjectEntity entity = level.entityManager.getObjectEntity(binding.tileX, binding.tileY);
      if (!(entity instanceof StorageTerminalObjectEntity)) {
         return new Resolved(Result.GONE, level, null);
      }

      // The object entity existing is not proof the terminal does. Object entities outlive their object in
      // some paths, so the object is checked too, by class rather than by registered ID -- which is how the
      // rest of the mod asks this question (UnitUpgrade tests instanceof StationUnitObject).
      if (!(level.getObject(binding.tileX, binding.tileY) instanceof StorageTerminalObject)) {
         return new Resolved(Result.GONE, level, null);
      }

      return new Resolved(Result.OK, level, (StorageTerminalObjectEntity)entity);
   }

   /**
    * Keeps a level, and the regions holding the given tiles, from being unloaded. Call every server tick.
    *
    * <p><b>Both halves are needed, and an earlier version had only the first.</b> Pinning the level alone kept
    * the level in memory while its regions unloaded underneath, so an open wireless terminal closed itself about
    * thirty seconds in -- the container's own validity check noticing that its units had stopped answering, which
    * is the safety net working rather than failing. Vanilla does both together in every place it does either:
    * {@code ArenaEntrancePortalMob} resets {@code unloadLevelBuffer} and then calls
    * {@code getRegionByTile(...).unloadRegionBuffer.keepLoaded()} three lines later, and
    * {@code ServerClient.tick} walks its own {@code loadedRegions} doing the same for every region a player can
    * see.
    *
    * <p>Every tile is pinned, not just the terminal's, because a network spans regions and each unit's inventory
    * belongs to an object entity in whichever region holds it. A unit whose region unloaded while the container
    * was writing into it would be writing into something the engine has already saved and dropped, and those
    * writes are a player's items. Regions are reloaded if they have gone, for the same reason: coming back is
    * better than a container that is half attached.
    *
    * <p>No reference counting, and none needed: the engine's mechanism is a countdown that anything may reset, so
    * two terminals open on one level cooperate without knowing about each other, and forgetting to unpin cannot
    * leak -- everything simply resumes counting and unloads about thirty seconds later.
    */
   public static void pin(Level level, Iterable<long[]> tiles) {
      if (level == null) {
         return;
      }

      level.unloadLevelBuffer = 0;
      if (tiles == null) {
         return;
      }

      for (long[] tile : tiles) {
         Region region = level.regionManager.getRegionByTile((int)tile[0], (int)tile[1], true);
         if (region != null) {
            region.unloadRegionBuffer.keepLoaded();
         }
      }
   }
}
