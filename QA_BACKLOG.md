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

- [ ] **Storage Conduit renders.** Place one and confirm it appears in the world, has an
      inventory icon, and shows "Extends a storage network" on hover. `addDrawables` and
      `drawPreview` are compiled but have never executed. The texture is currently a
      placeholder copied from the Storage Unit, so expect it to look wrong — what matters is
      that it draws at all and is not the pink `[ER]` placeholder.
- [ ] **Conduit rotation.** Once the real sprite exists with four facings, confirm placement
      rotation cycles through them. Frame count is derived as `width / 32`, so this changes
      behaviour the moment the art changes, with no code edit.
- [ ] **Terminal open state.** Once `objects/arcanestorageterminal_open.png` exists, confirm it
      swaps in while the terminal is open and reverts when closed.
- [ ] **All three objects show correct names and icons** in the crafting menu and inventory.

## Container lifecycle

The parts most likely to lose or duplicate items, which is the one failure class that cannot be
recovered from a save.

- [ ] **The round trip.** `/arcanestorage run session/roundtrip`. Sets itself up and covers
      open, withdraw, shift-click deposit, close, and item conservation at every step. This is
      the single highest-value check in this file.
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
- [ ] **Withdraw a partial stack, and more than one stack.** `WithdrawAction` clamps a single
      request to one stack size, so asking for more than that should yield exactly one stack
      rather than failing or over-delivering.
- [ ] **Deposit into a full network** should fail visibly and leave the items with the player,
      not silently consume them.

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

- [x] **Breaking a unit while the terminal is open** closes it immediately, with no stale
      entries in the grid and no odd stacks. *(Aug 2026.)* This was a real bug: the container's
      slots held a live reference to the removed unit's `Inventory`, so withdrawing produced
      items the world no longer contained and depositing wrote into an object that was never
      saved. Now covered mechanically as far as the harness can reach it — orphaning beyond a
      broken unit or conduit is asserted in `tests/scenarios/topology.txt` and
      `tests/scenarios/conduits.txt`.
