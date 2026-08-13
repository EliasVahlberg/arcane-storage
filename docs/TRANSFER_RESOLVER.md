# Transfer resolver — specification

Status: **specification, nothing built.** Supersedes the per-device transfer loop in `BusObjectEntity`.

This exists because the current design cannot be repaired incrementally. Each bus owns a timer, rediscovers
its network, recounts what everything holds, and decides alone. That is wrong in three separate ways, all
measured rather than argued (`tests/python/test_bus_dynamics.py`):

- **It churns forever on rules that disagree.** An import bus told to keep 50 and an export bus told to leave
  20, on one network and one chest, produced **12 moves in 120 ticks with the network total reading a steady
  20 throughout**. Both devices obey their own rule; together they describe no reachable state. The steady
  total is the cruel part: the numbers look settled while the system works forever, which is why it reads in
  game as a lockup rather than as a runaway.
- **It cannot detect that.** A device that knows only its own rule cannot discover a contradiction except by
  fighting. Detection needs something that sees every constraint on an item before anything moves.
- **It pays for what it does not do.** An idle bus with an empty chest beside it costs **5 network flood fills
  and 200 slot scans per 100 ticks**, and that cost is per bus and independent of whether anything happened.

A fourth finding constrains the solution. Vanilla's `Inventory` notifies **only its first** slot-update
listener — the notify site uses `if`, not `while` — verified by attaching two listeners and changing one slot
(`tests/python/test_engine_assumptions.py`). So subscribing to inventories we do not own is not available: we
would either be told nothing, or take the only notification away from the game's own container forms.

## The shape

One canonical current state per network, one resolver, one queue.

- **Index.** Per network, item → count, maintained incrementally. This is the single copy of derived state.
  Devices hold none. It is the same index the terminal already wants, and vanilla's settlement storage index
  is the precedent for keeping one.
- **Constraints.** A rule is a constraint on a `(network, item)` pair, not an instruction to a device. An
  import bus contributes a *ceiling* — fill the network to C. An export bus contributes a *floor* — drain the
  network to F.
- **Resolver.** Takes the constraints and the index for the `(network, item)` pairs that are dirty, and emits
  a **changeset**: the differences to apply. A materialised "desired state" is never built or stored.
- **Queue.** The changeset is drained under a per-network per-tick budget. Rate is policy and lives here;
  correctness lives in the resolver.
- **Staging.** Changes accumulate as a net delta per `(network, item)` between resolves, so five hundred slot
  updates of stone collapse to one entry.

### Which ACID properties bind, and which would be theatre

Named because the analogy is load-bearing in places and misleading in others.

**Atomicity — conservation, not rollback.** `Inventory.addItem` mutates as it goes, so there is no rollback
across several units, and we do not need one. What must hold is that items are never created or destroyed:
add first, then remove exactly what was accepted. The rule that follows, and the one that makes a crash
survivable: **never hold items outside a game inventory.** No in-transit buffer, no staging chest, nothing
owned by us between source and destination.

**Consistency — the property our bug violates.** A rule set must be satisfiable. The right moment to reject a
violation is when it is written, not by retrying the write forever. Hence the Apply button below, and hence
validation against the network rather than against the device.

**Isolation — temporal, not parallel.** Everything runs on the server thread; that is forced on us by the
lock-order inversions recorded in `WORKFLOW.md`, not chosen. There are no concurrent transactions to isolate.
The real hazard is that time passes between planning an action and applying it, so **every queued action
carries the version it was computed under, and is revalidated at drain**. On mismatch it is recomputed, never
retried blindly. Deltas are not idempotent; states are, and this is the guard that replaces idempotence.

**Durability — deliberately not ours.** The plan is never persisted. The game already saves what must survive:
inventory contents, and our rules through `addSaveData`. Recovery after a crash or a reload is "discard the
plan and resolve again", which is only sound because **the plan is a pure function of world state and rules**.
That property is the main reason to prefer a resolver over incremental device passes: a recomputable plan is
crash-safe by construction, while a persisted queue would need its own recovery and could apply stale actions
after load.

## When work happens

Nothing runs on a timer. Work is triggered, coalesced, and then the system is silent.

| Trigger | Source | Effect |
|---|---|---|
| We moved something | our own `move` | mark `(network, item)` dirty |
| Someone else changed a container | patch on `Inventory.updateSlot(int)` | mark dirty if that inventory is bussed |
| Topology changed | place/break of unit, conduit, bus | recompute membership, revalidate rules |
| Rules changed | Apply in the panel | validate, then mark every affected item dirty |

**The foreign-change hook is a patch, and that is a considered choice.** `Inventory.updateSlot(int)` is the
funnel every mutation inside `Inventory` routes through — 15+ call sites — and it is where both the listener
notify and `markDirty` already live. Patching it gives a change signal for every inventory in the game
without touching the listener list, so vanilla's forms keep their notification. The costs are real and must be
handled explicitly: the hook runs for every inventory in the game, so the "is this one bussed?" test must be
an allocation-free identity lookup and nothing more; and if the patch ever fails to apply, the index must fail
**loudly** rather than quietly believing the network holds iron that is gone.

**Reconciliation.** The index is a cache of state we do not own. It gets a cheap periodic drift check and a
resync on terminal open. Necesse hit this exact bug class in vanilla — the version history records fixing
crafting lists not updating when nearby inventories change — so this is not defensive programming for its own
sake.

**Coalescing.** At most one resolve per `(network, item)` per tick, and the resolve is scoped to dirty pairs.
Re-resolving a whole network per change is Refined Storage's lag pathology and is explicitly out.

**Determinism.** The dirty set is drained in a stable order: network id, then item id, then device tile. The
same state must always produce the same changeset, or none of this is testable.

**Who drives the tick.** No `LevelData` or `WorldData`, because both are only instantiated from save data and
therefore absent on a freshly generated world (`WORKFLOW.md`). Instead the network's own devices tick, and the
one with the lowest tile order drives the scheduler for that network — deterministic leader election, no new
patch, and it disappears with the last device.

## Contradiction: the exact predicate

For a `(network, item)`, let **C** be the highest ceiling any import bus contributes (unbounded if any import
bus has no limit) and **F** the lowest floor any export bus contributes (unbounded above if there is none).
Import drives the count up toward C; export drives it down toward F.

- `C ≤ F` — stable. Import fills to C, export finds nothing above F to remove.
- `C > F` — no stable value exists **if the flow forms a cycle**.

The cycle condition is essential and easy to miss. An import bus pulling from chest A and an export bus
pushing into chest B is `C > F` and perfectly well behaved: items flow A → network → B until A is empty, and
that is a feature, not a fault. It only fails to terminate when the export's destination feeds the import's
source — in practice the same container.

So: **a conflict is a cycle in the flow graph carrying `C > F`.** The common and cheap case to check is one
container serving both an import and an export bus on the same network. The general case is a cycle search
over containers and buses, which is small — the graph has one node per bussed container plus the network.

**Backstop for what static checking cannot see.** A settler or a hopper-like arrangement can close a loop
outside our graph. So the resolver also watches for churn: if a `(network, item)` oscillates beyond a
threshold with no net progress, it fails closed and reports, exactly as if the contradiction had been detected
statically. This is a safety net, not the mechanism.

## Device states

Derived, never persisted, recomputed on load — consistent with the durability stance.

- **Active.** Normal.
- **Inactive, with a reason.** Fail closed: a bus in an unsatisfiable configuration does nothing. For a
  storage mod that is the right default, since moving items wrongly is worse than not moving them, and it
  matches the export bus already starting with nothing ticked.
- **Overloaded** — *not part of this specification.* Reserved for a future throughput constraint, where
  breaking hardware is physically motivated and can carry balance. Deliberately not used for contradictions: a
  device obeying its rule perfectly should not explode because a *different* device disagrees with it, and the
  causal chain from "I typed a number" to "something popped off the wall three tiles away" is not one a player
  can follow.

**Inactive must be legible or it is just a quieter silent failure.** It requires a visible marker on the
object and a reason that names the other party: "inactive — its rule for iron bars conflicts with the export
bus at 14,9". This is what satisfies D22's acceptance clause, "legible in the UI without documentation".

## Apply

The panel becomes transactional: edits are local, and Apply validates the whole set at once, then accepts or
rejects it with the reason shown in the panel — where the player is already looking.

This is not only about validation cost. With immediate application, every *intermediate* state is validated,
and intermediate states are routinely invalid: ticking an item before typing its number is the normal way to
use the panel. Apply also cuts the per-click packet traffic.

It costs a divergence from the vanilla panel, which applies immediately. That is justified: a settlement
chest's filter cannot conflict with anything, and ours is network-scoped and can.

## Acceptance criteria

Each is a test the harness can now write, since it can let time pass (`settle`) and restart.

1. **Conservation.** Total count of every item is invariant across any sequence of operations, including a
   restart in the middle.
2. **Convergence.** Once the rules are satisfied, moves stop. Already asserted for one bus; the open case is
   `test_two_rules_that_disagree_still_settle`.
3. **Determinism.** The same state and rules produce the same changeset, twice.
4. **Crash equivalence.** Restart mid-operation and behaviour is identical, because the plan is recomputed.
5. **Idle cost is zero.** No network walks and no slot scans while nothing changes —
   `test_what_polling_costs_while_nothing_happens`.
6. **Throughput.** A chest of eight item types drains promptly rather than one type per second —
   `test_how_long_a_mixed_chest_takes_to_drain`.
7. **Validation.** A rule set forming a cycle with `C > F` is rejected at Apply, and a conflict arising
   without an edit — placing a bus, or joining two networks with one conduit — leaves the bus inactive with a
   reason naming the other device.

The three tests already carrying `xfail(strict=True)` encode 2, 5 and 6, so they flip to passing exactly when
this is real, and the strict marker means they cannot be fixed silently.

## What was built, and where it departed from this document

`[built Aug 2026]` All five steps are done and pushed. Two deliberate departures, both recorded because the
reasoning matters more than the plan:

**The queue holds items, not planned deltas.** This document specified that each queued action carry the index
version it was computed under, be revalidated at drain, and be recomputed on mismatch. The implementation stores
*what to look at* rather than *what to do*, and computes the move at the moment it is made. That reaches the same
property — no action is ever applied against a state it was not computed for — by construction rather than by
checking, because computation and application are never separated in time. It is less machinery and it cannot be
got wrong. The version counter still exists on the index, and is what the drift check compares.

**"Nothing runs on a timer" has one exception.** Device states are re-derived on a one-second heartbeat, per
network rather than per bus. The triggers cover rules, layout and contents; what they cannot cover is a vanilla
container being placed or broken beside a bus, because a chest is not ours and reports nothing. The compensating
change is that a stale state can no longer stop a device working: only a decision to stop — a rule conflict or a
churn stop — does that, while a missing container or network is a description the move path checks for itself.

One inefficiency is recorded rather than fixed: the index knows how many of a thing a network holds but not which
container holds them, so a move scans the source side to find it. That is work in response to an event rather
than on a timer. A location index — a record per stack, which is what vanilla's settlement storage keeps — would
turn each scan into a lookup, and is the obvious next improvement if anything ever shows it matters.

## Implementation plan

Ordered so that the fault reported in play stops first, and so that every step leaves the mod working and adds
its own tests. Validation does not depend on the resolver — the conflict predicate needs the rules, not the
index — so it comes first even though it is logically the last part of the design.

### 1. Validation and the inactive state

On the existing per-bus loop. Stops the churn.

- The conflict predicate: per `(network, item)`, highest import ceiling C against lowest export floor F, flagged
  only when the flow forms a cycle. Computed on topology change and on rule change, never per tick.
- A state enum on the bus — Active, or Inactive with a reason — **derived, never saved**, recomputed on load.
  An enum rather than a boolean so that a later manual off switch and a "no network" state reuse the same path.
- Synced with `setupContentPacket` / `applyContentPacket` and `PacketObjectEntity`, one packet per transition,
  because the sprite is drawn client-side and this is the vanilla path for object entity state.
- A desaturated sprite variant per device, loaded in `initResources()` (client-only), drawn when inactive.
- A chat line on the transition into inactive, once — never per tick. The durable surfaces are the sprite, the
  hover tooltip and the panel's reason, which must name the other device and its coordinates.
- The terminal lists the network's inactive devices. This is the surface that works when the player was
  elsewhere when it happened, and the only one that scales past a handful of buses.

Headless: the predicate, including the case that must **not** be flagged — import from chest A with export to
chest B, which is `C > F` and terminates. In game: sprite, chat, tooltip, terminal row.
`test_two_rules_that_disagree_still_settle` flips to passing here.

### 2. The index

- `NetworkIndex` per network: item to count, plus the member containers. The single copy of derived state;
  `Holdings` and the per-bus counting go away.
- Initially populated the way the current code counts, but once per network rather than once per bus per
  second. This step is a refactor with no behaviour change, which is why it is separate from step 3.

Headless: counts identical to the current implementation across the existing suites, and `query busstats` shows
walks and scans falling by roughly the bus count.

### 3. Incremental maintenance and the change hook

- Patch `Inventory.updateSlot(int)`, the funnel all mutation inside `Inventory` routes through. Not the listener
  list, which notifies only its first listener.
- The hook fires for every inventory in the game, so the "is this bussed?" test is an allocation-free identity
  lookup and nothing else.
- If the patch fails to apply, fail **loudly** at load. A silent miss means an index that believes in items
  that are gone.
- Reconciliation: periodic cheap drift check plus a resync when the terminal opens.

Headless: a foreign change updates the index with no walk; an induced drift is caught and resynced; the game's
own container forms still receive their notification (`query listenercheck`); the patch's presence is asserted,
not assumed.

### 4. The scheduler

- One per network, driven by the member device with the lowest tile order — deterministic leader election, no
  new patch, and it disappears with the last device. Not `LevelData` or `WorldData`, which only exist when
  loaded from save data and so are absent on a fresh world.
- Dirty set of `(network, item)`, coalesced, drained at most once per tick in a stable order.
- The resolver emits a changeset; each action carries the version it was computed under and is revalidated at
  drain, then recomputed rather than retried on mismatch.
- A per-network per-tick budget replaces the per-bus timer. Buses stop deciding anything: they contribute
  constraints and execute actions.
- The churn backstop: a `(network, item)` that oscillates past a threshold with no net progress fails closed
  and reports, catching loops closed outside the flow graph.

Headless: conservation, convergence, determinism, crash equivalence, zero idle cost, prompt throughput. The
remaining two `xfail` tests flip here.

### 5. Apply

- The panel becomes transactional: edits are local, Apply validates the whole set and either accepts it or
  rejects it with the reason shown in the panel.
- Rejected sets are not partially applied.

Headless: the existing `busroundtrip` extended with rejection cases. In game: the feel of the panel, and that
the reason is legible without documentation.

### Ordering note

Steps 2 to 4 are where throughput improves. Until step 4 lands, a mixed chest still drains one item type per
second, so the slow-drain symptom outlives the fix for the churn.

## Non-goals

- No history of changesets as architecture. One pending delta, not a DAG. A bounded debug log is optional and
  would have made the churn obvious in seconds, but it is a facility, not a design.
- No write-ahead log. It assumes we own the store, and we do not.
- No threading. The server thread is the only correct place for this work.
- No persisted plan, no persisted device state.

## Open questions

- **Item identity in the index.** Counting is by string ID today, so two swords with different enchantments or
  durability are one entry. That is wrong for equipment and irrelevant for materials; the index needs a
  decision before it is built, because it determines what a rule can even express.
- **The throughput budget.** Per network per tick, and what number. Wants measurement, not a guess.
- **Does Apply need Revert**, or is closing the panel without applying enough?
- **Churn threshold** for the backstop, which is the same problem as the budget: measure first.
- **Cross-level networks.** Out of scope here, but the index is per network, and D25's wireless silos will ask
  what happens when the two halves are on different levels.
