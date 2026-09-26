# Workshop feedback backlog

Threads from Steam Workshop comments (Aug–Sep 2026), tracked here because they fan out into
different amounts of work and different levels of agreement. Not a roadmap phase — see
`ROADMAP.md`/`RELEASE_ROADMAP.md` for planned work. This is community-submitted, still being
triaged, and nothing here is scheduled until it moves into one of those.

**How to read this file.** Same convention as `QA_BACKLOG.md`: a backlog, not a diary. A finding
is settled once it's confirmed here; open questions stay open until answered.

## 1. Settlers and settlement storage — real bug, cause found, fix not agreed

**Report.** DrMink (Workshop comment, 22 Aug): settlers deposit into settlement-assigned storage
but the terminal doesn't show it, "so they disappear until the terminal is broken."

**What actually happens (confirmed in source).** Assignability to settlement storage is
structural, not something any of this mod's containers opt into or out of: any object entity
that implements `OEInventory` (which in practice means extending `InventoryObjectEntity`) is
eligible, checked independently of what `Container` its interact click opens. That lines up
exactly with the icon colors seen in-game while assigning:

| Object | Icon | Entity class |
|---|---|---|
| Storage Unit | white | `StorageUnitObjectEntity extends InventoryObjectEntity` |
| Storage Terminal | white | `StorageTerminalObjectEntity extends InventoryObjectEntity` |
| Wireless Transceiver | white | `WirelessTransceiverObjectEntity extends StorageTerminalObjectEntity` |
| Station Unit | white | `StationUnitObjectEntity extends InventoryObjectEntity` |
| Base Station | green (matches a plain chest) | `ArcaneBaseStationObjectEntity extends InventoryObjectEntity` |
| Import/Export Bus | red, not assignable | `BusObjectEntity extends ObjectEntity` directly — never implements `OEInventory` at all |

So the bus behaviour is not a disabled flag, it's an accident of the class tree: a bus has no
`Inventory` of its own to expose, so there was never anything to opt out of. That part needs no
fix.

**The terminal's own 10 station slots are dead to the player, but not dead to a settler — this
is the bug.** `StorageTerminalContainer`'s constructor only ever turns Station Units' sockets
into player-facing `Container` slots (`this.stationUnits`, walked in a loop that calls
`addSlot(new OEInventoryContainerSlot(unit, i))`) — `terminal.inventory` itself is never given a
slot anywhere, and `getInstalledTechs()` only reads `getLinkedStationUnits()`, never
`terminal.inventory`. So no player action can ever put an item there or see one that's there.

But settlement storage's deposit job does not go through `Container`/UI slots at all.
`DropOffSettlementStorageActiveJob.perform()` calls `StorageDropOff.addItem(...)`, which calls
`range.inventory.addItem(...)` directly on the raw `Inventory` object — and `Inventory.addItem`
does consult the inventory's own filter (`Inventory.java:85`, `filter.isItemValid(...)`), which
for `StorageTerminalObjectEntity` is wired straight to its `isItemValid` override (accepts
crafting-station items only). So a settler carrying a spare station item, given access to the
terminal via settlement storage, can genuinely deposit it into those 10 slots — invisibly, since
nothing reads or displays that inventory anywhere. This matches DrMink's report almost exactly:
an item a settler deposited, gone from view, with nothing to do about it short of breaking the
object. The player-facing path is dead; the settler-facing path is very much alive.

Once assigned, what a settler can actually do is governed by a second, independent hook:
`OEInventory.getSettlementStorage()`. Its default exposes the entity's real `Inventory` as a
haulable `InventoryRange`. **`StationUnitObjectEntity` already overrides this to `null`**, on
purpose — the existing comment there says exactly why: a hauler could otherwise carry off an
installed crafting bench.

**Not a newer-vs-older story — both objects hold benches today, by design, and only one got the
override.** The terminal's own `StorageTerminalObject.SLOTS =
StorageTerminalObjectEntity.STATION_SLOTS` (10) is not a leftover from before Station Unit
existed: its doc comment states plainly that the terminal's slots are crafting-station installs,
present tense, on purpose ("Its slots are crafting station installs instead, which is why this is
no longer zero"). Station Unit is a later, additional mechanism — built so station capacity could
be something placed and paid for on the network rather than ten slots that came free with the
terminal — but it did not replace the terminal's own ten. `StorageTerminalContainer` actively
queries both: its own ten station slots (unreachable by a player, reachable by a settler), and
`terminal.getLinkedStationUnits()` for whatever Station Units are on the network. Both hold the
exact same kind of content (installed benches, as items in an `Inventory`), and only
`StationUnitObjectEntity` has the `getSettlementStorage()` override that stops a settler from
hauling one off or depositing into it. The terminal has the identical hazard and was simply never
given the same treatment.

**The full picture, all four objects:**

- **Storage Terminal** — real bug, confirmed above: settlers can deposit into it via settlement
  storage, invisibly, because the same 10 slots that accept the deposit are never shown anywhere.
  **Fixed**: `StorageTerminalObjectEntity.getSettlementStorage()` now overrides to `null`, same
  pattern as Station Unit. Built and tested clean (JUnit + full scenario suite), not yet checked
  in-game. The vestigial `Inventory`/`STATION_SLOTS` underneath it is still there — the override
  stops settlers reaching it, but the dead capacity itself is unremoved; still worth a follow-up
  cleanup, not urgent now that the actual hazard is closed.
- **Wireless Transceiver** — `WirelessTransceiverObjectEntity extends StorageTerminalObjectEntity`
  with no override of its own, so it inherited the terminal's fix automatically. Nothing further
  needed.
- **Base Station** — `SLOTS = 0`, by the entity's own doc comment ("a station holds nothing").
  Structurally assignable (white icon) but always empty today, so `getSettlementStorage()`'s
  default was already a no-op in practice. **Fixed anyway**, same override, as a guard against
  `SLOTS` ever changing later rather than because anything is reachable today. Built and tested
  clean.
- **Storage Unit** — deliberately left alone, not a bugfix candidate the way the terminal was.
  It has a real, large `Inventory` (40–320 slots by tier) and is *supposed* to hold haulable
  items; a settler depositing into it directly isn't the same class of problem as the terminal's
  invisible dead slots. **Decided (23 Aug): keep the passive settler-deposit for now.** It was
  never a planned feature and has an unintended side effect (the staleness below), but disabling
  it is a real capability change with no upside beyond silencing the symptom, and the actual
  reported bug is the cache going stale, not settlers being able to deposit at all. Revisit only
  if the staleness itself gets fixed and this still bothers someone, or if it causes a separate
  complaint on its own. The staleness mechanism, for whenever this comes back: a settler
  depositing directly into a unit's `Inventory` bypasses this mod's own bus/network tick path
  entirely, and `NetworkIndex` (the terminal's content cache) has a self-documented gap —
  `FRESH_FOR_TICKS = 600` (10s) with "maintenance driven by a change hook" not yet built — so a
  foreign write is invisible until the cache naturally expires or something calls
  `topologyChanged()` (which breaking any network object does, which is why breaking the terminal
  "fixes" it — it's forcing the one invalidation path that exists, not a real fix).

**Not yet agreed:**

1. **Storage Terminal, Wireless Transceiver, Base Station**: fixed, see above.
2. **Storage Unit**: deliberately deferred (23 Aug) — see above. Not urgent unless the staleness
   itself becomes worth fixing, or the passive-deposit behavior draws its own complaint.
3. **Access control**: **implemented (23 Aug).** Confirmed independent of everything above —
   `getSettlementStorage()`'s only three real callers in vanilla source
   (`SettlementContainerObjectStatusManager`, `SettlementInventory.getInventoryRange`,
   `SettlementAssignWorkForm`) are all inside the settler/settlement subsystem exclusively; none
   touch player interaction or `Container` at all. Overriding it (as Station Unit does) only
   tells settlers "nothing to haul here" — it has no bearing on which *players* can open or use an
   object, so it doesn't skip, satisfy, or interact with any access-control check in either
   direction. This mod had zero access-control checks anywhere before this — any player who could
   reach a terminal/unit/bus could use it, team or no team.

   **What shipped.** A new, self-contained helper — `arcanestorage.access.SettlementAccess` —
   rather than extending `SettlementDependantContainer`, since that would pull in the
   settlement-config UI machinery (subscribe actions, settlement panel forms) this mod already
   opted out of on purpose. The helper copies only `SettlementDependantContainer.hasSettlementAccess`'s
   rule, not its base class: access is granted if the settlement covering the tile is not private,
   has no owner, the requester owns it, or the requester's team matches the settlement's team —
   read directly off `NetworkSettlementData`, not simplified to a bare team-match check, since an
   unowned or non-private settlement is meant to be open to anyone.

   **One deliberate deviation from the vanilla rule it mirrors.** `hasSettlementAccess` returns
   `false` when there is no settlement at all, because a `SettlementDependantContainer` is expected
   to always have one (`tick()` closes it otherwise). Arcane Storage objects have no such
   expectation — most are placed outside any settlement's bounds in normal play — so here, no
   settlement means nothing to restrict: access is open. Getting this backwards would have broken
   every solo or no-settlement game.

   **Where it's wired in**, each site returning early with the same
   `arcanestorage_access_denied` chat message on denial:
   - `StorageTerminalContainer.openAndSendContainer` — covers the Storage Terminal and, by
     inheritance, the Wireless Transceiver.
   - `BusContainer.openAndSendContainer` — Import/Export Bus.
   - `BaseStationContainer.openAndSendContainer` — Base Station.
   - `AccessPointContainer.openAndSendContainer` — Access Point.
   - `UnitUpgradeContainer.open` — covers upgrading a Storage Unit or Station Unit in place, which
     is a unit's *only* direct player interaction; it never opens an inventory UI of its own
     (`StorageUnitObject.interact()` deliberately skips `super.interact()` — a unit is browsed only
     through a terminal, never directly).
   - `RemoteTerminal.resolve` — the Wireless Terminal item's own choke point, added after Elias
     pointed out the item keeps a binding (level + tile) to its paired transceiver rather than a
     tile of its own. A new `Result.DENIED` outcome joins `UNPAIRED`/`BAD_LEVEL`/`GONE`. This one
     check covers both the initial open (`WirelessTerminalItem.open()`) and the ongoing per-tick
     validity re-check (`RemoteTerminalContainer.isWithinReach()`, which already treats any non-OK
     result as "close the container") — so access revoked mid-session (a team change, a settlement
     changing hands) closes the terminal on the next tick with no extra code.

   Built and tested clean (JUnit + full scenario suite, 273 passed / 2 xfailed baseline) after
   every insertion. Not yet checked in-game — Elias was unable to test the access-control path
   itself this session (no settlement/team setup on hand), though the settlement-storage fix from
   item 1 above was confirmed working in-game.

   **Not done, flagged as a possible follow-up, not yet requested:** no automated test written for
   `SettlementAccess.isAllowed(...)` itself. It has several branches (no settlement / not private /
   no owner / owner match / team match / team mismatch) and is security-relevant, which is usually
   this project's bar for writing one, but nothing has confirmed a scenario-test harness path
   exists for setting up a private, owned settlement with two different teams to exercise it
   against.

## 2 & 3. Category tabs and scroll position — re-scoped

DeadSplash's two comments, re-read together:

> "the workshop items are all put under the same tab... i am wondering if it is possible to make
> tabs for weapons, armor, trinkets, consumables"

Open question: this may not be about the storage terminal's item grid at all — it could be about
the **crafting tab**, where a modded recipe list has no categorisation the way the storage grid
already does. Not resolved which one DeadSplash meant; worth asking directly rather than guessing
further, since the two have different amounts of existing infrastructure to build on.

**What's confirmed either way:**

- The storage side already has full category filtering, including any category a mod registers.
  `ItemCategory.masterCategory` is one global, mutable tree vanilla and every mod populate through
  the same `Item.setItemCategory(...)` call — there's no separate "modded category" concept, so
  nothing needs to be added for detection. `StorageTerminalContainerForm` already walks this tree
  into a dropdown (`categoryButton`) and filters the grid by it (`matchesCategory`). If DeadSplash
  meant storage, the data already exists; what's missing is only presentation.
- The crafting tab's categorisation, if that's what he meant, hasn't been investigated yet —
  separate follow-up once it's confirmed that's the actual target.

**Design options for the storage side, not yet chosen:**
- Small per-category toggle buttons alongside the existing dropdown (quick top-level shortcuts,
  dropdown stays for the long tail).
- Expandable grouped tabs, mirroring how the crafting tab already groups by tech/category.

**The general thread across both 2 and 3: state should persist across opens, not across login.**
Whatever was last open (scroll position, selected category, active filter) should still be there
next time the terminal opens, but reset on logout/relogin — deliberately, so a confused player
always has a way back to a known default state without a support question. This reframes the
scroll bug below as one instance of a broader "remember view state for the session" requirement,
not an isolated fix.

**Scroll bug, root cause (confirmed, independent of the above).** `refreshList()` calls
`itemList.reset()`, and vanilla's `FormGeneralList.reset()` unconditionally zeroes scroll on
every call — so any content change anywhere (a settler's deposit, a bus moving an item, another
player, the viewer's own withdrawal) resets it. Matches both of DeadSplash's reports (#18/#19).

Not a one-line fix: vanilla's `FormItemList` has no public way to refill its backing item list
without going through `reset()`, and no public getter for the current scroll offset (`protected
int scroll`, no accessor). Scroll *is* auto-clamped against the new element count on the normal
scroll-input path, so restoring a stale value after the list shrinks is already safe — the only
problem is that nothing can set it without either patching vanilla (`@ModMethodPatch`, brittle
per `MODDING_API.md`'s own warning about binding to exact signatures) or reaching into the
protected field via reflection (contained to one method, but still outside documented API). One
of these two needs picking before this gets fixed, and it's worth doing as part of the wider
persist-view-state design rather than as a narrow patch, since the same state-preservation
problem applies to category/filter selection too.

## 4. "Auto-pickup into the network" — solved already, gap is discoverability

Lyzrac's suggestion (carry a wireless terminal, walk over drops, they go straight into the
network) is not being built. The real frustration is likely elsewhere — depositing everything in
the backpack should be easier, and right now Deposit All is close to useless for anyone who keeps
non-hotbar items in dedicated bag slots (a Void Bag, a Potions Bag), since today's Deposit All
would sweep those into the network along with everything else.

**This turns out to already be fully solved on both sides, confirmed in source:**

- Vanilla has a general per-slot item lock, not limited to armor/trinket slots as the version
  history's wording might suggest — **Alt+Click any item in any inventory slot locks it**
  (confirmed on the wiki's own UI guide), and it is a real, persisted `Inventory` field
  (`Inventory.setItemLocked`, saved via `InventorySave`), not container-specific state.
- This mod's own `StorageTerminalContainer.depositAll()` **already checks it**, deliberately: its
  doc comment states outright that both an individually locked slot and a fully locked hotbar are
  left alone, "so the loadout someone deliberately arranged survives the click" — and quick-stack
  (a different button) goes through vanilla's own `quickStackToInventories`, which independently
  checks the same lock.

So there is no code gap here. The likely real problem is that players don't know Alt+Click
exists, or don't think to use it on a bag's contents rather than the bag itself. Options, not yet
decided:
- A tooltip or hint on the terminal's Deposit All button mentioning the lock.
- A wiki callout (`docs/wiki/`) since this is really a vanilla mechanic our own docs don't
  currently surface.
- Nothing code-side — this may just need to be answered in the Workshop comment thread.

Auto-pickup itself is *not* ruled out forever, just deprioritised: the pieces exist
(`WirelessTerminalItem`, `RemoteTerminal.resolve` for finding a bound network without standing at
a physical terminal), but it's a real design task (does insertion respect bus/unit filters, what
happens on a full network, opt-in or automatic) that only matters if the lock-discoverability fix
above doesn't already resolve the underlying complaint.

### 2/3 update (24 Aug) — recursive category tree, replacing the dropdown design options above

The "small toggle buttons" vs. "expandable grouped tabs" options above are superseded. The chosen
design is a full recursive, indented, collapsible category tree — modeled on
`CreativeItemsTab.CategoryForm` (the creative menu's own item browser), not the workstation's flat
one-level grouping, and shared between the crafting tab and the storage tab rather than built
twice. The reasoning: a mature network can hold a large fraction of the game's item registry, the
same scale problem the creative menu exists to solve, so its solution is the right fit rather than
a fixed-depth cutoff or a coarse/none/fine picker (both considered and dropped once this was
identified as the real precedent).

**Data model**, `arcanestorage.ui.CategoryGrouping<T>`: three independently-rebuildable
structures — a flat entry list, a boolean pass/fail mask, and a per-category `int[]` position map
— so a search keystroke, a sort-mode change, and the network's contents changing each pay only
their own cost, not the most expensive of the three. Matching logic (what a search term means,
what "craftable" means) stays entirely with each tab's existing filter code; this class only ever
asks "does entry i pass."

**Rendering**, `arcanestorage.ui.CategoryTreeForm<T>`: one recursive component, generic over leaf
type, reused for both tabs. Storage keeps its existing `SortMode` (Group/Name/Amount) as the
within-category order; crafting's own order is fixed — craftable-first, then alphabetical — not a
player choice.

**Status: both tabs migrated, shipped in 1.1.0** (the storage form in v1.1.1 builds from `storageGrouping`).
The paragraph that follows is the state as of 24 Aug, kept for the reasoning. **Status then: crafting tab
migrated, storage tab not started.** The crafting tab's old flat,
fixed-depth grouping (`groupCraftingByCategory` setting, `CRAFTING_CATEGORY_DEPTH` constant, the
checkbox) is fully removed, not kept as an alternative. Storage's category dropdown
(`categoryButton`, `addCategoryOptions`) has **not** been removed yet — that, plus a new per-item
leaf-cell component (porting `FormItemList.ItemElement`'s draw/click/tooltip behaviour) and wiring
storage onto `CategoryGrouping<InventoryItem>`, is the remaining work.

**Two real bugs found from in-game screenshots (24 Aug), both fixed same day:**

1. **Every category section drew a full opaque panel-plus-border behind itself**, making nested
   categories look like a stack of separate boxed tiles rather than an indented tree. Root cause:
   `CategoryTreeForm extends Form`, and `Form`'s own `(String, int, int)` constructor turns on
   `drawBase`/`drawEdge` unconditionally — fine for a single top-level window, wrong for a
   component instantiated once per category, nested arbitrarily deep. Fixed by setting both
   `false` right after the `super(...)` call.
2. **Collapsing or expanding a category didn't reflow anything below it** — screenshots showed
   large blank gaps where a collapsed section's siblings hadn't moved up to fill the space. Root
   cause: every level that stacks children (`CategoryTreeForm.rebuild()` for a parent's own
   children, `buildTopLevel()` for the top level) computed each sibling's Y position once, from
   heights read at construction time, with nothing recomputing that layout when a child's height
   later changed via collapse/expand. Fixed by giving every node an `onHeightChanged` callback,
   set by whichever caller stacked it next to its siblings, invoked whenever `updateOwnHeight()`
   detects an actual height change — so a fold three levels deep now reflows every ancestor's
   siblings, and `buildTopLevel`'s own new `IntConsumer` parameter lets the owning tab keep its
   scrollable content box in sync too.

Both fixes are built and pass the full test suite (JUnit + scenario suite, 273 passed / 2
xfailed baseline unchanged) but **not yet re-checked in-game** as of this update.

**New, related persistence bug reported the same session, not yet investigated:** category
expand/collapse state now only survives switching tabs within one open terminal, not closing and
reopening the terminal — expected to persist across a full close/reopen the same way the rest of
this section's "state should persist across opens, not across login" principle calls for.
Suspected but unconfirmed mechanism: whatever supplies the `ItemCategoryExpandedSetting` root is
likely read from an in-memory `Settings` singleton rather than the mod's on-disk config, so it
survives as long as one object reference does (a tab switch) but not a fresh container open.
Elias separately suggested this might be worth exposing as its own settings-menu option once
fixed (e.g. "remember category folding" as a persistent per-player preference) rather than always
resetting to default — noted here, not decided or scheduled.

## 5. Pouches and bags "stuck" in the terminal — not reproduced; one plausible mechanism found and fixed

**Report.** Seramicx (Workshop, 18 Sep): "I put a lunchbox, coin pouch, and shadow horror bag inside
the storage terminal, and now they're all stuck in there, can't get them out."

**What the three items have in common**, which is the only lead the report itself gives: all are in
the vanilla category `misc/pouches`, and all carry per-instance state in GND data. `Lunchbox` extends
`PouchItem` (which `implements InternalInventoryItemInterface`, keeping a whole `Inventory` under the
`inventory` GND key); `CoinPouch` extends `Item` directly but keeps a coin count under `coins` and is
the one vanilla pouch that actually compares it, in `isSameGNDData`. "Shadow horror bag" is **not a
vanilla item** — no such string ID in `locale.tsv` — so it is either modded or a misremembered name,
and which mod it comes from is worth asking, because it is the one of the three whose class is
unknown.

**Reproduction attempted and failed.** A new scenario file, `tests/python/test_pouch_items.py`,
drives the real paths a player uses. Fifteen cases, all passing, over `lunchbox`, `coinpouch` and
`voidbag`:

- the round trip a player actually performs — `give`, `open`, Deposit All, withdraw, and the item is
  back in the player's inventory;
- all three pouches deposited together, then withdrawn one by one;
- empty pouches, and pouches with contents (a new `stuffpouch` verb writes real internal-inventory
  contents, or coins for `CoinPouch`, so the GND data is non-empty);
- aggregation listing a stuffed pouch, asserted through `expect item` — the aggregating path — rather
  than `query item`, which counts slots by string ID and would pass even if the grid dropped the entry.

**Four candidate mechanisms examined and ruled out**, each with the reason, because they are the
plausible ones and a later attempt should not pay for them again:

1. **GND-data mismatch on withdrawal.** `WithdrawAction` matches with
   `equals(level, wanted, true, false, AGGREGATE_PURPOSE)`, so GND data *is* compared. It does not
   matter for `PouchItem`: `Item.isSameGNDData` returns `true` by default and `PouchItem` does not
   override it, so any two lunchboxes match. `CoinPouch` does override it (`sameKeys(..., "coins")`)
   and still round-trips, because the packet carries the GND map both ways.
2. **A pouch in the network swallowing deposits.** `PouchItem.inventoryAddItem` diverts an incoming
   item into the pouch only when the purpose is in `insertPurposes` (`itempickup`, `lootall`,
   `restockfrom`). Every purpose this mod uses is its own namespaced string — `arcanestorageaggregate`,
   `arcanestoragedeposit`, `arcanestoragebus`, `arcanestorageempty`, `arcanestorageupgrade` — so none
   qualifies. This is worth keeping in mind as a constraint on any *new* purpose string: reusing a
   vanilla one would turn every stored bag into a sink.
3. **A bus raiding a stored bag's contents.** `PouchItem.removeInventoryAmount` donates what is inside
   for any purpose *outside* its `requestPurposes` blacklist, which holds only `quickstackto` — so
   `arcanestoragebus` does qualify on its own terms. The reason it cannot happen today is indirect and
   load-bearing: the scheduler plans from `NetworkIndex`, which counts slot items only, so a stored
   lunchbox's bread is never in a plan and no removal is ever attempted. **Anything that plans a
   removal from a count it did not get from the index loses that protection.** Guarded by
   `test_an_export_bus_does_not_raid_a_stored_bag`, whose docstring says exactly this so the guard is
   not mistaken for a direct one.
4. **Aggregation dropping the entries.** It does not; `expect item` sees them.

**Two things found while looking, both real, neither the reported bug:**

- **The harness's `withdraw` verb was testing a request no client makes.** It encoded a fresh
  `new InventoryItem(itemID, 1)`, so every withdrawal test in the suite compared an item with *empty*
  GND data. The form sends the aggregated entry the player clicked, GND data and all. Fixed: the verb
  now resolves the entry from `getAggregatedItems()` and falls back to a bare item only when the
  network does not hold one. No existing test changed behaviour — for plain items the two encodings
  are identical, which is exactly why the gap survived.
- **Two pouches of the same kind with different contents merge into one grid entry.** A consequence of
  point 1: identity is the `Item` singleton, so aggregation sums two lunchboxes into one entry of
  amount 2 and a withdrawal returns whichever slot the scan reaches first. Not reported by anyone, and
  arguably worse to "fix" by splitting entries (a hundred distinct tools would become a hundred rows),
  but it is the kind of thing that reads as items being swapped. Recorded, not scheduled.

**A mechanism that fits the modded bag, found and fixed (26 Sep).** Withdrawal matches the request with
`equals(level, wanted, true, false, AGGREGATE_PURPOSE)`, so the item's own `isSameGNDData` decides. The request
has been through `InventoryItem.addPacketContent`, which carries id, amount, lock and GND data but **not
`isNew`**, and `GNDItemInventory.equals` compares the items *inside* a bag with `ignoreMeta = false`, which
includes `isNew`. So a bag whose `isSameGNDData` compares its whole GND map (CoinPouch's pattern with no key
list, a natural thing for a mod author to copy) never matches its own round-tripped request. The click does
nothing, the item stays in the grid, and it cannot be taken out, which is exactly the report. Vanilla bags inherit
`isSameGNDData -> true`, which is why none of the vanilla reproductions failed.

`WithdrawAction` now falls back when nothing matches exactly: if the network holds exactly one variant of the
item (by the aggregation's own identity, so one grid entry means one variant), that variant is withdrawn. If it
holds several, nothing is, because handing out a different enchantment or a different bag's contents is a swap
the player did not ask for. Covered by two tests that fake the drift with a `gnd:coins=N` override on the
`withdraw` verb, since CoinPouch compares that key. **The first fails without the fix and passes with it**,
which was checked by disabling the fallback. Plain clicks to the cursor were also added to the suite. They
take a different path (`combineSlots` rather than a transfer) and all pass.

This is a plausible fit, not a confirmed cause: the reporter's bag was not available to test, and the lunchbox
and coin pouch he also named are vanilla and do not fail this way. Asking him the two questions below is still
the way to close it.

**What is left, and it is the client.** The suite is server-side by construction — a mouse button, a
hit test and a cursor are client state — so the one region the reproduction cannot reach is the part
the report is most consistent with: no clickable cell. The relevant code is also the newest in the
mod. `misc/pouches` is a depth-2 category, `CategoryGrouping` keys entries by the `ItemCategory`
instance returned by `ItemCategory.getItemsCategory(item.item)`, and `CategoryTreeForm` finds them by
walking down from `ItemCategory.masterCategory` — if those two ever disagree for a node, the entry
exists, aggregates, counts toward capacity, and draws nowhere.

**Next steps, in order of cost:**

1. Ask Seramicx two questions that separate the halves: *are the items visible in the terminal's grid,
   or missing from it?* and *which mod is the shadow horror bag from?* A visible-but-unclickable item
   and an invisible one have nothing in common below the report.
2. In-game check with a lunchbox and a coin pouch, which is the only way to exercise the grid. Needs a
   real client session.
3. Only if 1 says "visible": re-examine the withdrawal against a *named* pouch
   (`setPouchName` writes `pouchName` into GND, and `PouchItem.getLocalization` then reads it) and one
   with auto-pickup disabled (`pickupDisabled`), the two GND keys a player can set that the tests do
   not cover.

## 6. Production stations in the terminal (forge, roasting/cooking) — declined here, deferred to Arcane Production

**Report.** Seramicx (Workshop, 18 Sep), two comments: a forge cannot be installed in a Station Unit,
and the suggestion that it should work as Magic Storage's does — forges installed into the terminal,
their smelting recipes appearing in the crafting tab, fuel-free and instant, with settlers still
having to use a placed forge. Then the same for the roasting/cooking station.

**Answered in thread (Sep 2026)**: timed "production stations" like the forge and grain mill were tried in
Arcane Storage and the interactions got too messy, and a separate mod meant to interact with Arcane Storage
covers this and more. The reasoning is already this project's: timed production stations were
tried in Arcane Storage and the interactions got messy. A forge is not a crafting station with a
recipe list — it consumes fuel over time — so making it instant and free inside the terminal is a
balance change disguised as a UI feature, and making it *not* instant means modelling a queue in a
container that has no tick of its own for it.

**This is Arcane Production's remit**, which exists for exactly this class of machine and is designed
to interact with Arcane Storage. No work here. Left in this file rather than dropped, because "why
can't I put a forge in" will be asked again and the answer should stay written down.

**Half of it is not a suggestion but a missing explanation.** "I am unable to put the forge inside the
tungsten station terminal — is this intended?" means the refusal reads as a bug. A Station Unit's
`isItemValid` accepts crafting-station items only, and refuses silently. A rejection message naming
the reason ("production stations are not crafting stations") would cost one locale string and close
the question without any mechanism changing. Not scheduled; worth doing next time the station UI is
touched.

## 7. Export bus: keep the adjacent container stocked at N per item — implemented (26 Sep), unreleased

**Report.** Talion The Tark (Workshop, 8 Sep, following up on a 22 Aug exchange): an export bus
option to keep the neighbouring container topped up at a chosen amount per item — "select stone and
sand and indicate 100, the exporter will always keep the adjacent box stocked with 100 stone and 100
sand." The follow-up is asking whether it has progressed; the earlier answer was that external
storage constraints are planned but awkward to do reliably and efficiently.

**Where this actually stands.** The rule already exists in the import direction and the number is
already per-item: `ItemCategoriesFilter` holds an allow flag and an amount per item, and
`networkShouldHold` reads it as "fill up to N" for an import bus and "drain down to N" for an export
bus. So the request is not a new rule type — it is the same number read against the *neighbour's*
contents instead of the network's.

That is the whole difficulty, and it is worth stating precisely because the thread's answer did not:
every count the scheduler plans from comes from `NetworkIndex`, which is a cache of the network's own
member inventories with a change hook and a drift check behind it. A chest on the far side of a bus
has none of that — nothing invalidates a cached count when a settler takes a stone out of it — so the
naive version rescans the neighbour every tick per item, which is the cost the index was built to
remove, and the careful version needs the same machinery pointed at an inventory the mod does not own.

**Implemented (26 Sep), and the difficulty above turned out smaller than stated.** A bus's container is
*already* watched: `IndexedInventories.watch` registers it every tick, so the `updateSlot` hook marks an item
dirty on the network's scheduler when anything, a settler included, takes it out of the chest. Nothing has to
poll. The chest is counted only when the scheduler has already been told that item changed, and it is one
container's worth of slots. An emptied slot, which does not say what left it, already triggers
`reconsiderEverything`.

What shipped:

- `BusObjectEntity.stocking`, a flag beside the filter (not a reuse of `ItemCategoriesFilter.limitMode`, which
  is already overloaded for a bus). Saved only when set, so old saves read unchanged. Export buses only.
- `containerShouldHold` uses the same arithmetic as `networkShouldHold` (item limit, category limits walking
  up, the panel-wide number, tightest wins), with the category term counted in the chest. The two now share
  one `shouldHold`.
- `DeviceOnNetwork.containerTargetFor`, NONE by default. In `NetworkScheduler.resolve` a stocking device
  contributes no network floor and gets no share of the surplus. A new `stock` step tops each stocking chest up
  to its number from what the network holds. It never pulls back. An item ticked without a number is still a
  plain drain-everything export.
- **Conflict rule.** A stocking exporter and an importer on the same chest have no resting state for any
  positive stock, whatever the importer's number, so `whyRefused` refuses it at Apply. A stock of zero rests
  and is allowed. `whyRefused(filter, stocking)` judges the flag together with the filter, because stocking
  alone can turn an accepted rule set into a refused one.
- UI: a checkbox, *Stock the container instead of the network*, under the amount row in `BusRulesEditor`,
  offered on export buses in both the bus panel and the logistics tab. The flag travels after the filter in the
  open packet, `SetFilterAction`, `SendRulesAction` and `SetRulesAction`. One new locale key in all ten
  languages.

Tested in `tests/python/test_bus_stocking.py` (10 cases): fill to N with the network keeping the rest, a control
with the flag off, a top-up after a `take`, two items at their own numbers, an over-full chest left alone, a
short network, conservation, the conflict refusal, save/load (`bussave`), and the open-packet hand-off,
which guards against the panel opening unticked and Apply silently switching the flag off. New harness verbs:
`busstock`, `take`, `bussave`. **Not yet seen in game.** The checkbox's placement in the logistics pane is
the part most likely to need a look.
