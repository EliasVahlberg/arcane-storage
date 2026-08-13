# QA backlog

**How to read this file.** It is a backlog, not a log: the top is what still needs eyes, and
confirmations are collapsed into one list rather than kept as prose. Elias runs every visual test;
nothing here is marked confirmed unless he said so.

Restructured 11 Aug 2026 because it had grown into a diary and he was right that it was stale.
Revised again the same evening: the bash scenario suite it kept citing no longer exists, so every
reference now points at the pytest file that owns the assertion. See `docs/TESTING.md`.

## Confirmed in game

Stated by Elias, in order of confirmation:

- The larger terminal, the 18x8 grid and the footer — *"the size and feel is good with the larger ui"*
- All sixteen conduit shapes
- The dropdown category filter, including composed with search on top
- Filters not sticking between opens — he likes it, and it is now true of the crafting tab too
- The crafting tab: visuals, crafting from the network, the craftable filter, recipe highlighting
- Crafting station detection — it picks out the real workbenches — and their recipes displaying
- The source tickboxes filtering the recipe list
- "Group by category" persisting across restarts

## Still needs eyes

### The transfer resolver — Phase 5b, built while Elias was away, none of it seen in game

Everything below is verified headlessly by 148 scenario tests and 19 unit tests; what none of those can see
is what a player looks at. Run in this order, since each step sets up the next.

1. **Place a terminal, a unit, an import bus and a chest in a row.** Drop a mixed stack of eight or so
   different things into the chest. It should empty within a moment rather than one kind per second, which
   was the old behaviour. Watch for anything that looks like a lurch: the budget is eight moves per network
   per tick, and the question is whether that reads as brisk or as sudden.
2. **Open the bus panel and change a rule without clicking Apply.** The line under the heading should say
   "Unapplied changes" and the Apply button should be lit. Check the button's placement at the bottom of the
   panel — it was put there without being seen, and it may collide with the category tree on a short window.
3. **Click Apply.** The change should take effect immediately: nothing polls any more, so if a rule appears
   to do nothing until something else happens, that is a real bug and worth reporting precisely.
4. **Close the panel with unapplied edits.** They are discarded by design. Judge whether that is acceptable
   or whether it needs a confirmation — a transaction implies it, but a player may not expect it.
5. **Now make a contradiction.** Put an export bus on the same chest, tick one item on it and give it a
   number, then try to give the import bus a *higher* number for the same item and click Apply. It should be
   refused, in red, in the panel, naming the item and the other bus's coordinates. The refusal is the whole
   point of the Apply button, so read it as a player would: does it tell you what to do next?
6. **Force a stop rather than a refusal.** The same contradiction can be reached from the other side —
   configure both buses to disagree by editing the export bus after the import bus. Both should stop: grey
   sprites, one chat line each, and a reason on hover. Check the grey reads as "stopped" and not as a
   different object, and that the chat line is legible without the panel open.
7. **Walk away and open the terminal.** It should show a red "2 stopped at x,y" banner on the category row.
   This is the only surface that finds a problem for a player who was elsewhere when it happened, so it
   matters that it is noticeable without being alarming.
8. **Fix one of the rules.** Both devices should come back to life and the banner clear.
9. **Break the chest a bus is pointing at, then put it back.** The bus should say it has no container within
   about a second, and start working again about a second after the chest returns. A vanilla chest cannot
   notify us of anything, so this is the one thing still on a heartbeat.
10. **A larger network, if there is time.** Several units, a few conduits, two or three buses. Nothing should
    be doing anything measurable while nothing is happening, and the game should not stutter when a settler
    drops a haul into a bussed chest.

Fixed after the first attempt at this script, so start again from step 2:

- **The Apply button was inert** — no hover, no click, on both bus types. It sat inside the filter list's
  rectangle, and `FormContentBox` claims the mouse anywhere in its rectangle once clicked, because clicking a
  component raises the priority key that the event loop sorts on. So it worked until the list was first
  touched, which is every path to it. The list now stops short of a reserved strip. It is also no longer
  disabled when there is nothing to send, because a disabled button looks exactly like that bug.

Known and deliberate, so not bugs:

- The stopped sprites are luminance-weighted desaturations of the current placeholder art, generated in a
  few lines of Python. They need regenerating when real art lands.
- A device stopped for churn resumes on its own within thirty seconds. If whatever was undoing its work is
  still there, it stops again after about forty moves. That is intended: a stop that could only be lifted by
  editing a rule would need the player to work out what to edit.
- The commits for this work are unsigned. The signing key needs a passphrase typed by a person.

### Fixed since his last pass, so worth a look first

1. **Tickbox panels no longer overflow.** The cause was not width alone: `FormCheckBox.setText`
   *wraps* at its max width rather than truncating, so at 106px "Demonic Workstation" became two lines
   inside a 24px panel. Panels are 156x32 now, four per row. Check the long names
   ("Caveglow Alchemy Table") wrap inside their panel rather than past it.
2. **Source labels come from the station's item, not the tech.** His log had
   `Translation of tech.transmutation is not found` — a gap in *the game's own* locale, which has 26
   `tech` entries and no transmutation. A tech's `itemStringID` points at the station that provides it,
   whose name is complete, and is also the name on the item in your inventory. So a Transmutation
   Station should now read "Transmutation Station" rather than a raw key.
3. **The 408px/412px warning in his log was my check being wrong, not the layout.**
   `FormFlow.next(add)` returns the position *before* advancing, so the check compared the last row's
   start against the total. The layout was always right; the warning should now be silent, and if it
   ever fires again it means something real.
4. **Fueled stations refuse installation** — try a Forge; it should simply not go in, and the Stations
   tab text should say why.

### Phase 5's buses — new Aug 2026, none of it seen

The mechanics are covered headlessly (`tests/python/test_buses.py`); everything here is what the harness
cannot reach. `harness run` needs `make run HARNESS=1`; both buses cost 4 logs and 2 iron bars.

13. **Both buses render and are told apart.** The sprites are **placeholders** — the Storage Unit's own
    sprite tinted green for import and amber for export by `tools/tint_sprite.py`. They will read as
    "unit in a different colour", which is enough to test with and not enough to ship. Real art is
    requested in SPRITES.md.
14. **Placing one reports what it sees.** Interact with a bus: it should name how many units it is on,
    whether a container is attached, and its rules. Placing a bus with no chest beside it should say so
    — that is the most likely mistake and the message is the only feedback.
15. **An import bus actually drains a chest over time**, about one stack a second, and a conduit next to
    a bus should draw as joined to it.
16. **A settler depositing into a bussed chest appears in the terminal** — the whole point of the
    indirection. Needs a settlement, so it cannot be tested headlessly.
17. **A Shipping Chest sells what an export bus sends it.** Vanilla's behaviour above a stack threshold,
    via trader missions, so this is a check that the two features compose rather than that either works.
18. **The rule panel — filters, amounts and persistence confirmed in game by Elias (12 Aug).** What is left: The panel opens and
    its edits reach the server, but the client appears to start from an empty filter, so an edit can
    unset everything that was ticked and overwrite the server's copy. Treat a bus's rules as unsafe until
    that is fixed; the transfer mechanics are unaffected. What is left to check once it is fixed: Right-click a bus: it
    should open the same panel as "configure storage" on a settlement chest, because it is that panel —
    `ItemCategoriesFilterForm`, with the category tree, per-item numbers and search. What to check, in
    order of how likely it is to be wrong:
    - it opens at all, and the two header lines fit the 340px width without overlapping the dropdown;
    - the content box scrolls and the categories collapse and stay collapsed when reopened;
    - ticking an item and typing a number changes what the bus does — the network should end up holding
      that many, filling on an import bus and draining on an export bus;
    - the limit-mode dropdown's four options behave (the two per-item ones are honoured, the two
      whole-container ones are deliberately ignored by a bus, which may deserve hiding them instead);
    - closing and reopening shows what was set, and a second player opening the same bus sees it too.

    Everything under that panel is covered headlessly, including the filter's packet round trip through
    the container action, so a failure here is a *drawing* or *layout* failure. That is the whole reason
    this list exists.

### Never drawn at all

5. The **Stations tab**: ten slots in a row, the help text unclipped, one bench per slot, a stack of
   two refused.
6. Installing a bench with the Crafting tab open should add its recipes **without reopening**;
   uninstalling should remove them and reset the source tickboxes.
7. A **Demonic Workstation should contribute two tickboxes**, and its Workstation recipes should be
   craftable with only it installed — tiering coming free.
8. **Breaking a terminal with benches installed should drop them.**
9. **Collapsible sections**: headers show a count, click to collapse, and unticking "Group by
   category" returns the flat grid.
10. The **capacity bar's four colours** — thresholds at 25/50/75, and the number beside the bar stays
    neutral by design. `harness run capacity_steps` starts you at 40/160; `harness run full_network`
    is the red end.
11. The **click conventions** in the storage grid: left click with a held stack deposits anywhere
    including empty space, right click takes half a stack, right click while holding deposits one, and
    non-stackables behave the same on both buttons.

### The harness in a play session — a warning, and now a use

The harness was loading into your play sessions, and with it enabled the client's **keyboard** died
once the world started hosting — mouse fine, server ticking normally, nothing in any log. It is now
dormant in a client process, so leaving it installed is safe, and it stays dormant unless you ask for
it. Do not disable it in the game's Mods menu: that stops the Python suite from running, by design. To
be beyond suspicion entirely, `make -C ../necesse-headless-harness uninstall`.

**Asking for it is now worth doing**, because several checks below are minutes of clicking to set up
and one line to script. `make run HARNESS=1` enables the `harness` chat command in your own world, and
`tests/scenes/` holds two ready scenes:

- `harness run full_network` — 64 units, every slot full. Covers item 10's red bar and the
  large-network stutter check in one go.
- `harness run capacity_steps` — a 160-slot network at 40 used, so you can deposit and watch the bar
  cross 50% and 75%.

Coordinates are relative to the world spawn tile, so stand near spawn. Both scenes use world verbs
only. Anything player-coupled (`open`, `install`, `give`, `click`) spawns the harness's synthetic
player, which has never been exercised inside a live client — drive those parts with your own
character, and if you try it anyway, treat anything odd as the harness rather than the mod.

Also worth knowing: if the keyboard hang recurs *with* `HARNESS=1`, that is real evidence about a thing
I never explained. Without the flag, both of the harness's always-on behaviours are off, so a recurrence
then would point at the class transformation itself.

### The multiplayer lighting question — now answerable without two clients

He could not verify that a terminal looks in use to *other* players, because Steam refuses the same
account twice: his log shows `Client "Tester" was already playing` followed by a disconnect. That was
neither the mod nor the harness.

**The server-side half is now tested headlessly** (`tests/python/test_in_use.py`): a terminal reports
one user while open, still reports it after three seconds, and reports none after closing. The middle
one is the test that matters, because `OEUsers` entries expire after two seconds unless the container
re-asserts them — a container that asserted once would look open briefly and go dark while still open,
which is a bug you would only ever see as the *second* player. The terminal re-asserts every tick,
exactly as vanilla's chest container does, and the state rides along in the object entity's content
packet, so other clients render from it.

What is left is purely the sprite. **Two clients on this machine:** `make run` to host and open the
world to LAN, then `make dev` in another terminal. `-dev 1` sets dev mode *and* a temporary auth of 1,
so the second client is a different identity; the host must also be in dev mode, because
`PacketConnectRequest` rejects auths of 500 or less otherwise — `make run` passes `-dev`, so it is.

12. With both clients connected, one opens the terminal and **the other should see it lit**, and see it
    go dark when the first closes it.

## Deferred, with reasons

- The **storage unit's lighting revision** — it must never look openable. Still owed.
- The **six unused category icons** — a decision, not a bug.

---

# The standing checklists, unchanged

These predate the round-by-round notes above and are still open. Kept verbatim rather than
rewritten, because several are the only record of a specific thing to try.

## Rendering and client-only code

The headless server never loads textures and never runs draw code, so this whole class is
invisible to the harness. It is also the quickest to check by eye.

Real sprites are now installed, so all four of these are live rather than waiting on art.

- [x] **All three objects render.** *Confirmed — sprites and inventory icons.* Terminal, unit and conduit in the world, plus their
      inventory icons. None of this draw code has ever executed.
- [ ] **The terminal stands two tiles tall** and its foot sits on its own tile rather than
      floating, since the sprite is 32×64 and bottom-anchored.
- [x] **Conduits draw the shape their neighbours call for.** *Confirmed in game — all sixteen.* With the current four-frame sheet
      only straights exist, so a run that turns still shows two straights meeting — that is
      expected until the 16-frame sheet requested in SPRITES.md arrives. What to check now is
      that straight runs join with no visible seam, and that the sprite switches axis as you
      face up/down versus left/right. The mask convention behind the eventual elbows and tees is
      already asserted headlessly in `tests/python/test_conduits.py`.
- [x] **The terminal screen lights up while open** and goes dark when closed — *confirmed for
      yourself; the server-side state behind it is now covered by `tests/python/test_in_use.py`,
      and only what a **second** player sees is still unverified (item 12 above)*
      (`arcanestorageterminal_open.png`, swapped by `isInUse()`).
- [ ] **A run of units reads as one wall**, with the intended notches where four corners meet
      rather than looking like a mistake.

## Phase 3 interface — new, none of it exercised yet

The whole of Phase 3 so far is client-side or player-coupled, so the harness reaches only the
numbers behind it. `tests/python/test_network.py` covers the capacity accounting headlessly;
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
      `tests/python/test_transfers.py`.
- [ ] **Deposit all leaves locked slots alone.** Not covered: no test can lock a slot,
      because locking is a client-side interaction and nothing headless can set one yet.
- [ ] **The three buttons are present, labelled and clickable.** The behaviour behind them is
      asserted; that they are drawn at all is not.
- [ ] **Sort cycles and the tooltip says which mode is active.** Group order should match what
      the inventory-sort button produces on your own inventory, since it is literally the same
      comparator. Name is A-Z, amount is most-numerous first.
- [ ] **A large network does not stutter.** `harness run full_network` builds the 64-unit ceiling
      for you; then open the terminal. Two known costs were removed unmeasured -- aggregation was
      quadratic in distinct items, and the draw path aggregated three times per frame -- so this
      needs a real network to confirm rather than an argument.
- [ ] **The layout survives a small window.** The header now holds a title and a search box, and
      the footer a capacity label and three controls, so there is more to collide than before.

## Container lifecycle

The parts most likely to lose or duplicate items, which is the one failure class that cannot be
recovered from a save.

- [x] ~~**The round trip** -- open, withdraw, deposit, close, and item conservation at every
      step.~~ Asserted headlessly by `tests/python/test_network.py`. It was described here
      as the single highest-value check in this file, which is why it was worth automating first.
- [ ] **The six click conventions individually**, via `harness click <slot> <action>`:
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
      broken unit or conduit is asserted in `tests/python/test_topology.py` and
      `tests/python/test_conduits.py`.

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

