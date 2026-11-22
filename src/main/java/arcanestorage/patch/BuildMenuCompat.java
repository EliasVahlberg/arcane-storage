package arcanestorage.patch;

import java.lang.instrument.Instrumentation;
import java.lang.reflect.Field;
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

   /**
    * A class and method to patch, the advice to weave in, and -- for the three that keep a snapshot list -- how to
    * find the instance holding that list.
    *
    * <p>{@code instanceAccessor} and {@code instanceField} are the two shapes Build Menu uses: the form exposes a
    * static getter, the two tools keep a private static field. {@code listField} is the instance field the target
    * stores its snapshots in, which is what decides whether it already has ours.
    */
   private static final class PatchTarget {
      final String className;
      final String methodName;
      final Class<?>[] argumentTypes;
      final Class<?> returnType;
      final Class<?> adviceClass;
      final boolean snapshot;
      final String instanceAccessor;
      final String instanceField;
      final String listField;

      PatchTarget(String className, String methodName, Class<?>[] argumentTypes, Class<?> returnType,
            Class<?> adviceClass, boolean snapshot) {
         this(className, methodName, argumentTypes, returnType, adviceClass, snapshot, null, null, null);
      }

      PatchTarget(String className, String methodName, Class<?>[] argumentTypes, Class<?> returnType,
            Class<?> adviceClass, boolean snapshot, String instanceAccessor, String instanceField,
            String listField) {
         this.className = className;
         this.methodName = methodName;
         this.argumentTypes = argumentTypes;
         this.returnType = returnType;
         this.adviceClass = adviceClass;
         this.snapshot = snapshot;
         this.instanceAccessor = instanceAccessor;
         this.instanceField = instanceField;
         this.listField = listField;
      }
   }

   /** The field name both snapshot targets store their ranges in. */
   private static final String LIST_FIELD = "remoteStorageRanges";

   /** All four targets, checked and woven together. */
   private static final PatchTarget[] TARGETS = {
      new PatchTarget(BUILD_MENU_UTIL, BUILD_MENU_METHOD,
            new Class<?>[]{Level.class, PlayerMob.class, int.class, int.class}, List.class,
            BuildMenuAvailabilityAdvice.class, false),
      new PatchTarget(BUILDING_MENU_FORM, SNAPSHOT_METHOD, new Class<?>[]{List.class}, null,
            SnapshotAdvice.class, true, "getActiveInstance", null, LIST_FIELD),
      new PatchTarget(PASTE_TOOL, SNAPSHOT_METHOD, new Class<?>[]{List.class}, null,
            SnapshotAdvice.class, true, null, "activeInstance", LIST_FIELD),
      new PatchTarget(PLACE_TOOL, SNAPSHOT_METHOD, new Class<?>[]{List.class}, null,
            SnapshotAdvice.class, true, null, "activeInstance", LIST_FIELD)
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

   /** Re-apply the cached summary no more than twice a second, so an open tool is never stale for long. */
   private static final long REAPPLY_INTERVAL_MS = 500L;

   private static volatile long lastReapplyMs;

   /** How many times the client tick advice has run, whatever it then decided to do. Diagnostic. */
   private static volatile long clientTicks;

   /** The range handed over on the previous pass, which is how a target still holding it is recognised. */
   private static volatile InventoryRange lastHandedOver;

   /** How many times a summary has been handed to a Build Menu snapshot. Diagnostic, read by the harness. */
   private static volatile long applied;

   /** The same count, per target, so a test can say which target was handed one. */
   private static final java.util.Map<String, Long> APPLIED_PER_TARGET = new java.util.concurrent.ConcurrentHashMap<>();

   private static void countHandover(String className) {
      applied++;
      APPLIED_PER_TARGET.merge(className, 1L, Long::sum);
   }

   /**
    * The cached-summary re-apply's own tick count.
    *
    * <p>Read by the harness, which drives a real {@code Client} headlessly. A weave that silently failed and
    * a tick with nothing cached both leave no trace anywhere else, and the first means the whole client half
    * of this feature is doing nothing in players' games.
    */
   public static long clientTickCount() {
      return clientTicks;
   }

   /**
    * How many times the cached summary has actually been handed to one of Build Menu's snapshots.
    *
    * <p>The counter that matters for the tooltip: the form rebuilds its item elements on every handover, so a
    * steady increase while nothing has changed is the flash, and no increase is the fix.
    */
   public static long appliedCount() {
      return applied;
   }

   /** The same count for one target class, or zero. Read by the harness to name which target was told. */
   public static long appliedCount(String className) {
      Long count = APPLIED_PER_TARGET.get(className);
      return count == null ? 0L : count;
   }

   /**
    * Whether a target's own list currently holds the cached summary, read through the same field the re-apply
    * checks. Null when the target has no instance in play, which is what the harness sees with no form open.
    */
   public static Boolean targetHoldsRange(String className) {
      for (PatchTarget target : TARGETS) {
         if (!target.className.equals(className)) {
            continue;
         }

         try {
            Object instance = instanceOf(target);
            if (instance == null) {
               return null;
            }

            return containsIdentity(heldBy(instance, target.listField), networkSummaryRange);
         } catch (Throwable t) {
            snapshotFailed(t);
            return null;
         }
      }

      return null;
   }

   public static boolean isEnabled() {
      return enabled;
   }

   public static boolean isActive() {
      return active;
   }

   /**
    * For the harness only: flips the installed flag at runtime, so the sweep gate can be exercised
    * without a second boot. Clearing also drops any per-client state the sweep had built.
    */
   public static void setActive(boolean nowActive) {
      active = nowActive;
      if (!nowActive) {
         BuildMenuSummary.forget();
      }
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
         BuildMenuSummary.forget();
         return;
      }

      // All-or-nothing: resolve and check every target before weaving any.
      for (PatchTarget target : TARGETS) {
         Class<?> cls = resolveClass(target.className);
         if (cls == null) {
            BuildMenuSummary.forget();
            return; // Build Menu is not installed. Soft dependency: nothing happens.
         }

         String problem = signatureProblem(cls, target.methodName, target.argumentTypes, target.returnType);
         if (problem != null) {
            logMismatch(problem + " (" + target.className + ")");
            BuildMenuSummary.forget();
            return;
         }

         // The snapshot targets are also read, not only patched: the re-apply has to know whether one already holds
         // our range. A field renamed in a Build Menu update would leave that check silently answering "no" for
         // ever, which is a per-tick rebuild -- the flash again -- so it is checked here with the signatures.
         String state = clientStateProblem(cls, target.instanceAccessor, target.instanceField, target.listField);
         if (state != null) {
            logMismatch(state + " (" + target.className + ")");
            BuildMenuSummary.forget();
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

   /**
    * Why the shape the re-apply reads does not match, or null when it does.
    *
    * <p>These members are read rather than patched, so they get the same treatment as the patched methods: checked
    * once at load, with the same single log line, and a mismatch turns the whole compat off. The alternative is a
    * compat that installs, offers the network once, and then re-hands it to a target it cannot see is holding it --
    * which is the tooltip flash again, from a cause nothing would name.
    *
    * <p>Plain parameters rather than a target, so the check is a unit test with a stand-in class.
    */
   public static String clientStateProblem(Class<?> targetClass, String instanceAccessor, String instanceField,
         String listField) {
      if (targetClass == null) {
         return "no such class";
      }

      if (listField != null) {
         Field list;
         try {
            list = targetClass.getDeclaredField(listField);
         } catch (NoSuchFieldException changed) {
            return "no " + listField + " field";
         }

         if (!List.class.isAssignableFrom(list.getType())) {
            return listField + " holds " + list.getType().getName() + ", not a list";
         }
      }

      if (instanceAccessor != null) {
         try {
            Method accessor = targetClass.getMethod(instanceAccessor);
            if (!Modifier.isStatic(accessor.getModifiers())) {
               return instanceAccessor + " is not static";
            }
         } catch (NoSuchMethodException changed) {
            return "no " + instanceAccessor + " method";
         }
      } else if (instanceField != null) {
         try {
            if (!Modifier.isStatic(targetClass.getDeclaredField(instanceField).getModifiers())) {
               return instanceField + " is not static";
            }
         } catch (NoSuchFieldException changed) {
            return "no " + instanceField + " field";
         }
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

   /**
    * How many client-display refreshes have failed, counted even once logging stops.
    *
    * <p>Read by the harness: a failure here is caught and logged rather than thrown, so a broken client path
    * presents as "installed and doing nothing" unless something counts the failures.
    */
   public static long snapshotFailureCount() {
      return snapshotFailures;
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
    *
    * <p><b>Public for a runtime reason, not an API one.</b> ByteBuddy inlines the calling advice into
    * Build Menu's own classes, which are in a different package, so a package-private method is not
    * reachable from the inlined call site and every invocation fails with {@code IllegalAccessError}.
    * That is invisible to the unit tests -- they call this from the same package -- and the advice's
    * own catch turns it into a logged warning and a list with no network on it, so the symptom is a
    * compat that appears to be installed and offers nothing.
    */
   public static List<InventoryRange> withNetworkSummary(List<InventoryRange> storageRanges) {
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
    * Re-applies the cached summary to Build Menu's three snapshots, client-side, when there is something new.
    *
    * <p><b>Why "when there is something new" is the whole point.</b> Calling a target's
    * {@code onStorageSnapshot} is not a cheap notification: the form sets its own refresh flag and rebuilds every
    * item element of its visible categories on the next draw, which destroys and recreates the element under the
    * cursor -- and with it the item tooltip. Re-applying an unchanged summary twice a second therefore made the
    * tooltip flash on and off while hovering, at exactly the re-apply cadence, and only when this compat had
    * something to hand over.
    *
    * <p>So a target is told only while it does not already hold our range. That is one condition for both goals:
    * while it holds the range nothing happens and nothing is rebuilt, and once it has lost it -- which Build Menu's
    * {@code cancelStorageSubscription} does by replacing the list with an empty one -- it is handed it exactly
    * once.
    *
    * <p><b>That second half cannot be left to the game, and this was nearly lost.</b> Build Menu subscribes from
    * inside a settlement, and its subscribe packet is answered with a snapshot only when a settlement covers the
    * player's tile; outside one the server unsubscribes and sends nothing at all. So hiding and reopening the menu
    * outside a settlement would otherwise never offer the network again until the summary happened to change.
    *
    * <p>The tick is counted before any of those guards, because the guards are what make a missing invocation
    * invisible: with nothing cached this returns immediately having done nothing, which is indistinguishable from
    * advice that was never woven in. {@link #clientTickCount()} is the difference between the two, and it is the
    * only way a headless test can tell them apart.
    *
    * <p>Public for the same reason as {@link #withNetworkSummary}: the advice is inlined into {@code Client.tick()},
    * in the game's own package, and a package-private method there fails with {@code IllegalAccessError} -- on
    * every client tick, swallowed by the advice's guard, leaving the re-apply doing nothing in a player's game
    * while every test still passed.
    */
   public static void clientTickReapply() {
      clientTicks++;

      if (!active || networkSummaryRange == null) {
         return;
      }

      long now = System.currentTimeMillis();
      if (!shouldReapply(now, lastReapplyMs)) {
         return;
      }

      lastReapplyMs = now;
      refreshClientSnapshots();
   }

   /** Whether a re-apply is due. Extracted so the cadence is a unit test rather than a wait. */
   static boolean shouldReapply(long now, long lastReapplyMs) {
      return now - lastReapplyMs >= REAPPLY_INTERVAL_MS;
   }

   /**
    * Hands the cached summary to whichever of Build Menu's three targets does not already hold it.
    *
    * <p>Per target rather than all at once, deliberately: the form rebuilds its item grid when it is told, and the
    * tools only replace a list. A tool that has lost the range must not cost the form a rebuild, which is the flash.
    *
    * <p>{@link #appliedCount()} counts handovers rather than passes, so "nothing was handed over" means literally
    * nothing Build Menu reads was replaced.
    */
   private static void refreshClientSnapshots() {
      if (!active) {
         return;
      }

      List<InventoryRange> current = lastBuildMenuSnapshot;
      if (current == null) {
         current = new ArrayList<>();
      }

      InventoryRange range = networkSummaryRange;
      for (PatchTarget target : TARGETS) {
         if (!target.snapshot) {
            continue;
         }

         try {
            Object instance = instanceOf(target);
            if (instance == null) {
               // No form open and no tool active: there is nothing of Build Menu's to keep in step. Deliberately
               // different from a target that has lost the range -- one has a list, the other does not exist.
               continue;
            }

            if (!needsHandover(heldBy(instance, target.listField), range, lastHandedOver)) {
               continue;
            }

            Class<?> cls = resolveClass(target.className);
            Method method = cls.getMethod(target.methodName, target.argumentTypes);
            method.invoke(null, current);
            countHandover(target.className);
         } catch (Throwable t) {
            snapshotFailed(t);
         }
      }

      lastHandedOver = range;
   }

   /**
    * Whether this target has to be told about {@code wanted}, given what its own list holds.
    *
    * <p>The core of the flash fix, and a rule rather than plumbing so it can be a unit test. Identity rather than
    * content on purpose: the range object we put in is the one we look for, and anything else in the list belongs
    * to Build Menu.
    *
    * <ul>
    *   <li>It already holds this exact range: nothing to do, and telling it again rebuilds its grid for nothing.</li>
    *   <li>It holds an older range of ours: a new summary arrived, so hand the new one over.</li>
    *   <li>It holds neither, or an empty list: it has been reset, so hand over -- once.</li>
    *   <li>Our summary has been cleared: only a target still holding the old range needs telling.</li>
    * </ul>
    */
   static boolean needsHandover(List<?> held, InventoryRange wanted, InventoryRange previouslyHandedOver) {
      if (wanted == null) {
         return previouslyHandedOver != null && containsIdentity(held, previouslyHandedOver);
      }

      return !containsIdentity(held, wanted);
   }

   /** Whether a list holds this exact object: the only way we can recognise our own range inside it. */
   static boolean containsIdentity(List<?> held, Object wanted) {
      if (held == null || wanted == null) {
         return false;
      }

      for (Object entry : held) {
         if (entry == wanted) {
            return true;
         }
      }

      return false;
   }

   /** The target's active instance, or null when Build Menu has none of it in play. */
   private static Object instanceOf(PatchTarget target) throws ReflectiveOperationException {
      Class<?> cls = resolveClass(target.className);
      if (cls == null) {
         return null;
      }

      if (target.instanceAccessor != null) {
         return cls.getMethod(target.instanceAccessor).invoke(null);
      }

      Field field = cls.getDeclaredField(target.instanceField);
      field.setAccessible(true);
      return field.get(null);
   }

   /** The list an instance stores its snapshots in, or null when that field does not hold a list. */
   private static List<?> heldBy(Object instance, String listField) throws ReflectiveOperationException {
      Field field = instance.getClass().getDeclaredField(listField);
      field.setAccessible(true);
      Object value = field.get(instance);
      return value instanceof List ? (List<?>)value : null;
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
