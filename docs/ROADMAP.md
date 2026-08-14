# Roadmap

Ordered so that every phase ends with something playable. Each phase lists what
"done" means, because "storage works" is not a testable claim.

Priority ordering follows evidence about what players actually install a storage
mod for, not the order Magic Storage happens to present its features. Adoption
rests on four things: **one view showing everything, search, craft from storage,
and deposit-all.** Everything after that is retention.

Necesse also ships more of this than Terraria does, so parts of Magic Storage's
feature list are redundant here and are deliberately not reimplemented. Where a
deliverable extends a vanilla feature rather than inventing one, it says so — the
mod's description should be honest about that too.

## Correctness — never deferrable

These are not performance concerns and are never traded for progress. Losing or
duplicating items is unrecoverable from the player's side, and no later
optimisation repairs an already-corrupted save.

- **No item loss or duplication.** The full matrix of deposit and withdraw
  interactions, including a member container mutated concurrently, never creates
  or destroys an item. This is the characteristic bug of this class of mod.
- **No stale-view errors.** A container changed by something else while the
  interface is open never causes a wrong-item or phantom-item action. Necesse hit
  this exact bug in its own nearby-inventory crafting and fixed it, so it is a
  demonstrated failure mode in this engine, not a hypothetical.
- **Discoverability.** A player who has read no documentation can find and link a
  container. Toggle and filter state must be legible enough not to look broken.

Test for duplication explicitly at every phase. Do not assume it.

**One instance of this has already been found and fixed** (`f68875b`), which is why the
rule is stated so plainly. Breaking a storage unit while the terminal was open left its
slots registered against the removed object entity's `Inventory`: withdrawing produced items
the world no longer contained, and depositing wrote into an object that was never saved. It
presented as "looks like an item dupe but the item disappears". The fix checks every linked
unit in `Container.isValid`, which `ServerClient` calls each tick, so the terminal closes the
moment its network is invalidated. **Verified in game, Aug 2026:** the terminal closes
immediately on breaking a unit, with no stale entries in the grid beforehand.

Item conservation is now asserted mechanically rather than by eye — the session round trip
checks the total across every unit plus the player's inventory before and after each
action, so a withdraw that gains or loses one item fails even when the grid looks right.

## Performance — deferred, but with numbers

Optimisation comes after the basics work. These are recorded as targets rather
than gates, and they are concrete because the comparable mod's failure points are
documented:

- **Scale:** the interface opens and stays interactive at **≥3,000 distinct
  stacks**. Magic Storage is reported to freeze for seconds to minutes there, and
  to lag at ~1,500 in multiplayer.
- **The recipe list is never silently wrong.** Recipes must not vanish as item
  count grows. This one straddles the line — the cause is scale but the symptom is
  correctness — so it cannot be deferred indefinitely.

## Completed -- phases 0 through 5c

Moved to [`ROADMAP_COMPLETED.md`](ROADMAP_COMPLETED.md) on 14 August 2026 with their acceptance
criteria and design notes intact. In brief, in build order:

| Phase | What it delivered |
|---|---|
| 0 | Skeleton: mod entry, registration, a placeable terminal |
| 1 | The aggregated view -- one list over every member container, summed and sorted |
| 2 | Network membership by topology, and persistence across save and load |
| 3 | Usable at scale: search, category filter, sorting, deposit-all, capacity feedback |
| 4 | Crafting from the network, with per-ingredient availability and installed stations |
| 5 | Transfer rules and the two buses |
| 5b | The transfer resolver -- convergence, idle cost and throughput, all measured |
| 5c | The logistics tab, bus naming, and the reworked rule panel |

One item accepted during Phase 4 is still open, and is carried below rather than archived.

## Carried forward from Phase 4

- [ ] **Station slots move off the terminal onto their own unit** — *accepted from
      Elias's design pass, Aug 2026; supersedes the ten slots that used to be on the terminal.*

      **Implemented, unverified in game — deliberately still unticked.** The object, its entity, the
      network interface, the terminal's discovery and tech lookup, the container's slot addressing, the
      Stations tab, the recipe, the locale, the harness alias and verb, and the load-time migration are
      all in. Nine new headless tests pass, as do the 40 existing station and crafting ones. What no
      headless test can see is the tab's layout and the migration actually handing benches back in a
      real world, and the migration is the one part that can lose a player's property. The checklist is
      in `docs/QA_BACKLOG.md` under "Station Units". This ticks when that has been walked through.
      A **Station Unit** carries the slots and joins the network by the same connectivity
      rule as a Storage Unit, so station capacity becomes a placed, paid-for resource
      rather than a constant I picked. Ladder **1 → 2 → 4 → 8** slots across vanilla's four
      station tiers (base → Demonic → Tungsten → Fallen).

      Why this is right rather than just more content: the mod already says capacity comes
      from units and reach comes from conduits, and free station access was the one
      capability breaking that grammar. It also deletes an arbitrary number — ten was
      defensible ("eight station families plus headroom") but it was really "what fits in a
      row". And it composes with tiering for free, because an upgraded bench reports the
      lower techs too, so **upgrading a bench frees a slot**: progression that makes an
      existing setup neater, which is the kind players value.

      **The one real constraint.** Station slot indices today are the terminal's own
      inventory: fixed count, fixed order, identical on both sides. Slots discovered
      through the network are not — membership is discovered independently per side, and
      the client *does* send slot indices when it moves an item. Mitigation: enumerate
      Station Units deterministically (tileX then tileY) and keep station slots before
      network slots, so identical membership gives identical indices. Where membership
      differs — a client short of level data — a bench can land in a slot other than the
      intended one: bounded, recoverable, never a duplication, and only in an
      already-degraded state. The alternative that removes it entirely is to keep the slots
      on the terminal and let Station Units grant *how many are usable*, which keeps
      determinism but loses "the bench is in the block". Taking the metaphor, with the
      fallback recorded here.

      **Migration:** a terminal today holds up to ten benches in its own inventory, and
      dropping it to zero slots would let `InventorySave` truncate them away silently.
      Needs a load-time drop, or a warning to empty terminals first. *Elias has benches
      installed in his current world right now.*

## Phase 6 — Progression, content and art

Two ladders, progressing separately. Capacity growth sits underneath every tier;
the mechanics on top are what make a tier worth reaching. Spending the whole
ladder on one number is what makes the comparable mod's progression stop being
interesting.

Anchor the gates to Necesse's own four crafting-station tiers — base → Demonic →
Tungsten → Fallen — so each upgrade lands where the player is already upgrading,
with materials they are already gathering.

**The network:** tier 1 is the complete core, ungated. Then crafting and the
import bus, then the export bus, then remote access, with recursive crafting as an
optional endgame tier.

**The storage units:** filters, then reduced spoil rate plus a per-unit toggle for
whether settlers may draw from it, then spoilage fully stopped — which maps onto a
paused-spoiling state vanilla already has.

- [ ] **Custom panel sprites for the terminal interface**, so it reads as this mod's
      interface rather than a vanilla form *(deliberately late: it is the one visual
      change that cannot be judged until the layout has stopped moving)*

      **Implemented, unverified in game — deliberately still unticked.** `ArcanePanel` wraps a
      `GameBackgroundTextures` over `ui/arcanestoragepanel` and `ui/arcanestoragepaneledge`, applied per
      tab and to the bus rules form. The geometry was verified by reconstructing a panel from the slices,
      but the engine's own nine-slice reader has never drawn it, and only that counts. Checklist in
      `docs/QA_BACKLOG.md` under "The custom panel".

      **The trade-off below was decided rather than deferred:** the panel is on by default and
      `ArcaneStorageSettings.useCustomPanel` turns it off, restoring `GameBackground.form`. A setting
      because the cost falls on a specific player — anyone who deliberately chose an interface style has
      that choice overridden for our forms — and that is a real cost but not one worth giving up the
      mod's own identity for by default.

      The mechanism is already known, so this does not need research when it comes up.
      `Form.setBackground(GameBackground)` is public and every form defaults to
      `GameBackground.form`, so the terminal can carry its own panel without touching
      anything global. `GameBackground` is not one image: it is outline, centre and edge
      draw options plus tiled variants and a `getContentPadding()`, so the art request is
      a nine-slice set rather than a single PNG.

      **The trade-off to decide then, not now:** `GameBackground.form` delegates to
      `Settings.UI.form`, which is the player's chosen interface style — Necesse ships more
      than one and shows a selector when it has several. A custom panel therefore *ignores
      that choice* for our form, which is the point when it makes the mod feel distinct and
      the cost when a player has deliberately themed their game. Registering a whole
      `GameInterfaceStyle` is the other option and is the wrong one: it re-skins the entire
      game rather than one interface.

- [ ] Recipes for the mod's own objects, tiered to Necesse's progression — *implemented,
      unverified in game, deliberately unticked.* **Each rung consumes the rung below it**, which is
      what makes this an upgrade path rather than four unrelated objects — nothing is stranded and no one
      accumulates obsolete units. A consequence worth stating: the lower tiers' string IDs can now never
      be retired, because a recipe names them.

      **The costs are not repeated here. They live in `src/main/resources/recipes.properties`,** which the
      Java loads at startup and the Python tests parse directly, with the reasoning for each number written
      beside it.

      That is a correction, not a formatting preference. This document used to carry the whole table, and it
      was wrong for two commits without anything noticing: the unit materials were changed in the enum and
      not here, then here and not in the enum, and the terminal, conduit and bus costs stated above were
      **never in the code at all** — the terminal really cost anylog 8 and ironbar 2 while this table said
      80 and 20, and the conduit was priced in logs while this table said iron. A commit written to fix the
      first half asserted the second half was fine without checking it. Prose restating a number the code
      also holds is a copy, and copies drift silently; a pointer cannot.

      What belongs here instead is the shape rather than the values. The tier below is consumed **at one and
      deliberately not scaled** — scaling compounds to 125 base units for one Fallen, an accident rather
      than a curve. Unit materials are kept **modest on purpose**: a player owns many units, and a per-unit
      price that reads as a grind stops the upgrade being something to look forward to, which is the whole
      point of having a ladder.

      Two things here are still open rather than decided. The conduit and both buses remain **hand-crafted**
      — their costs are set but no station gates them, so a bus is still reachable in the first minutes of a
      world. And an earlier note in this file claimed this item was implemented while three of the eight
      objects were still on placeholder costs; that was an overstatement and the costs above replace them.
- [ ] Capacity growth per tier, plus one real mechanic per tier — *implemented for both unit
      ladders, unverified in game, deliberately unticked.* Storage Units 40 → 80 → 160 → 320
      stacks; Station Units 1 → 2 → 4 → 8 sockets. Four rungs because Necesse has exactly four
      station tiers, so every upgrade lands where the player is already upgrading. Doubling
      because it is the only curve a player can predict without a table. The base 40 is vanilla's
      container ceiling, so a first unit is worth exactly one chest and the ladder starts from
      parity rather than an advantage. Sockets are the Station Unit's real mechanic; **the
      terminal's mechanic gating (Ladder A) is not done and is not mine to decide** — see below.

      **Half verified in game Aug 2026:** the Storage Unit ladder was placed tier by tier and capacity
      grows between them as designed. Still unverified, and why the box stays unticked: the **socket
      ladder** (1 → 2 → 4 → 8), which is the other half of this item and a different code path — sockets
      are published in tile order and laid out by `buildStationsTab`, and only one socket has ever been
      seen working. Also unverified that the **recipes** are reachable, since placing an object never
      exercises one.
- [ ] A non-spoilage perk on every unit tier that offers one — *not started, and blocked on the
      open question below rather than on effort.*
- [ ] Localization complete — six new object names added for the tiers; the rest still outstanding.
- [x] Textures for all objects and UI elements — **verified in game Aug 2026.** Every registered object
      has both a world sprite and an inventory icon (15 object files, 12 icons; the unpaired
      `arcanestorageterminal_open` is a state variant like the buses' `_inactive` files and correctly has
      no icon), and the UI panel's nine-slice pair is in place. The tier sprites were the last gap: twelve
      hand-drawn files, 32x32, hard alpha, at most nine colours, silhouettes pixel-identical to the base
      tier, recoloured from luminance-matched generated ramps so the shading structure survived the hue
      change. See request H in `SPRITES.md`.

      Ticked on a placed-tile check, which is the only one that could settle it. The risk was Tungsten
      against Fallen: both deliberately desaturated and, by construction, at identical luminance per step,
      so hue at low saturation was all that separated them — the combination that survives an editor
      side-by-side and then converges under the game's ambient light. It does not converge.

**Deliberately not done in the tiering pass, and needing a decision rather than work:**
Ladder A gates already-built features behind tiers — craft-from-network at Demonic, the export
bus at Tungsten, remote access at Fallen. Implementing that would take capabilities the player
has today and lock them behind progression, which is a player-visible regression for anyone
mid-world and not a call to make unilaterally. The unit ladders were safe to build because they
only add new objects alongside the existing ones.

**Open, and worth resolving before building the ladder:** food only spoils in
survival mode. If unit tiers reward spoilage resistance and nothing else, three of
five tiers are dead content in any non-survival world. Candidate second perks:
per-unit reserve floors, overflow routing, auto-restocking an adjacent station, or
letting one unit stay in the network from another level.

Also open: whether the terminal upgrades in place or each tier is a distinct
object. In-place avoids the item sprawl that a 23-items-for-one-number ladder
produces.

**Done when:** the mod is obtainable through normal play, does not trivialise the
early game, and no tier is dead content.

## Phase 7 — Multiplayer and release

- [ ] Verified on a dedicated server with more than one client
- [ ] Concurrent access from two players does not desync or duplicate
- [ ] Workshop preview image and description, honest about what extends vanilla
- [ ] Published

**Done when:** it works on a real server, not just in singleplayer.

## Working with other mods

Raised as a priority Aug 2026, and the reasoning is Magic Storage's and AE2's: a storage mod earns its
keep in a heavily modded game, so third-party stations and items working here is a feature rather than a
courtesy. The stance is to get it by asking the game's own questions instead of recognising the game's
own classes, so that compatibility is a property of how the mod is written and not a compatibility layer
bolted on later.

Findings and the rule that came out of it are in [MOD_COMPAT.md](MOD_COMPAT.md). In short: Necesse has
no tag or annotation system, it has capability interfaces, and the station gate now asks whether a
station needs to be standing somewhere rather than which class it extends — verified against all 26
vanilla stations.

- [x] **Network membership as an interface**, so another mod's object entity can join a network without
      a patch — *done Aug 2026, before Phase 5 deliberately.* `NetworkStorage extends OEInventory`
      (contributes slots), `NetworkConductor` (carries membership), `NetworkNode` (the network visibly
      meets this). Extending the game's own `OEInventory` is what makes it free: vanilla's
      `OEInventoryContainerSlot` takes that interface, so a foreign member needs no adapter and inherits
      the vanilla quick-stack, restock and sort answers. Verified by a unit test implementing a member
      that knows nothing else about this mod. Buses must be built through these rather than beside
      them.
- [ ] **A published integration API** — deliberately not yet. An interface nobody has implemented is a
      guess; make the internals general, ship, and let the first real request shape the surface.

## Decisions required

Not blocked on code. Both are recorded because inheriting them silently would be
worse than choosing them.

- ~~**Crafting stations: items in slots, or placed stations?**~~ **Settled Aug 2026:**
  benches are installed into the terminal and proximity is not used. This entry
  outlived the decision and was contradicting Phase 4, which is worse than either
  answer. The divergence from Necesse's own idiom is real and was chosen knowingly.
- **Remote access range metric.** Waystones are Necesse's built-in fast-travel
  network, which offers a diegetic yardstick — reach measured against settlements
  and waystones rather than an arbitrary tile count.

## Deferred

Worth doing, not worth blocking on, and none started.

- **Wirelessly linked silos** — separate clusters joining one logical network
  through paired connectors instead of one contiguous mass. Addresses the
  relocation complaint that dogs Magic Storage, and is probably the clearest way
  not to read as a port.
- **Remote and wireless access** — genuinely absent from vanilla. Note that
  gating it to endgame is itself a common complaint about Magic Storage.
- **Recursive crafting** — ships disabled by default in Magic Storage, which says
  something about its cost-to-benefit.
- **Batch crafting** with a max-craftable indicator.
- **Favourites and lock-from-transfer**, consistent with the existing sort-lock
  and quick-stack-lock modifiers.
- **Network naming**, **recipe blacklist**, **relocation tooling**. Necesse's
  simpler container model may make relocation a non-issue — check before
  spending effort on it.
- **Settlement storage bridge** — optionally surfacing settlement storage in the
  terminal, read-only.

## Non-goals

Recorded to prevent scope creep and to avoid re-implementing the game.

- **Changing vanilla containers or stack sizes.** Three existing Necesse mods
  already sell bigger chests and bigger stacks; competing there means competing
  where this mod has no advantage. Our own units growing in capacity is a
  different thing and is wanted.
- **Capacity as the only progression axis.** See Phase 6.
- **Rebuilding quick-stack, restock, transfer-all, loot-all or single-inventory
  sort.** All vanilla. Extend them to the network instead.
- **Auto-selling.** The Shipping Chest already does trader-mediated selling.
- **Coin compaction, decrafting, a tutorial NPC, password-protected networks.**
  Either inapplicable to Necesse's systems or disproportionate to their value.
- **Settler integration as a foundation.** Joining settlement storage means
  settlers take your items. The import bus is the deliberate alternative.
