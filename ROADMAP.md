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
      terminal *(code complete, **awaiting in-game QA** — see below)*
- [ ] Extended by a connector object for range
- [ ] Membership survives save/load and level changes
- [ ] Removing a terminal or a linked unit degrades gracefully rather than
      orphaning items
- [ ] A unit belongs to only one network
- [ ] A sensible bound on how far a network may reach
- [ ] Several terminals may access one network

### ⚠ Pending QA — run this first next session

Connectivity-based linking is committed and unit-tested but **has never been run
in the game**. 11 tests cover the traversal (`make test`, needs no game), so what
is unverified is specifically the parts that touch `Level` and `ObjectEntity`.

1. A chain of 5+ units running away from the terminal, only the first touching it.
   All should aggregate — a different item in each makes it readable.
2. Break a middle unit, reopen: far units drop out, near ones stay, nothing lost.
3. **An L-shape or a solid block**, so more than one path exists to some unit.
   Counts must be exact — a doubled stack means the visited set is failing in the
   real game, which is the duplication path and stops everything else.
4. A diagonally-touching unit — must be excluded.
5. A unit one tile away with a gap — must be excluded.
6. Two terminals on one chain: same contents, and a withdrawal at one is reflected
   at the other.

Also still open from Phase 1 QA: the per-unit `slots used` readout appeared not to
change when a second non-stackable item entered the network. Most likely the item
landed in the *other* unit and the readout is per-unit by design — clicking both
units and comparing against the terminal's total distinguishes that from a real
bug.

A Storage Unit has no UI of its own to host a membership button, because a
terminal is the only way to interact with it. Membership is therefore placement:
put a unit against the network and it joins. That is arguably *more* discoverable
than a button, which matters for the discoverability requirement above.

Membership is a pure function of layout, recomputed each time a terminal opens, so
nothing is persisted and breaking a unit needs no cleanup. Persistence only
becomes necessary if linking stops being derivable from the world.

Multiple terminals may already work, since each terminal resolves its own network
independently — verify before building anything for it.

**Ordinary chests do not join this way.** They join later through an import bus
(Phase 5), which keeps settlers using chests they already understand while the
network never has to expose itself to settler access.

**Done when:** a network survives a full game restart, and breaking it in the
obvious ways loses nothing.

## Phase 3 — Usable at scale

Everything here is Priority 1 or the tier-1 usability set. Search is the
second-most-cited reason players install a storage mod at all, and an aggregated
view without it just reproduces the paginated-browser problem that existing
Necesse storage mods already have.

- [ ] **Search** the aggregated view by item name, filtering live as you type
- [ ] **Capacity feedback** — how full the network is, and a visible failure when
      a deposit cannot fit, rather than items silently vanishing
- [ ] **Deposit-all** and **quick-stack** from the interface, against the whole
      network
- [ ] **Category filters** over the pooled list
- [ ] **Sort** by semantic group, name, and quantity
- [ ] Usable with a large network without stutter

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

- [ ] Recipes craftable from network contents, at any distance
- [ ] Crafting consumes ingredients from anywhere in the network
- [ ] Per-ingredient availability — have, missing, partial — judged against the
      whole network
- [ ] An explicit "you have 3 of 5, here is what is missing" display
- [ ] Recipe search against network-wide availability
- [ ] Station requirements satisfied under a documented rule (**decision
      required**, see below)

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

- **Crafting stations: items in slots, or placed stations?** Magic Storage puts
  station *items* into interface slots and ignores the world. Necesse does the
  opposite: placed stations reach out ±5–9 tiles. The earlier preference for the
  Magic Storage model was inherited from Magic Storage rather than chosen, and it
  is now confirmed to be a real divergence from Necesse's own idiom. Both are
  defensible; the tension is the point. Blocks the last item of Phase 4.
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
