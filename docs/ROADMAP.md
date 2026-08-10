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

## Phase 0 — Skeleton ✅

Repo, build, and a mod that loads.

- [x] Project builds against Necesse 1.3.2, producing a loadable jar
- [x] `make build/run/dev/server/doctor` work
- [x] `@ModEntry` class with lifecycle stubs

**Done when:** the game launches with the mod present and no errors in the log.

## Phase 1 — Aggregated view ✅

The core proof: one UI showing the contents of several containers at once.

- [x] A placeable **Storage Terminal** object with an object entity and container
- [x] A placeable **Storage Unit** the player cannot open — a terminal is the only
      way to reach its contents
- [x] Terminal container aggregates the contents of a set of linked containers
- [x] Aggregated grid shows combined stack counts (60 iron across 3 units reads
      as one entry of 60)
- [x] Withdraw an item — pulled from whichever unit holds it
- [x] Deposit an item — routed into a unit with space
- [x] Contents stay correct when a linked unit is edited directly while the
      terminal is open

**Done when:** in singleplayer, two storage units with different contents can be
viewed and modified through one terminal, and no items are duplicated or lost.

**Verified in game, Aug 2026.** Withdrawal was tested against exact counts —
partial stacks gathered across two units, a full player inventory refusing the
transfer, non-stackables, and repeated clicking — with nothing duplicated or
lost. Click conventions match the rest of the game: plain click fills the cursor,
quick-move transfers to the inventory.

Two honest caveats, neither covered by the criteria above:

- **Deposit preference is the engine's, not ours.** Deposit routes through the
  engine's quick-transfer machinery, so whatever preference exists for units
  already holding an item is the engine's stacking order. It has not been
  isolated and tested, and no code here implements it.
- **Membership is still adjacency scaffolding** — orthogonal neighbours,
  recomputed each time a terminal opens. Phase 2 owns real topology.

## Phase 2 — Network membership and persistence

- [x] Units join a network by topology — units connected to units, not just to the
      terminal *(harness-verified; the container half awaits in-game QA — see below)*
- [x] Extended by a connector object for range — the **Storage Conduit** conducts a
      network onward without holding anything, so layout no longer fights capacity
      *(server logic harness-verified; client rendering compiled but not yet run)*
- [x] Membership survives save/load and level changes — verified across a real server
      restart: unit contents, conduit-linked networks, multi-path counting, and a
      network 40 tiles from spawn in regions the spawn area never touches. Free by
      design: membership is recomputed from layout, so there is no membership to
      persist, only the objects themselves
- [x] Removing a terminal or a linked unit degrades gracefully rather than
      orphaning items *(verified in game: breaking a unit closes an open terminal
      immediately, with no stale entries — and the harness covers orphaning
      beyond a broken unit or conduit)*
- [x] A unit belongs to only one network — true by construction: membership is
      connectivity, so a unit lies in exactly one connected component. Two
      terminals on that component share it rather than competing for it
- [x] A sensible bound on how far a network may reach — `MAX_CONDUITS = 256`
      bounds reach, separately from `MAX_UNITS = 64` which bounds capacity. A
      conduit is cheap and holds nothing, so without its own bound a long run
      would make discovery expensive for a network storing nothing
- [x] Several terminals may access one network *(harness-verified: two terminals on
      one chain both resolve the same 3 units)*

### Verification

Two tiers, because `Container` is built from the player's inventory and so cannot exist
without one. Both use the same command, so a check is one line either way.

- `make test` — unit tests over game-independent logic (the network traversal). Sub-second.
- `make scenarios` — scenario files driven against a **headless dedicated server**, all of
  them in **one server boot**. Each file is a list of server console commands, so any prefix
  can be pasted into a live server to investigate a failure. `make scenario FILE=...` runs
  one. Booting dominates the wall clock — 10.4s for the suite, of which an extra scenario
  costs about **0.3s** — so scenarios are cheap to add.

  They share a world, so each starts with `reset`, which removes every storage object on the
  level wherever it is. `clear` is not sufficient for this: it only covers a radius, while
  `expect total` scans the whole level.
- **In a session**, `/arcanestorage run session/roundtrip` executes a scenario file line by
  line as the player, covering open, withdraw, shift-click deposit and close. Player-coupled
  subcommands refuse to run from the console with an explanation rather than failing
  obscurely.

Both tiers use **one mechanism and one file format**: a line is a whole console command, so
the same file runs either way, vanilla commands can be mixed in, and any single line can be
pasted into chat or a server console to investigate a failure. The command supplies only
primitives — `place`, `fill`, `clear`, `break`, `give`, `open`, `close`, `withdraw`,
`deposit`, `click`, `report`, `expect` — and composition lives in the files.

`clear <radius> [tile]` strips objects around spawn so a scenario does not depend on terrain;
world generation puts roughly **2,900 objects** within 45 tiles of spawn, so this is not
theoretical. Vanilla's own `cleararea` is more thorough but targets a `ServerClient` and so
cannot run headless.

These call the methods the packet handlers call: a click is
`Container.applyContainerAction(slot, action)` per `PacketContainerAction`, and a withdraw
is `WithdrawAction.executePacket` per `PacketContainerCustomAction`, with the request
encoded exactly as the client encodes it. So what runs is the shipping path.

Automated headlessly: chains link through units; a diagonal neighbour is excluded; a
one-tile gap is not bridged; a multi-path block counts each unit exactly once; breaking a
unit mid-chain orphans what lies beyond; two terminals on one chain see the same network.

`tools/capture_server.sh` starts a server with a stocked network at spawn and logs every
inbound packet (`-Darcanestorage.packetlog`), which is how the packet vocabulary above was
established.

### Persistence needs two boots, and one gotcha

`make persistence` writes state in one boot and verifies it in a second after a restart, with
`--keep` reusing the saved world. All four cases are bundled into that single restart, since
the restart is the expensive part. The write phase also runs last inside `make scenarios`, so
the whole suite plus persistence costs exactly two boots.

**Reads do not load regions.** Regions load on demand, and the object layer resolves a tile
through `RegionBoundsExecutor` with `loadIfNotLoaded = false` — so an unloaded region reads as
empty rather than as itself. Normally only a player causes regions to load, and the harness has
no player. A freshly generated world hides this entirely because generation leaves every region
in memory; it appears only after a restart, where a scenario sees an empty world and looks
exactly like a persistence bug. The command now loads the regions a subcommand addresses before
touching them.

Consequence worth knowing: `expect total` scans **loaded** regions, so in a fresh boot it means
"everything in the areas the scenario has touched". Assert per-network numbers first, then
totals.

### In-game QA

Checks that need a live session are tracked in [QA_BACKLOG.md](QA_BACKLOG.md) rather than
inline here, so this file stays a statement of what the mod does and that one stays a queue of
what to look at next. Nothing in it is blocking.

### Design notes

A Storage Unit has no UI of its own to host a membership button, because a
terminal is the only way to interact with it. Membership is therefore placement:
put a unit against the network and it joins. That is arguably *more* discoverable
than a button, which matters for the discoverability requirement above.

Membership is a pure function of layout, recomputed each time a terminal opens, so
nothing is persisted and breaking a unit needs no cleanup. Persistence only
becomes necessary if linking stops being derivable from the world.

Several terminals on one network work, and needed no code: each terminal resolves
its own network independently, so they share it rather than competing for it.

**Ordinary chests do not join this way.** They join later through an import bus
(Phase 5), which keeps settlers using chests they already understand while the
network never has to expose itself to settler access.

**Done when:** a network survives a full game restart, and breaking it in the
obvious ways loses nothing. **Both are now verified** — `make persistence` restarts
the server and re-asserts every number, and orphaning beyond a broken unit or
conduit is asserted by the topology and conduit scenarios.

## Phase 3 — Usable at scale

Everything here is Priority 1 or the tier-1 usability set. Search is the
second-most-cited reason players install a storage mod at all, and an aggregated
view without it just reproduces the paginated-browser problem that existing
Necesse storage mods already have.

- [x] **Search** the aggregated view by item name, filtering live as you type
      *(code complete, awaiting in-game QA)* — uses the engine's own
      `ItemSearchTester`, so the syntax matches the crafting station and creative
      menu: `|` separates alternatives and `@` also searches tooltips. Purely
      client-side, since the client already holds every slot, so typing costs no
      packets
- [x] **Capacity feedback** — the footer reports slots used out of slots
      available, counted in slots because slots are what run out. Accounting is
      harness-verified (`tests/scenarios/capacity.txt`); the readout itself awaits
      in-game QA. The deposit half was **verified in game** (Aug 2026): a deposit
      that cannot fit fails visibly and leaves the items with the player
- [x] **Deposit-all** and **quick-stack** from the interface, against the whole
      network *(code complete, awaiting in-game QA — `session/transfers.txt`)*.
      Quick-stack and restock reuse the engine's `quickStackToInventories` and
      `restockFromInventories` with the network's units as targets, so no transfer
      logic is reimplemented. Both are redirected away from vanilla's
      proximity search, which would miss distant units and scoop up non-member chests
- [x] **Category filters** over the pooled list *(seen in game; the dropdown is present
      and the layout is right. Its nesting behaviour — Objects → Furniture, and selecting
      "All Furniture" — has not been deliberately exercised, so treat that part as unproven)*
      — partly delivered already, since `Item.matchesSearch` walks the category
      tree, so "sword" or "food" filtered by category before this. What was missing was
      picking a category without typing its name, and that is now a dropdown above the
      grid, built from the game's own tree at runtime.

      **It is a dropdown and not the row of icon buttons this kind of UI usually has,
      and that follows from using Necesse's taxonomy rather than Terraria's.** The
      running game has exactly eight top-level categories — `mobs`, `tiles`,
      `materials`, `consumable`, `objects`, `equipment`, `wiring`, `misc` — and 107 in
      total (verified through the harness with `query categories`, not inferred). Any
      fixed row of icons would have to invent buckets and then place real categories
      into them wrongly; the six icons in `art-submissions/2026-08-09-tiers-and-ui/ui/`
      are a Terraria set (all/tool/material/placeable/ammo/misc) with no icon for
      `consumable`, which is 59 items including all food, and an icon for `ammo`, which
      is a subcategory. Deriving the menu from the game instead costs no art, carries
      the game's own names in every language, nests the way the creative menu nests, and
      picks up any category a mod adds.

      Membership is tested by walking an item's category chain upward, so choosing a
      parent means "and everything beneath it" — the same rule `Item.matchesSearch`
      applies, so the picker and the search box agree about what a category contains.
      The comparison is by category **id**, not string ID, because the game reuses names
      at different depths: `storagebox` is `misc < furniture < objects`, so a
      string-ID comparison would put every chest under the top-level `misc`
- [x] **Sort** by semantic group, name, and quantity *(code complete, awaiting
      in-game QA)* — one cycling button. Group order is the engine's own:
      `InventoryItem` is `Comparable` and `Inventory.sortItems` just calls
      `Collections.sort`, so the network sorts the way the player's inventory-sort
      button already does. Not persisted, so it resets when the terminal reopens
- [x] Usable with a large network without stutter — **measured, not assumed**
      (`tests/scenarios/performance.txt`). Against the largest network the mod
      allows — 64 units, 2560 slots, 1910 distinct items, so effectively every
      stackable item in the game — one aggregation costs **0.34ms, about 2% of a
      60fps frame**. The scenario fails the build above 2ms, so this cannot
      silently regress. Two costs had already been removed by reading the code
      (aggregation was quadratic in distinct items, and the draw path aggregated
      three times a frame); the measurement is what closed the item. Still worth an
      in-game look, since a benchmark cannot see a stutter caused by drawing rather
      than counting

Search over items the player *possesses* is the genuinely novel piece: Necesse
has search in at least three places — station recipes, settler lists, the
creative menu — but always over a set of *candidates*, never over what you own.

Deposit-all, quick-stack, restock, loot-all and single-inventory sort are all
vanilla. The deliverable is making them work against the network, not rebuilding
them. Per-container item filters also already ship for settlement storage.

Usability is never gated behind progression. Gating scope is fair; gating search,
filtering or sorting reads as a broken mod.

**Done when:** finding one item among thousands takes one search box and no
scrolling, and a returning player can empty their inventory in one click.

## Phase 4 — Crafting from the network

**The mechanism already works, and this changes the shape of the phase.** Verified
headlessly (Aug 2026, `tests/python/test_crafting.py`): with eight logs in a unit and
nothing in the player's hands, a `woodboat` crafts and the logs are consumed from the
network — including through a 21-tile conduit run, so it is network membership doing the
work and not proximity. **No mod code implements this.** `Container.addSlot` adds every
slot's inventory to `craftInventories`, the terminal already has a slot per network slot,
and `applyCraftingAction` is defined on `Container` rather than on a crafting station and
consumes from `getCraftInventories()`. Global ingredients resolve too: the recipe asks for
`anylog` and the network answers with oak.

So this phase is an interface phase, not a mechanics phase, and the mod should keep
implementing no ingredient consumption of its own — that is exactly where a duplication bug
would live.

- [x] Recipes craftable from network contents, at any distance — *mechanism verified
      headless; no player-facing way to trigger it yet, which is the crafting tab below*
- [x] Crafting consumes ingredients from anywhere in the network — *verified headless,
      including a refusal that consumes nothing when materials are short*
- [x] Per-ingredient availability — have, missing, partial — judged against the
      whole network. **Vanilla already computes this**: `Container.canCraftRecipe` takes the
      inventory collection, and `FormContainerCraftingListContentBox` renders can-craft state
      per recipe from it. The work is showing that list in the terminal, not computing it
- [x] An explicit "you have 3 of 5, here is what is missing" display *(code complete,
      awaiting in-game QA)* — arrives with vanilla's recipe component rather than being
      drawn here
- [x] Recipe search against network-wide availability *(code complete, awaiting in-game
      QA)* — `FormContainerCraftingList` already
      carries search, so this arrives with the tab rather than as separate work
- [x] Station requirements satisfied by **installing the station into the terminal**
      — **final, Aug 2026.** Not to be reopened without a really good reason. Each
      bench has its own dedicated slot, the slots live on their own tab, and the
      recipes an installed bench holds appear once it is added. The terminal does
      **not** reach out to nearby benches: station access is something you commit a
      bench to, which makes the terminal's capabilities visible instead of depending
      on what happens to sit within an invisible radius, and gives that access a real
      cost. Deliberately *not* vanilla's proximity rule
- [x] **A recipe selector by source bench** *(code complete, awaiting in-game QA)* —
      keyed on `Tech` rather than on the bench item, because that is what a recipe carries,
      and it makes tiering read correctly: a Demonic Workstation installs two techs and so
      offers two entries
- [x] **A show-all-recipes toggle**, so the list can be everything or only what the
      network can currently build. Uses vanilla's `filteronlycraftable` *label* but its own
      transient state: an earlier version shared vanilla's `craftingListOnlyCraftable`
      setting so the choice would carry between a bench and the terminal, and that was
      reversed on Elias's instruction (Aug 2026). Two reasons, both good: the storage tab
      already forgets its search and category on close, so a crafting tab that remembered
      them would be inconsistent inside one interface; and sharing meant the terminal wrote
      a preference belonging to benches. **Defaults to show-all**, unlike Magic Storage,
      because a list narrowed to what the network can build is indistinguishable from a tab
      that does not work yet — which is exactly what a player sees before their first bench

**The crafting pool is the network and nothing else.** No nearby chests, ever. Vanilla's
"use nearby inventories" would undermine the entire point of a self-contained system, and
this is free to guarantee rather than something to enforce: nearby pickup is an override of
`getCraftInventories()` on `CraftingStationContainer` and `UpgradeStationContainer`, not
behaviour on `Container`, so a terminal that does not override it has the network as its
only source by construction.

**One terminal, not two.** Magic Storage separates storage from its Storage Crafting
Interface; both live in one block here, as tabs. A deliberate divergence: the split exists
in Magic Storage for its own historical reasons, and two blocks would mean two placements,
two recipes and two things to explain for no gain the player can feel.

**What "reuse the vanilla logic" resolves to**, all verified in the source Aug 2026, so this
needs no research when it starts:

- `CraftingStationObject` (26 objects) declares `Tech[] getCraftingTechs()`, and
  `ObjectItem.getObject()` gets there from the item — so a slot accepts a bench by asking
  the item what techs its object offers. No table of benches to hand-maintain.
- Recipe registration is vanilla's own one-liner from `CraftingStationContainer`:
  `Recipes.streamRecipes().filter(r -> techs.anyMatch(r::matchTech)).forEach(this::addRecipe)`.
- `Tech` carries `itemStringID` and a localized `displayName`, which is both the label for
  the source-bench selector and the sprite for a slot's faded placeholder icon.
- Tiering comes free: an upgraded bench returns several techs — a Demonic Workstation covers
  `WORKSTATION` as well as `DEMONIC` — so installing the upgrade supersedes the base bench
  without a line of tier logic here.
- `FormContainerCraftingListContentBox` is abstract with one method to supply,
  `streamAllRecipes()`, and computes per-recipe can-craft state against the container's craft
  inventories itself.

**How the index constraint was resolved, Aug 2026.** Recipe IDs are indices into the
container's own list and `applyCraftingAction` resolves them by index on both sides. Vanilla's
own answer to a recipe list that changes under an open container is to **close** it —
`CraftingStationContainer` closes after a station upgrade — which would have thrown the player
out of the interface for installing a bench.

Instead the container registers **every** recipe in the game once, so the list is fixed for the
container's lifetime and installing a bench cannot desync anything. What changes is which
recipes the tab *shows*, and `applyCraftingAction` is overridden to refuse recipes whose station
is not installed, checked against the server's own copy of the station slots. Two supporting
facts made this safe: the player's inventory crafting panel streams only
`RecipeTechRegistry.NONE` (`MainGameFormManager:722`), so the extra registrations cannot leak
into it; and the terminal's own inventory is dropped from the craft pool, because
`Container.addSlot` adds every slot's inventory to it and an installed bench must not be a
material. Verified headlessly: `tests/python/test_stations.py`, 5 tests including a refused
station recipe that consumes nothing, and three kinds of non-station being refused installation

**Be honest about what is new here.** Necesse already lets a crafting station
consume from nearby containers — it is a player-facing per-station checkbox with a
range of roughly ±5 to ±9 tiles depending on the station. So the hard conceptual
part is vanilla. What is missing is unlimited scope, an explicit membership set,
and any view of what the pool contains. Recipe search, categories and an
"only craftable" filter also already exist per station; extending them to a
network-wide pool is the deliverable, and presenting either as novel would be
false.

The missing-ingredient display is the part Terraria players install *additional*
mods to get, so it carries more weight than its size suggests.

One consequence worth checking in game: the terminal's form sets
`shouldOpenInventory()`, so the player's inventory panel — which carries vanilla's own
crafting list for the *open container's* recipes — is on screen while the terminal is open.
If that list is already live, hand-craftable recipes may be craftable from the network today
with no crafting tab at all. Unverified visually; it would be a pleasant surprise rather than
a substitute for the tab, since it cannot show station recipes.

**Done when:** an item can be crafted from materials spread across several units
without opening any of them, and the interface says plainly why a recipe is not
currently craftable.

## Phase 5 — Transfer rules and buses

Where the mod stops being a port. One primitive does most of the work.

- [ ] **Condition-based transfer rules** — a rule is a threshold, not just an item
      filter: move X when more than N, accept X only when fewer than N, never
      drain below N
- [ ] **Import bus** — attaches to an ordinary container and moves its contents
      into the network
- [ ] **Export bus** — pushes items out of the network into a target container on
      a rule

The rule primitive is deliberately introduced once and reused. Overflow control,
defence against settler overproduction, and reserve floors are the same idea read
three ways, and the player should only have to learn it once.

The import bus is the answer to "can ordinary chests join". It is indirection with
a purpose: settlers keep depositing into chests they already understand, and
nothing in the network is ever exposed to settler access. The natural export
target is a Shipping Chest, which already sells its contents through trader
missions above a stack threshold — so selling needs no new machinery.

These rules should compose with vanilla's own priority-driven hauling rather than
standing up a parallel system. Necesse already ships an 8-level storage priority
scale, and haulers already move items between containers according to it.

**Done when:** overflow of a chosen item reaches a Shipping Chest and is sold,
with a reserve floor respected, and a settler depositing into a bussed chest shows
up in the terminal without gaining any access to the network.

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

- [ ] Recipes for the mod's own objects, tiered to Necesse's progression
- [ ] Capacity growth per tier, plus one real mechanic per tier
- [ ] A non-spoilage perk on every unit tier that offers one
- [ ] Localization complete
- [ ] Textures for all objects and UI elements

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
