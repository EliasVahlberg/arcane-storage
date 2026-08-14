# Roadmap -- completed

Phases 0 through 5c, done and verified. Split out of `ROADMAP.md` on 14 August 2026, because a
roadmap whose first six hundred lines are finished work stops being a plan and becomes an archive
that has to be scrolled past.

**Acceptance criteria are kept with their phase rather than summarised**, which is why this file is
long. They record what "done" was allowed to mean at the time, and several were tightened after a
first attempt satisfied a criterion while the feature was still wrong -- so they are evidence about
the mod's behaviour rather than ceremony. The design notes and the rejected alternatives are kept for
the same reason: the useful part of a finished phase is usually why it ended up shaped the way it is.

What is deliberately *not* here is anything unfinished. One item accepted during Phase 4 -- moving
station slots onto their own Station Unit -- is still open and lives in `ROADMAP.md` instead, because
an archive with an open task in it is the drift this split exists to stop.

Current work, the deferred list, the non-goals and the decisions still to make are all in
[`ROADMAP.md`](ROADMAP.md).

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

Two commands, and the second one owns almost everything.

- `make test` — unit tests over game-independent logic (the network traversal). Sub-second.
- `make pytest` — the whole automated suite, driven against a **headless dedicated server** over a
  JSON-per-line reply channel. 53 tests, ~46s, dominated by two JVM boots: one for the session and
  one more for the persistence test, since a restart is the only way to prove anything survives a
  save. Everything else is milliseconds.

  Tests share a world, so the `storage` fixture starts with `reset`, which removes every storage
  object on the level wherever it is. `clear` is not sufficient: it covers a radius around spawn,
  while a level-wide total scans everything.

There was a second suite of bash-driven scenario files until Aug 2026. It is gone, and
[TESTING.md](TESTING.md) records why the arguments for keeping it did not hold. What survived is the
**file format**, because it is the one thing pytest cannot do: a line is a whole console command, so a
file can be run inside a live game with `harness run <name>` (needs `make run HARNESS=1`) or headlessly
with `make scene FILE=...`. `tests/scenes/` uses that to build states worth looking at — a full
64-unit network, or a network at one quarter capacity — which turns two in-game QA items from minutes
of clicking into one line each. Those are scenes, not tests; no assertion lives there.

The verbs supply only primitives — `place`, `fill`, `clear`, `break`, `give`, `open`, `close`,
`withdraw`, `deposit`, `depositcursor`, `install`, `click`, `report`, `bench`, plus queries — and
composition lives in the tests.

`clear <radius> [tile]` strips objects around spawn so a test does not depend on terrain; world
generation puts roughly **2,900 objects** within 45 tiles of spawn, so this is not theoretical.
Vanilla's own `cleararea` is more thorough but targets a `ServerClient` and so cannot run headless.

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

### Persistence needs a restart, and there is one gotcha

`tests/python/test_persistence.py` builds four networks, calls `harness.restart()` — which stops the
server cleanly, so the world is saved, and boots it again on the same world — and asserts every number
came back. All four cases share the one restart because the boot is the expensive part.

**Reads do not load regions.** Regions load on demand, and the object layer resolves a tile
through `RegionBoundsExecutor` with `loadIfNotLoaded = false` — so an unloaded region reads as
empty rather than as itself. Normally only a player causes regions to load. A freshly generated world
hides this entirely because generation leaves every region in memory; it appears only after a restart,
where a test sees an empty world and looks exactly like a persistence bug. The verbs now load the
regions they address before touching them.

Consequence worth knowing: a level-wide total scans **loaded** regions, so in a fresh boot it means
"everything in the areas the test has touched". Assert per-network numbers first, then totals.

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
obvious ways loses nothing. **Both are now verified** — `test_persistence.py` restarts
the server and re-asserts every number, and orphaning beyond a broken unit or
conduit is asserted by `tests/python/test_topology.py` and `test_conduits.py`.

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
      harness-verified (`tests/python/test_network.py`); the readout itself awaits
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
      (`tests/python/test_transfers.py`). Against the largest network the mod
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
- [x] **A source filter, one tickbox per bench, all on by default** — *confirmed working
      in game Aug 2026.* Replaced a dropdown, which was both the wrong control and broken:
      it read its selection when building the list and never rebuilt on change, so it could
      only have worked by accident. Elias's design is better independent of the bug, because
      a dropdown is single-select and cannot say "show me these two benches". Keyed on
      `Tech` rather than on the bench item, since that is what a recipe carries, which also
      makes tiering read correctly — a Demonic Workstation contributes two boxes. Hand
      recipes get a box like any other, labelled with the game's own name for that tech
- [x] **Collapsible category sections, as at a vanilla bench, with a flat-grid option** —
      *default grouped; the toggle persists, confirmed in game.* Vanilla's categorised view
      lives in `CraftingStationContainerForm`'s `protected` inner classes and cannot be
      reused, but it did not need to be: overriding `updateList()` alone keeps
      `FormContainerRecipe` — can-craft state, the have/missing tooltip, the "3 of 5" count,
      click-to-craft — built exactly as the base class builds it, so only positions differ.
      The toggle lives in the engine's own `ModSettings`, returned from `initSettings()`, so
      it persists without this mod inventing a settings file. Section expansion is
      session-scoped, matching vanilla, whose expansion map is never written to disk
- [x] **Fueled stations are refused installation** — a hole in the first version, closed the
      same day with tests. `FueledCraftingStationObject extends CraftingStationObject`, so a
      Forge was installable, but fuel is enforced in
      `FueledCraftingStationContainer.applyCraftingAction` — behaviour of the container, not
      the object — so an installed Forge smelted for free. See the production-station review
      under Phase 5 for where these belong instead
- [x] ~~**Station slots move off the terminal onto their own unit**~~ -- **still open, and
      moved to the current roadmap.** Phase 4 is otherwise complete; this item was accepted
      during it and outlived it.

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

- [x] **Condition-based transfer rules** — *done Aug 2026, using the game's own filter.* A rule is an
      entry in vanilla's `ItemCategoriesFilter`: an item or category is ticked or not, and a number says
      how much of it the network should hold. The three readings the roadmap asked for are that one
      control seen from two sides — an import bus fills up to the number, an export bus drains down to
      it, and no number means move as much as possible
- [x] **Import bus** — *done Aug 2026.* Attaches to any neighbouring container and moves its contents
      into the network. With no rules it moves everything, because importing only adds
- [x] **Export bus** — *done Aug 2026.* Pushes items out of the network into a neighbouring container on
      a rule, and is **inert until it has one** — the opposite default to the import bus, because
      exporting removes from storage and a bus that emptied the network on placement would be a trap
- [x] **A rule interface** — *done Aug 2026, and it is the game's.* Right-clicking a bus opens
      `ItemCategoriesFilterForm`, the same panel as "configure storage" on a settlement chest: category
      tree, tri-state ticks, per-item numbers, search, allow-all and clear-all. Only the two header lines
      are ours. **Partly verified in game, and currently broken: issue #1.** It opens and its edits reach
      the server, but the client seems to begin from an empty filter, so editing can wipe what was ticked.
      Fixed (the filter was wrapped twice for the open packet) and confirmed in game: filters, amounts
      and persistence all work. Limits were a second, separate defect - only the two "each item" modes were
      honoured while the panel's default is `TOTAL_ITEMS` - and now all four modes plus category limits apply
      to the network, verified headlessly and pending an in-game check. See QA_BACKLOG item 18

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

**Where that stands:** the transfer half is verified headlessly in `tests/python/test_buses.py` — a
chosen item's surplus leaves the network into an ordinary container with the floor held across units
rather than per unit, and a chest's contents arrive in the network without the chest joining it. Two
parts are not verified: that a Shipping Chest then *sells* what it receives, which is vanilla's
behaviour and needs a trader mission in a real world, and that a settler depositing into the bussed
chest behaves as expected, which needs a settlement. Both are in-game QA rather than code.

**The primitive already existed, and that is the lesson of this phase.** The rules were first written from
scratch as a `TransferRule` of three fields, with a plan to build an editor for it. Both were unnecessary:
`necesse/inventory/itemFilter/ItemCategoriesFilter` already carries per-item and per-category limits, four
limit modes, tri-state inheritance, save data, `writePacket`/`readPacket` and `copy()`, and
`ItemCategoriesFilterForm` is an 851-line editor for it that the player has already learned. The
hand-written version was deleted the next day.

The search that missed it looked for *rule* and *threshold*. The game's word is *filter*. This is the
settlement-storage lesson from `STORAGE_AND_INVENTORY.md` repeating exactly: **search the codebase with
its own vocabulary, and check the game has no answer before writing one.**

What is genuinely ours is the arithmetic across units. `getAddAmount` and `getRemoveAmount` take an
`InventoryRange`, which is a range within one inventory, and a network is many — evaluating the filter per
unit would silently turn "the network keeps 200" into "each unit keeps 200". The numbers come from the
filter; only the summing is ours.

**Design notes worth keeping.** Two decisions here were wrong first and are recorded because the
reasoning matters more than the outcome:

- **A bus conducts.** The first version made a bus a plain node like the terminal, on the stated
  reasoning that a chest should not bridge two networks — which was empty, because a chest is not a
  conductor and never could. What it actually did was sever a run of units wherever a bus was placed. A
  test caught it. Everything this mod places conducts except the terminal, which is a window rather than
  infrastructure.
- **Both sides of a transfer are lists of inventories.** The tempting alternative was an `Inventory`
  subclass spanning the network, which would have been a subtle liar: `Inventory` carries dirty
  tracking, filters and locked slots, and a view reimplementing some of that would be wrong in ways
  nothing would notice for a while.

- **A bus was not right-clickable.** `GameObject.canInteract` returns false by default and only
  `InventoryObject` overrides it, so the panel was unreachable and the interact tip was dead code. The
  headless panel test caught it; a play session would have caught it too, but slower and with less
  certainty about the cause.

Items are added first and removed by exactly what the destination accepted, so a full destination is a
no-op rather than a hole — the same ordering the cursor deposit uses, and for the same reason.

### Production stations — reviewed Aug 2026, deferred deliberately

Elias's proposal: furnaces, grain mills and the like reachable from the terminal, with
auto-refuelling that takes "just enough" fuel, and a production request queue (click adds
one, shift adds all possible, ctrl adds ten) displayed per station type. He asked whether it
belongs in the base mod or an addon, and floated a "production connector" to placed stations
as possibly cleaner. **It is cleaner, and the sequencing matters more than the split.**

Three findings from the source drive the recommendation, and the first contradicts a premise
of the proposal.

**Fuel is time-based, not per-item.** `FueledInventoryObjectEntity.isFueled()` is literally
`fuelBurnTime > 0`; `useFuel()` consumes one fuel item and buys a *burn duration*, and
`serverTick` re-lights only while `alwaysOn || keepRunning`. A player's craft at a Forge is
gated on "is it lit right now" and consumes no fuel of its own. So smelting one bar cannot
cost 5k wood in vanilla, and metering fuel per item is not a problem that exists. The real
goal restates as **keep it lit while a job runs and burn nothing idle** — which vanilla
already expresses through the `keepRunning` flag its fueled container exposes as a toggle.

**Player crafting at a fueled station has no crafting time.** Progress over time lives in a
different family — `FueledProcessingInventoryObjectEntity`, `ProcessingForgeObject`, and the
settler job path. So a queue with visible progress is either a new mechanic invented for
player crafting, or it is surfacing the processing family, which already has fuel, progress,
an input inventory and a server tick. Building on that family is the difference between
composing and simulating.

**Which means most of the proposal is Phase 5, not a new subsystem.** Feeding a placed
processing object from the network and pulling its output back is an import rule and an export
rule aimed at that object. If the transfer-rule primitive is built well, a production
connector is thin on top of it plus a queue UI; if it is built badly, an addon would paper
over that instead of fixing it. Sequencing: Phase 5 first, then reassess — there may be no
addon-sized problem left.

**Why not install production stations like benches.** Fuel is enforced in
`FueledCraftingStationContainer.applyCraftingAction`, behaviour of the *container* rather than
the object, so a terminal that installs a Forge inherits its techs and none of its fuel. That
was a live hole in the first version of station installation, closed the same day with tests.
The design line worth keeping from it: **install what is stateless, connect what is
stateful.** A workbench is a permission. A furnace is a machine with fuel, progress and
settler access, and it should stay in the world where all of that already works.

**Tinting production recipes is feasible and derivable** rather than hand-maintained: the
grouped crafting view constructs its own `FormContainerRecipe` components, so a subclass can
tint, and the rule is "this recipe's tech comes from a fueled station". Worth knowing that
vanilla has no formal notion of a production recipe — the distinction would be ours, which is
an argument for keeping it purely visual.

**Base mod or addon.** Not the base mod yet, and the reason is sequencing rather than size.
One thing the base mod should do whenever convenient, at close to zero cost: make network
membership an extensible notion, so a new member type can be added without patching
`UnitNetwork`. That keeps the door open for an `Arcane Productions` addon — or for Phase 6 to
walk through it itself.

**The queue's click grammar is worth keeping regardless of where production lands.** One,
all-possible and ten on plain, shift and ctrl is the same vocabulary the storage grid now
uses for withdrawal, and consistency across tabs is cheap here because both are ours.

## Phase 5b — Transfer resolver

Specified in `docs/TRANSFER_RESOLVER.md`. **Built, in five commits, and verified headlessly. The three
`xfail(strict=True)` tests it was measured against — convergence, idle cost, throughput — have all flipped to
passing and their markers are gone.** What remains is in-game verification of everything a player sees, listed
under "Needs a person" below.

Phase 5's transfer loop worked item by item, device by device, and could not be repaired incrementally. Elias hit
it in play as "weird lockups"; measurement found three faults and one engine constraint:

- Rules that disagree churn forever — 12 moves in 120 ticks with the network total reading a steady 20, so it
  presents as a lockup rather than as a runaway.
- A device that knows only its own rule **cannot** detect that. Detection has to see every constraint on an
  item before anything moves, which is an argument about structure, not about performance.
- Idle cost is 5 network flood fills and 200 slot scans per bus per 100 ticks, whether or not anything
  happened.
- `Inventory` notifies only its **first** slot-update listener, so subscribing to containers we do not own is
  not available. The change hook has to be a patch on `Inventory.updateSlot(int)`.

The design is a per-network index as the single copy of derived state, rules as constraints on
`(network, item)` rather than instructions to a device, a resolver emitting changesets for dirty pairs only,
and a queue drained under a per-network budget so throughput is policy rather than correctness. Contradictions
are rejected when written — an Apply button makes the panel transactional — and a bus that finds itself in an
unsatisfiable configuration fails closed with a reason naming the other device. Breaking hardware is reserved
for a future throughput constraint, where it is physically motivated.

### What was built

- [x] **Validation and device states.** Per `(network, item)`, the highest import ceiling against the lowest
      export floor, flagged only when the two buses share a container — because import from one chest with
      export to another is the same inequality and terminates, so flagging it would break a useful layout. A
      stopped device is drawn in grey, says why on hover and in its panel, and announces itself once in chat.
      The terminal names the tiles of every stopped device on its network.
- [x] **The per-network index.** One shared copy of item counts and member inventories, named by the network's
      lowest-ordered member tile so every device arrives at the same name from its own walk. Replaced counting
      that was quadratic in slots and paid once per device.
- [x] **Incremental maintenance.** A patch on `Inventory.updateSlot(int)`, with a per-slot shadow so a change
      can be applied as a difference rather than a rescan. Proved at load rather than assumed, and it degrades
      to timed recounting with a warning if a game update ever stops it applying. A periodic drift check and a
      resync when a terminal opens, because this is a cache of state the mod does not own.
- [x] **The scheduler.** One per network, led by the lowest-ordered device, holding the set of items something
      has disturbed. Eight moves per network per tick is the whole of the rate policy. A churn backstop stops
      an item that keeps moving without the network getting closer to its rules, which catches loops closed
      outside the network — a settler, a hopper, another mod — and is retried after at most thirty seconds.
- [x] **The Apply button.** Edits are local, the whole set is judged before any of it is adopted, and a refusal
      names the item and the other device. Nothing is partially applied.

### Measured, before and after

| | Before | After |
|---|---|---|
| Idle network, 100 ticks | 5 walks, 200 slot scans per bus | 0 walks, 0 rebuilds, 0 scans |
| Chest of 8 kinds | 3 kinds still there after 5 s | empty within a tick or two |
| Contradictory rules | 12 moves per 120 ticks, forever | refused when written |
| Loop closed by an outside agent | never detected | stopped after 40 moves |

### Phase 5c — the logistics tab

`[built Aug 2026]` The stopped-device notice was first a single line in the storage tab's category row, and it
worked in the sense that it appeared. It was a poor notice: the row's height is fixed, so the line could say how
many devices had stopped and where, and nothing about why -- a player who saw it still had to walk to each device.

The terminal now has a fourth tab that does two jobs in one place. A red-backed panel names every stopped device
with its reason, and the same list of devices is how their rules are set, so the fix is where the diagnosis is.

- [x] Per-bus summaries in the terminal's content packet, carrying the parts of a reason rather than a sentence,
      so nothing is worded twice and no server sends its own language to a client.
- [x] The rule editor as one shared component, built into whatever form asks for one. Both the bus's panel and
      the tab use it, so neither can drift from the other.
- [x] Rules written from the terminal go through the same validation as rules written at the bus. There must be
      no way to reach a contradictory configuration by choosing the more convenient of two interfaces.
- [x] A membership check on every rule action: the tab addresses buses by coordinate, so the terminal confirms
      the bus is on its own network before writing anything.
- [x] Filters fetched per device on selection rather than sent for every bus when the terminal opens.

One shape decision is a departure from what was asked for and is recorded in `docs/QA_BACKLOG.md`: the list shows
one device's rules at a time rather than a column of expanding rows, because the editor contains a scrolling
category tree and nesting those inside another scrolling list invites the same hit-testing fault that made the
Apply button inert.

A larger idea from the same conversation -- logistics as its own terminal, required before a bus can be placed --
is a balancing question rather than a technical one and is written up in the backlog rather than decided here.

### Needs a person

**Confirmed in game 14 Aug 2026 — Phase 5c's visual half is done.** The ten-step resolver script passes, and
so does the reworked interface: the grey stopped sprite reads as stopped, the chat line's wording works, the
terminal's red banner is right, and the whole thing feels prompt now that work follows changes rather than a
timer. The panel now reads name-status-amount-filter with a status line that takes no space until there is
something wrong and grows the window when there is; stopped devices appear as flow-wrapped name boxes with
their reason on hover and a Copy button for the set; and the terminal's rules pane scrolls as a whole.

**One art problem came out of it.** A stopped import bus and a stopped export bus are nearly the same
picture: their outlines are pixel-identical and desaturation removes the green/amber split that was carrying
direction. Requested as a silhouette change in `docs/SPRITES.md`; the sprites are otherwise fine.

Sprites are luminance desaturations of the delivered bus art, so they no longer need regenerating for that
reason — but they will if the silhouette request above is drawn.

This lands before Phase 6, because every later device inherits the model — and D25's wireless silos and the
crafting queue both sit on top of it.
