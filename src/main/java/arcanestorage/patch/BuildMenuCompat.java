package arcanestorage.patch;

import java.lang.instrument.Instrumentation;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
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
import static net.bytebuddy.matcher.ElementMatchers.returns;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

/**
 * Lets Build Menu place from a storage network, by appending the network's inventories to the one
 * list that decides what a placement may take.
 *
 * <p><b>This is a soft patch of another mod's one choke point, and the shape follows from how the
 * game loads mods.</b> Build Menu's {@code buildAvailabilityRanges(Level, PlayerMob, int, int)}
 * builds the single {@code List<InventoryRange>} that every place, paste and craft path hands to
 * the engine's {@code Recipe.canCraftRange} / {@code craftRange}. Its author declined to add
 * support here, so this mod appends the network's inventories to that list when the player is
 * network-connected, and the engine does the counting and consuming.
 *
 * <p><b>Why not the usual patch annotation.</b> {@code @ModMethodPatch} and its siblings take a
 * {@code Class} literal, and Build Menu is deliberately not on this mod's compile classpath (it is a
 * soft dependency). A name string is the only thing this mod may hold, so the advice is installed
 * here, through ByteBuddy's own agent builder matched by class name, on the instrumentation the game
 * already installed to load mods at all.
 *
 * <p><b>The one Build Menu type named anywhere in this mod.</b> The class and method below are the
 * only identifiers that mention Build Menu, and they are strings rather than types so that their
 * absence is a normal case. Nothing else in the mod refers to Build Menu.
 *
 * <p><b>The connected rule.</b> The player is connected when they carry a wireless terminal paired
 * to a transceiver that still resolves, server-side. The resolution is {@link RemoteTerminal}'s, the
 * same one the wireless terminal itself uses, so "connected for building" and "can open this
 * network" cannot disagree. Nothing from the client is trusted: this runs only on the server, reads
 * the player's real inventory, and resolves against the server's own world.
 *
 * <p><b>The signature is checked, not assumed.</b> A name-based patch binds to an exact signature,
 * so a Build Menu update that renames or retypes the method would otherwise fail silently — a player
 * could place a blueprint and find the network ignored, with nothing in any log. The method name,
 * parameter types and return type are read by reflection before the advice is installed; on a
 * mismatch one line is logged and the compat turns itself off.
 */
public final class BuildMenuCompat {

   private BuildMenuCompat() {
   }

   /** The Build Menu class whose one method this mod appends to. Named by string: soft dependency. */
   private static final String BUILD_MENU_UTIL = "BuildMenu.modclass.util.BuildMenuInventoryUtil";

   /** The one method on that class. */
   private static final String BUILD_MENU_METHOD = "buildAvailabilityRanges";

   /** Set once at load from the config. Read by the advice, so it cannot be changed at runtime. */
   private static volatile boolean enabled;

   /** Whether the advice was actually installed this launch. */
   private static volatile boolean active;

   /** How many times the advice appended at least one network range. Diagnostic, and the proof it ran. */
   private static volatile long appended;

   /** How many ranges the advice appended in total, across every call. Diagnostic. */
   private static volatile long appendedRanges;

   /** Whether a signature problem has been logged, so it is logged once rather than per placement. */
   private static volatile boolean mismatchLogged;

   /** How many times a mismatch was reported, kept so the "once" behaviour is testable. */
   private static volatile int mismatchReports;

   /** For tests and diagnosis: whether the compat is meant to be on this launch. */
   public static boolean isEnabled() {
      return enabled;
   }

   /** For tests and diagnosis: whether the advice is actually woven into Build Menu this launch. */
   public static boolean isActive() {
      return active;
   }

   /** How many times the advice appended network ranges. Read by {@code query buildmenu}. */
   public static long appendedCount() {
      return appended;
   }

   /** How many ranges the advice appended in total. Read by {@code query buildmenu}. */
   public static long appendedRangeCount() {
      return appendedRanges;
   }

   /** How many signature mismatches were reported. Read by the unit tests for the log-once rule. */
   public static int mismatchReports() {
      return mismatchReports;
   }

   /**
    * Installs the advice, after checking the signature. Called once from {@code postInit}.
    *
    * <p>Every early return here is a normal state, not an error: the toggle is off, Build Menu is
    * absent, or Build Menu changed shape. The first two log nothing, so a player without Build Menu
    * never hears about it.
    */
   public static void install() {
      enabled = ArcaneStorage.SETTINGS.buildMenuCompat;
      if (!enabled) {
         return;
      }

      Class<?> util;
      try {
         util = Class.forName(BUILD_MENU_UTIL);
      } catch (ClassNotFoundException absent) {
         return; // Build Menu is not installed. Soft dependency: nothing happens.
      }

      String problem = signatureProblem(util);
      if (problem != null) {
         logMismatch(problem);
         return;
      }

      try {
         Instrumentation instrumentation = ByteBuddyAgent.install();
         AtomicInteger completed = new AtomicInteger();
         new AgentBuilder.Default().disableClassFormatChanges().with(AgentBuilder.RedefinitionStrategy.RETRANSFORMATION)
               .with(new AgentBuilder.RedefinitionStrategy.Listener() {
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
               }).type(named(BUILD_MENU_UTIL))
               .transform((builder, type, classLoader, module) -> builder.visit(Advice.to(BuildMenuAvailabilityAdvice.class)
                     .on(named(BUILD_MENU_METHOD).and(takesArguments(Level.class, PlayerMob.class, int.class, int.class))
                           .and(returns(List.class)))))
               .installOn(instrumentation);
         active = completed.get() > 0;
         if (!active) {
            logMismatch("the method was not transformed (the advice did not match)");
         }
      } catch (Throwable t) {
         active = false;
         logMismatch("the patch could not be installed: " + t);
      }
   }

   /**
    * Why the patch cannot target the given class, or null when it can. Extracted and made testable
    * so a changed signature is a unit test rather than a mystery at load.
    */
   public static String signatureProblem(Class<?> util) {
      if (util == null) {
         return "no such class";
      }

      Method method;
      try {
         method = util.getMethod(BUILD_MENU_METHOD, Level.class, PlayerMob.class, int.class, int.class);
      } catch (NoSuchMethodException changed) {
         return "no buildAvailabilityRanges(Level, PlayerMob, int, int) method";
      }

      if (!Modifier.isStatic(method.getModifiers())) {
         return "buildAvailabilityRanges is not static";
      }

      if (!List.class.isAssignableFrom(method.getReturnType())) {
         return "buildAvailabilityRanges returns " + method.getReturnType().getName() + ", not a List";
      }

      return null;
   }

   /** Logs a mismatch once per launch, so a changed signature cannot fill the log on every placement. */
   private static void logMismatch(String reason) {
      if (mismatchLogged) {
         return;
      }

      mismatchLogged = true;
      mismatchReports++;
      GameLog.warn.println("Arcane Storage: Build Menu support is off this launch: " + reason
            + ". The network is not offered to Build Menu, and everything else works as normal.");
   }

   /**
    * The patched method's ranges, or null when the patch is not active. For tests and diagnosis.
    */
   @SuppressWarnings("unchecked")
   public static List<InventoryRange> availabilityRanges(Level level, PlayerMob player, int tileX, int tileY) {
      if (!active) {
         return null;
      }

      try {
         Class<?> util = Class.forName(BUILD_MENU_UTIL);
         Method method = util.getMethod(BUILD_MENU_METHOD, Level.class, PlayerMob.class, int.class, int.class);
         return (List<InventoryRange>)method.invoke(null, level, player, tileX, tileY);
      } catch (Throwable t) {
         return null;
      }
   }

   /**
    * Appends the network's inventories to the list, when the connected rule holds. Called from the
    * advice, so it is inlined into Build Menu's own method — it must stay public and cheap, and any
    * failure here must not escape into a placement.
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

      RemoteBinding binding = findPairedBinding(player);
      if (binding == null) {
         return;
      }

      RemoteTerminal.Resolved resolved = RemoteTerminal.resolve(client, binding);
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

   private static volatile long appendedFailures;

   /** The first paired wireless terminal in the player's bag, as the binding it carries, or null. */
   private static RemoteBinding findPairedBinding(PlayerMob player) {
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

   /** The advice inlined into Build Menu's method. Kept here so the Build Menu name stays in one file. */
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
}
