package arcanestorage.patch;

import java.lang.instrument.Instrumentation;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import arcanestorage.ArcaneStorage;
import arcanestorage.network.NetworkStorage;
import arcanestorage.remote.RemoteBinding;
import arcanestorage.remote.RemoteTerminal;
import arcanestorage.remote.WirelessTerminalItem;
import necesse.engine.GameLog;
import necesse.engine.network.server.ServerClient;
import necesse.entity.mobs.PlayerMob;
import necesse.inventory.Inventory;
import necesse.inventory.InventoryItem;
import necesse.inventory.InventoryRange;
import necesse.level.maps.Level;
import net.bytebuddy.agent.ByteBuddyAgent;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.asm.Advice;
import static net.bytebuddy.matcher.ElementMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

/**
 * Lets Build Menu build from a storage network, both server and client, as a soft patch of the few
 * methods that decide what a placement may take.
 *
 * <p><b>Server.</b> Build Menu's {@code buildAvailabilityRanges(Level, PlayerMob, int, int)} builds
 * the single {@code List<InventoryRange>} that every place, paste and craft path hands to the
 * engine's {@code Recipe.canCraftRange} / {@code craftRange}. This mod appends the network's
 * inventories to that list when the player is connected, and the engine does the counting and
 * consuming.
 *
 * <p><b>Client.</b> Build Menu refuses a placement on the client before any packet reaches the
 * server, counting from the player's bag and cloud plus a list its own three
 * {@code onStorageSnapshot(List<InventoryRange>)} methods assign. Those are only ever fed by
 * settlement storage. This mod sends a compact network summary (see {@link PacketBuildMenuNetworkSummary}
 * and {@link BuildMenuSummary}), and the advice here appends that summary's range to whatever list
 * each {@code onStorageSnapshot} is given -- so the client's "can afford" view includes the network
 * in and outside a settlement. The client only counts from the summary; consumption stays on the
 * server against the real units.
 *
 * <p><b>Why not the usual patch annotation.</b> {@code @ModMethodPatch} and its siblings take a
 * {@code Class} literal, and Build Menu is deliberately not on this mod's compile classpath. A name
 * string is the only thing this mod may hold, so the advice is installed here, through ByteBuddy's
 * own agent builder matched by class name, on the instrumentation the game already installed.
 *
 * <p><b>The only Build Menu types named anywhere in this mod.</b> The class and method names below
 * are the only identifiers that mention Build Menu, and they are strings so their absence is a
 * normal case.
 *
 * <p><b>The connected rule.</b> A player is connected when they carry a wireless terminal paired to
 * a transceiver that still resolves, server-side. Resolution is {@link RemoteTerminal}'s, the same
 * one the wireless terminal itself uses. Nothing from the client is trusted.
 *
 * <p><b>The signatures are checked together, not assumed.</b> All four targets are verified by
 * reflection before any advice is installed; a mismatch on any one turns the whole compat off with
 * one log line. A half-woven state -- the server counting a network the client refuses, or the
 * reverse -- is worse than none. {@code Class.forName(name, false, loader)} is used so the check
 * never runs Build Menu's static initialisers early.
 */
public final class BuildMenuCompat {

   private BuildMenuCompat() {
   }

   /** The Build Menu classes and methods this mod appends to, named by string: soft dependency. */
   private static final String BUILD_MENU_UTIL = "BuildMenu.modclass.util.BuildMenuInventoryUtil";
   private static final String BUILDING_MENU_FORM = "BuildMenu.modclass.ui.BuildingMenuForm";
   private static final String PASTE_TOOL = "BuildMenu.modclass.tool.BuildMenuPasteGameTool";
   private static final String PLACE_TOOL = "BuildMenu.modclass.tool.BuildMenuPlaceGameTool";

   private static final String BUILD_MENU_METHOD = "buildAvailabilityRanges";
   private static final String SNAPSHOT_METHOD = "onStorageSnapshot";

   /** A class and method to patch, plus the advice to weave in. {@code returnType == null} means void. */
   private static final class PatchTarget {
      final String className;
      final String methodName;
      final Class<?>[] argumentTypes;
      final Class<?> returnType;
      final Class<?> adviceClass;
      final boolean snapshot;

      PatchTarget(String className, String methodName, Class<?>[] argumentTypes, Class<?> returnType,
            Class<?> adviceClass, boolean snapshot) {
         this.className = className;
         this.methodName = methodName;
         this.argumentTypes = argumentTypes;
         this.returnType = returnType;
         this.adviceClass = adviceClass;
         this.snapshot = snapshot;
      }
   }

   /** All four targets, checked and woven together. */
   private static final PatchTarget[] TARGETS = {
      new PatchTarget(BUILD_MENU_UTIL, BUILD_MENU_METHOD,
            new Class<?>[]{Level.class, PlayerMob.class, int.class, int.class}, List.class,
            BuildMenuAvailabilityAdvice.class, false),
      new PatchTarget(BUILDING_MENU_FORM, SNAPSHOT_METHOD, new Class<?>[]{List.class}, null,
            SnapshotAdvice.class, true),
      new PatchTarget(PASTE_TOOL, SNAPSHOT_METHOD, new Class<?>[]{List.class}, null,
            SnapshotAdvice.class, true),
      new PatchTarget(PLACE_TOOL, SNAPSHOT_METHOD, new Class<?>[]{List.class}, null,
            SnapshotAdvice.class, true)
   };

   /** Set once at load from the config. Read by the advice, so it cannot be changed at runtime. */
   private static volatile boolean enabled;

   /** Whether every target was actually transformed this launch. */
   private static volatile boolean active;

   /** How many times the server advice appended at least one network range. Diagnostic. */
   private static volatile long appended;

   /** How many ranges the server advice appended in total. Diagnostic. */
   private static volatile long appendedRanges;

   /** Whether a signature problem has been logged, so it is logged once rather than per placement. */
   private static volatile boolean mismatchLogged;

   /** How many times a mismatch was reported, kept so the "once" behaviour is testable. */
   private static volatile int mismatchReports;

   /** The cached network summary range, client-side. Null while no summary has arrived. */
   private static volatile InventoryRange networkSummaryRange;

   /** The last list Build Menu handed to {@code onStorageSnapshot}, remembered so a refresh reuses it. */
   private static volatile List<InventoryRange> lastBuildMenuSnapshot;

   public static boolean isEnabled() {
      return enabled;
   }

   public static boolean isActive() {
      return active;
   }

   public static long appendedCount() {
      return appended;
   }

   public static long appendedRangeCount() {
      return appendedRanges;
   }

   public static int mismatchReports() {
      return mismatchReports;
   }

   /** The cached client summary range, or null. For the unit tests of the client view. */
   public static InventoryRange networkSummaryRange() {
      return networkSummaryRange;
   }

   /**
    * Installs every advice, after checking every signature. Called once from {@code postInit}.
    *
    * <p>Every early return is a normal state: the toggle is off, Build Menu is absent, or Build Menu
    * changed shape. The first two log nothing, so a player without Build Menu never hears about it.
    */
   public static void install() {
      enabled = ArcaneStorage.SETTINGS.buildMenuCompat;
      if (!enabled) {
         return;
      }

      // All-or-nothing: resolve and check every target before weaving any.
      for (PatchTarget target : TARGETS) {
         Class<?> cls = resolveClass(target.className);
         if (cls == null) {
            return; // Build Menu is not installed. Soft dependency: nothing happens.
         }

         String problem = signatureProblem(cls, target.methodName, target.argumentTypes, target.returnType);
         if (problem != null) {
            logMismatch(problem + " (" + target.className + ")");
            return;
         }
      }

      try {
         Instrumentation instrumentation = ByteBuddyAgent.install();
         AtomicInteger completed = new AtomicInteger();
         AgentBuilder.RedefinitionStrategy.Listener listener = new AgentBuilder.RedefinitionStrategy.Listener() {
            @Override
            public void onBatch(int index, List<Class<?>> batch, List<Class<?>> types) {
            }

            @Override
            public Iterable<? extends List<Class<?>>> onError(int index, List<Class<?>> batch,
                  Throwable throwable, List<Class<?>> types) {
               return null;
            }

            @Override
            public void onComplete(int amount, List<Class<?>> types,
                  java.util.Map<List<Class<?>>, Throwable> failures) {
               completed.addAndGet(amount);
            }
         };

         for (PatchTarget target : TARGETS) {
            new AgentBuilder.Default().disableClassFormatChanges().with(AgentBuilder.RedefinitionStrategy.RETRANSFORMATION)
                  .with(listener).type(named(target.className))
                  .transform((builder, type, classLoader, module) -> builder.visit(Advice.to(target.adviceClass)
                        .on(named(target.methodName).and(takesArguments(target.argumentTypes)))))
                  .installOn(instrumentation);
         }

         active = completed.get() >= TARGETS.length;
         if (!active) {
            logMismatch("not every target was transformed (the advice did not match)");
         }
      } catch (Throwable t) {
         active = false;
         logMismatch("the patch could not be installed: " + t);
      }
   }

   private static Class<?> resolveClass(String name) {
      try {
         // Non-initialising: the check must not run Build Menu's static initialisers early.
         return Class.forName(name, false, BuildMenuCompat.class.getClassLoader());
      } catch (ClassNotFoundException absent) {
         return null;
      }
   }

   /**
    * Why a method on the given class does not match the expected shape, or null when it does.
    * Extracted and made testable so a changed signature is a unit test rather than a mystery at load.
    */
   public static String signatureProblem(Class<?> targetClass, String methodName, Class<?>[] argumentTypes,
         Class<?> returnType) {
      if (targetClass == null) {
         return "no such class";
      }

      Method method;
      try {
         method = targetClass.getMethod(methodName, argumentTypes);
      } catch (NoSuchMethodException changed) {
         return "no " + methodName + " method with the expected arguments";
      }

      if (!Modifier.isStatic(method.getModifiers())) {
         return methodName + " is not static";
      }

      Class<?> actualReturn = method.getReturnType();
      if (returnType == null) {
         if (actualReturn != void.class && actualReturn != Void.class) {
            return methodName + " returns " + actualReturn.getName() + ", not void";
         }
      } else if (!returnType.isAssignableFrom(actualReturn)) {
         return methodName + " returns " + actualReturn.getName() + ", not " + returnType.getSimpleName();
      }

      return null;
   }

   /** Logs a mismatch once per launch, so a changed signature cannot fill the log on every placement. */
   static void logMismatch(String reason) {
      if (mismatchLogged) {
         return;
      }

      mismatchLogged = true;
      mismatchReports++;
      GameLog.warn.println("Arcane Storage: Build Menu support is off this launch: " + reason
            + ". The network is not offered to Build Menu, and everything else works as normal.");
   }

   /** Resets the once-per-launch mismatch flag and count, for the unit test of the log-once rule. */
   static void resetMismatchReporting() {
      mismatchLogged = false;
      mismatchReports = 0;
   }

   /**
    * The patched server method's ranges, or null when the patch is not active. For tests and diagnosis.
    */
   @SuppressWarnings("unchecked")
   public static List<InventoryRange> availabilityRanges(Level level, PlayerMob player, int tileX, int tileY) {
      if (!active) {
         return null;
      }

      try {
         Class<?> util = resolveClass(BUILD_MENU_UTIL);
         Method method = util.getMethod(BUILD_MENU_METHOD, Level.class, PlayerMob.class, int.class, int.class);
         return (List<InventoryRange>)method.invoke(null, level, player, tileX, tileY);
      } catch (Throwable t) {
         return null;
      }
   }

   /**
    * Appends the network's inventories to the list, when the connected rule holds. Called from the
    * server advice, so it is inlined into Build Menu's own method — it must stay public and cheap,
    * and any failure here must not escape into a placement.
    */
   public static void appendNetworkRanges(Level level, PlayerMob player, List<InventoryRange> ranges) {
      if (!enabled) {
         return;
      }

      if (level == null || player == null || ranges == null) {
         return;
      }

      // The server decides, and a client cannot: on a client the level is not a server and the
      // player has no ServerClient, so both checks fail and nothing is appended.
      if (!level.isServer()) {
         return;
      }

      ServerClient client = player.getServerClient();
      if (client == null) {
         return;
      }

      RemoteTerminal.Resolved resolved = RemoteTerminal.resolve(client, findPairedBinding(player));
      if (!resolved.ok()) {
         return;
      }

      int before = ranges.size();
      for (NetworkStorage member : resolved.terminal.getLinkedUnits()) {
         Inventory inventory = member.getInventory();
         if (inventory != null) {
            ranges.add(new InventoryRange(inventory));
         }
      }

      int added = ranges.size() - before;
      if (added > 0) {
         appended++;
         appendedRanges += added;
      }
   }

   /**
    * Reports a failure that escaped {@link #appendNetworkRanges}, rate-limited hard, for the same
    * reason the inventory hook's own failure reporter is: the call site is a placement, and a fault
    * that repeats would bury the first occurrence.
    */
   public static void appendFailed(Throwable cause) {
      appendedFailures++;
      if (appendedFailures <= 3L) {
         GameLog.warn.println("Arcane Storage: Build Menu support could not be applied to a placement (" + cause
               + "). The placement itself goes through; only the network was not offered."
               + (appendedFailures == 3L ? " Further occurrences will not be logged." : ""));
         cause.printStackTrace();
      }
   }

   /** Reports a failure from the client display or the sweep, rate-limited hard. */
   public static void snapshotFailed(Throwable cause) {
      snapshotFailures++;
      if (snapshotFailures <= 3L) {
         GameLog.warn.println("Arcane Storage: Build Menu's client display could not be refreshed (" + cause + ")."
               + (snapshotFailures == 3L ? " Further occurrences will not be logged." : ""));
         cause.printStackTrace();
      }
   }

   private static volatile long appendedFailures;
   private static volatile long snapshotFailures;

   /** The first paired wireless terminal in the player's bag, as the binding it carries, or null. */
   static RemoteBinding findPairedBinding(PlayerMob player) {
      if (player.getInv() == null || player.getInv().main == null) {
         return null;
      }

      Inventory main = player.getInv().main;
      for (int slot = 0; slot < main.getSize(); slot++) {
         InventoryItem item = main.getItem(slot);
         if (item != null && item.item instanceof WirelessTerminalItem) {
            RemoteBinding binding = RemoteBinding.read(item);
            if (binding != null) {
               return binding;
            }
         }
      }

      return null;
   }

   /** Stores a received summary and asks Build Menu's three snapshots to carry it. Client-side. */
   public static void applyNetworkSummary(Inventory summary) {
      networkSummaryRange = (summary == null || summary.getSize() == 0) ? null : new InventoryRange(summary);
      refreshClientSnapshots();
   }

   /**
    * Returns Build Menu's own list with the cached network summary appended, remembering the former
    * for a later refresh. The argument is replaced, never mutated, so a stale range cannot accumulate.
    */
   static List<InventoryRange> withNetworkSummary(List<InventoryRange> storageRanges) {
      lastBuildMenuSnapshot = storageRanges;
      InventoryRange network = networkSummaryRange;
      if (network == null) {
         return storageRanges;
      }

      List<InventoryRange> combined = new ArrayList<>((storageRanges == null ? 0 : storageRanges.size()) + 1);
      if (storageRanges != null) {
         combined.addAll(storageRanges);
      }

      combined.add(network);
      return combined;
   }

   /**
    * Re-invokes Build Menu's three {@code onStorageSnapshot} methods with the last list they were
    * given, so a change to the network alone refreshes their view without a settlement snapshot.
    */
   private static void refreshClientSnapshots() {
      if (!active) {
         return;
      }

      List<InventoryRange> current = lastBuildMenuSnapshot;
      if (current == null) {
         current = new ArrayList<>();
      }

      for (PatchTarget target : TARGETS) {
         if (!target.snapshot) {
            continue;
         }

         try {
            Class<?> cls = resolveClass(target.className);
            Method method = cls.getMethod(target.methodName, target.argumentTypes);
            method.invoke(null, current);
         } catch (Throwable t) {
            snapshotFailed(t);
         }
      }
   }

   /** The advice inlined into Build Menu's server method. */
   public static final class BuildMenuAvailabilityAdvice {

      private BuildMenuAvailabilityAdvice() {
      }

      @Advice.OnMethodExit
      static void onExit(@Advice.Argument(0) Level level, @Advice.Argument(1) PlayerMob player,
            @Advice.Return(readOnly = false) List<InventoryRange> ranges) {
         // The catch is written here, inside the advice, so it is inlined into the target alongside
         // the call -- the same reason the inventory hook's advice carries its own catch.
         try {
            BuildMenuCompat.appendNetworkRanges(level, player, ranges);
         } catch (Throwable t) {
            BuildMenuCompat.appendFailed(t);
         }
      }
   }

   /** The advice inlined into Build Menu's three client snapshot methods. */
   public static final class SnapshotAdvice {

      private SnapshotAdvice() {
      }

      @Advice.OnMethodEnter
      static void onEnter(@Advice.Argument(value = 0, readOnly = false) List<InventoryRange> storageRanges) {
         try {
            storageRanges = BuildMenuCompat.withNetworkSummary(storageRanges);
         } catch (Throwable t) {
            BuildMenuCompat.snapshotFailed(t);
         }
      }
   }
}
