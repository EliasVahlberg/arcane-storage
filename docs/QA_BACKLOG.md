# QA backlog — checks that need a live session

Everything here needs a running game with a player, because `Container` is built from the
player's inventory and cannot exist with nobody connected. Nothing here is blocking; it is a
queue, ordered roughly by what would hurt most if broken.

Automated coverage lives elsewhere and should not be duplicated here: `make test` for the
traversal, `make scenarios` for topology, aggregation, conduits and persistence across a
restart. Add an item here only when the harness genuinely cannot reach it — a client-only code
path, a rendering result, a real second player, or a human judgement about whether something
*looks* right.

**How to run most of these.** Start a session, then either singleplayer or
`tools/capture_server.sh` (which pre-places a stocked network at spawn and prints join
details). Scenario files can be executed from chat:

```
/arcanestorage run session/roundtrip
```

Individual primitives are available too — `open`, `close`, `withdraw`, `deposit`,
`click <slot> <action>`, `give`, `report`, `expect`, `place`, `fill`, `clear`, `reset`.

When something passes, tick it and note the date. When something fails, keep it here with what
you saw — a failure that gets fixed should end up with a scenario or unit test that would have
caught it, and that is worth recording.

## Rendering and client-only code

The headless server never loads textures and never runs draw code, so this whole class is
invisible to the harness. It is also the quickest to check by eye.

Real sprites are now installed, so all four of these are live rather than waiting on art.

- [ ] **All three objects render.** Terminal, unit and conduit in the world, plus their
      inventory icons. None of this draw code has ever executed.
- [ ] **The terminal stands two tiles tall** and its foot sits on its own tile rather than
      floating, since the sprite is 32×64 and bottom-anchored.
- [ ] **Conduits draw the shape their neighbours call for.** With the current four-frame sheet
      only straights exist, so a run that turns still shows two straights meeting — that is
      expected until the 16-frame sheet requested in SPRITES.md arrives. What to check now is
      that straight runs join with no visible seam, and that the sprite switches axis as you
      face up/down versus left/right. The mask convention behind the eventual elbows and tees is
      already asserted headlessly in `tests/scenarios/conduits.txt`.
- [ ] **The terminal screen lights up while open** and goes dark when closed
      (`arcanestorageterminal_open.png`, swapped by `isInUse()`).
- [ ] **A run of units reads as one wall**, with the intended notches where four corners meet
      rather than looking like a mistake.

## Phase 3 interface — new, none of it exercised yet

The whole of Phase 3 so far is client-side or player-coupled, so the harness reaches only the
numbers behind it. `tests/scenarios/capacity.txt` covers the capacity accounting headlessly;
everything below needs eyes or a player.

- [ ] **Search filters as you type.** Type part of an item name and confirm the grid narrows
      live. Then try the engine's syntax, which we inherit rather than invent: `iron|stone`
      matches either, and `@` before a term searches tooltips too. Category names work as
      queries as well — "sword", "food" — because `Item.matchesSearch` walks the category tree.
- [ ] **Search does not break withdrawal.** Filter, then click an item and confirm the right
      one arrives. A withdrawal names an item and an amount rather than a slot index, and the
      server re-resolves it, so a filtered view should not be able to misdirect a click — worth
      confirming once because it is the failure this design is supposed to make impossible.
- [ ] **Capacity readout is correct and updates.** Compare "N / M slots used" against what you
      know is stored, then deposit and withdraw and confirm it moves.
- [x] ~~**Quick stack, deposit all and restock**, including the quick-stack versus deposit-all
      distinction and conservation across every transfer.~~ Asserted headlessly by
      `container_transfers`, 19 assertions.
- [ ] **Deposit all leaves locked slots alone.** Not covered: the scenario has no locked slots,
      because locking is a client-side interaction and nothing headless can set one yet.
- [ ] **The three buttons are present, labelled and clickable.** The behaviour behind them is
      asserted; that they are drawn at all is not.
- [ ] **Sort cycles and the tooltip says which mode is active.** Group order should match what
      the inventory-sort button produces on your own inventory, since it is literally the same
      comparator. Name is A-Z, amount is most-numerous first.
- [ ] **A large network does not stutter.** Fill something close to 64 units with varied items
      and open the terminal. Two known costs were removed unmeasured -- aggregation was
      quadratic in distinct items, and the draw path aggregated three times per frame -- so this
      needs a real network to confirm rather than an argument.
- [ ] **The layout survives a small window.** The header now holds a title and a search box, and
      the footer a capacity label and three controls, so there is more to collide than before.

## Container lifecycle

The parts most likely to lose or duplicate items, which is the one failure class that cannot be
recovered from a save.

- [x] ~~**The round trip** -- open, withdraw, deposit, close, and item conservation at every
      step.~~ Asserted headlessly by `container_roundtrip`, 11 assertions. It was described here
      as the single highest-value check in this file, which is why it was worth automating first.
- [ ] **The six click conventions individually**, via `/arcanestorage click <slot> <action>`:
      `LEFT_CLICK`, `RIGHT_CLICK`, `QUICK_MOVE`, `TAKE_ONE`, `QUICK_MOVE_ONE`, `QUICK_GET_ONE`.
      The design commitment is that these behave as they do in a vanilla chest; a subtle
      divergence here is the kind of thing players report as "feels wrong" rather than as a bug.
- [ ] **Walking out of range closes the terminal**, as it does for a vanilla chest.
- [ ] **Two terminals open at once**, by two players on one network: withdraw at one and confirm
      the other reflects it rather than showing a stale grid. `ServerClient` calls
      `isValid` every tick, and our implementation checks every linked unit, so the mechanism
      exists — but it has only been exercised for a *destroyed* unit, not for a concurrent
      withdrawal.
- [ ] **Withdraw more than one stack of a *stackable* item** — ask for more `ironbar` than one
      stack holds. The unstackable case passed (Aug 2026), but it cannot exercise the clamp:
      with a maximum stack of 1 the clamp is satisfied by definition, so what was verified is
      that many single-item stacks transfer correctly, not that a request larger than a stack is
      cut down to one.

## Readouts and judgement calls

- [ ] **Per-unit `slots used`.** Compare two units' individual readouts against the terminal's
      total. It counts occupied slots directly via `Inventory.getUsedSlots()`, so it cannot
      disagree with a unit's real contents — but it is deliberately *per unit*, so a network's
      total is the sum across its units, and that needs to read as intentional rather than as a
      bug.
- [ ] **Is placement discoverable as membership?** A Storage Unit has no UI of its own to host a
      "join network" button, because a terminal is the only way to interact with one. Membership
      is therefore placement: put a unit against the network and it joins. The claim is that this
      is *more* discoverable than a button. Worth confirming against the discoverability
      requirement by placing a unit without reading anything first.

## Multiplayer — Phase 7, but cheap to spot-check early

- [ ] **Two clients, one dedicated server**, both opening terminals on the same network.
- [ ] **Concurrent withdrawal of the same item** by two players does not duplicate it. The
      server re-resolves every withdrawal against its own units and no network slot index is
      ever sent by the client, so the design should hold — unverified.

## Passed

Move items here with a date once verified, rather than deleting them, so a regression has
something to point back at.

- [x] **Depositing into a full network** fails visibly and leaves the items with the player.
      *(Aug 2026.)* The failure class this rules out is the worst one available — silently
      consuming items on a deposit that could not fit.

- [x] **Withdrawing more than one stack of an unstackable item.** *(Aug 2026.)* See the open
      stackable case above for what this does and does not cover.

- [x] **Breaking a unit while the terminal is open** closes it immediately, with no stale
      entries in the grid and no odd stacks. *(Aug 2026.)* This was a real bug: the container's
      slots held a live reference to the removed unit's `Inventory`, so withdrawing produced
      items the world no longer contained and depositing wrote into an object that was never
      saved. Now covered mechanically as far as the harness can reach it — orphaning beyond a
      broken unit or conduit is asserted in `tests/scenarios/topology.txt` and
      `tests/scenarios/conduits.txt`.

## Category picker — the submitted icons went unused

`art-submissions/2026-08-09-tiers-and-ui/ui/category_{all,ammo,material,misc,placeable,tool}.png`
are not wired to anything, and the category filter shipped as a dropdown over the game's own
category tree instead. Reasoning is in ROADMAP Phase 3: Necesse's eight top-level categories do not
line up with those six buckets, most visibly that `consumable` (all food, 59 items) has no icon
while `ammo`, a subcategory, has one.

Worth a decision rather than silent abandonment:

- Leave it as a dropdown, and drop the six icons.
- Keep the dropdown and add a short row of icon shortcuts for the categories worth one click, drawn
  against the real names — which would need `consumable` and `wiring` icons, and `placeable` renamed
  to `objects`.

Nothing here blocks Phase 3. The dropdown is complete and needs no art.

## Terminal size and footer — August 2026, awaiting first look

The form went from 368x~300 to **656x412** and the grid from 10x6 to 18x8. Numbers are arithmetic
from the constants, not observed: nothing about a container form can be checked headlessly.

What to judge:

1. Does it fit your screen at your UI scale, with the player inventory below it? This is the one
   real risk of the change. Vanilla precedent says it should -- the settlement menubar is 800 wide
   and vanilla container sub-forms reach 400 tall, and the creative menu is 684x264 -- but precedent
   is not your monitor. Two constants, `COLUMNS` and `ROWS`, tune it.
2. Eight rows should actually be visible. The old grid asked for six and showed five, because
   `FormGeneralGridList` spends 32px on scroll buttons and computes its scroll limit against
   `height - 32`; the grid is now given that extra 32px explicitly.
3. The capacity bar, bottom left, should read "312 / 480 slots" inside the bar, fill
   proportionally, and turn amber at 90% and red when full -- *not* green when full. The vanilla
   component treats full as success, which is right for a crafting cost and backwards for storage,
   so the colours are overridden.
4. The summary, right of the category dropdown, should read "37 kinds, 1,842 items", and switch to
   "12 of 37 kinds" while a search or category is hiding things -- so an empty grid is legible as a
   filter rather than as an empty network.

### Not done: collapsible category sections

The creative menu's structure -- a scrolling box of per-category sections, each collapsible, items
wrapping inside -- was considered and deliberately not copied, though it is the right reference for
*size* and was the reason for this change.

It suits the creative menu because that browses a fixed taxonomy of everything in the game, where
sections are stable landmarks. A terminal's contents change constantly, so sections would appear,
empty and vanish as items move, and the common task is "find what I have, sorted" rather than "walk
the taxonomy". A flat grid with a category filter is also what Magic Storage settles on. Say the
word if you want it tried; it would replace `FormItemList`, which is the flowing grid you said you
liked.
