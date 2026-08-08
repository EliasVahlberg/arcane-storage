# Roadmap

Ordered so that every phase ends with something playable. Each phase lists what
"done" means, because "storage works" is not a testable claim.

Necesse already provides more than Terraria does here, so parts of Magic
Storage's feature list are redundant and are deliberately not reimplemented —
noted where relevant.

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
`INV_QUICK_MOVE` transfers to the inventory.

Two honest caveats, neither covered by the criteria above:

- **Deposit preference is the engine's, not ours.** Step 4's original wording
  wanted deposit to prefer units already holding the item. Deposit routes through
  `addInventoryQuickTransfer`, so whatever preference exists is
  `transferFromSlots`' own stacking order. It has not been isolated and tested,
  and no code here implements it.
- **Membership is still adjacency scaffolding** — orthogonal neighbours,
  recomputed per open. Phase 2 owns real topology.

## Phase 2 — Network membership and persistence

- [ ] Units join a network by topology rather than by a per-container button —
      adjacency, extended by a connector object for range
- [ ] Membership survives save/load and level changes
- [ ] Removing a terminal or a linked unit degrades gracefully rather than
      orphaning items
- [ ] A unit can belong to only one network
- [ ] Sensible bound on how far a network may reach

Superseding the earlier plan to reuse Necesse's "Add Inventory to Settlement
Storage" gesture: a Storage Unit has no UI of its own to host that button, since
the terminal is the only way to interact with it. This follows Magic Storage's
adjacency-plus-connector model instead. See the correction in `MOD_BRIEF.md`.

**Open:** whether ordinary chests may also join, via the button idiom in their own
UI. Additive if so, and not a Phase 1 dependency.

**Done when:** a network survives a full game restart, and breaking it in the
obvious ways loses nothing.

## Phase 3 — Search, sort, filter

- [ ] Text search filtering the aggregated view as you type
- [ ] Sort by name, quantity, and category
- [ ] Category filters, reusing the game's existing item-category filters
- [ ] Usable with a large network without stutter

Per-container item filters already exist in Necesse for settlement storage and
are not rebuilt.

**Done when:** finding one item among thousands takes one search box and no
scrolling.

## Phase 4 — Unified crafting

- [ ] Crafting tab listing recipes craftable from network contents
- [ ] Recipe search by output name
- [ ] Crafting consumes ingredients from anywhere in the network
- [ ] Recipes requiring a crafting station work when that station is connected
- [ ] Clear display of what a recipe needs and what is missing

Necesse's crafting already pulls from settlement storage for settlers, and
`Container` has a native mechanism for crafting from non-player inventories, so
this extends existing machinery rather than replacing it.

**Done when:** an item can be crafted from materials spread across several
chests without touching any of them.

## Phase 5 — Bulk transfer

- [ ] Deposit-all from player inventory
- [ ] Quick-stack — deposit only items the network already holds
- [ ] Restock — refill the player's stacks from the network

Necesse already treats sort, quick-stack, and restock as engine concepts with
dedicated slots, so these hook into existing behaviour.

**Done when:** a full inventory can be emptied into storage in one click.

## Phase 6 — Progression and balance

- [ ] Recipes for the mod's own objects, tiered to fit Necesse's progression
- [ ] Capacity or range that scales with tier, so the network is a goal rather
      than a switch
- [ ] Localization file complete
- [ ] Textures for all added objects and UI elements

**Done when:** the mod is obtainable through normal play and does not trivialise
the early game.

## Phase 7 — Multiplayer and release

- [ ] Verified on a dedicated server with more than one client
- [ ] Concurrent access from two players does not desync or duplicate
- [ ] Optional bridge exposing settlement storage through the terminal
- [ ] Workshop preview image and description
- [ ] Published

**Done when:** it works on a real server, not just in singleplayer.

## Later — possible extensions

Inspired by Applied Energistics 2, listed to show where this could go. None are
commitments and none are started.

- **Import/export conduits** — pull from or push into a container automatically,
  the basis of sorting and processing automation
- **Wireless terminal** — access the network from a distance, with tiered range
- **Autocrafting** — request an item and let the system resolve and queue the
  intermediate crafts
- **Storage cells** — capacity as an item rather than a placed container
- **Settler integration** — let settlers use the network as a work source, which
  would tie the mod into Necesse's automation rather than sitting beside it
