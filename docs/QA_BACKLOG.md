# QA backlog

**How to read this file.** It is a backlog, not a log: the top is what still needs eyes, and
confirmations are collapsed into one list rather than kept as prose. Elias runs every visual test;
nothing here is marked confirmed unless he said so.

Restructured 11 Aug 2026 because it had grown into a diary and he was right that it was stale.
Revised again the same evening: the bash scenario suite it kept citing no longer exists, so every
reference now points at the pytest file that owns the assertion. See `docs/TESTING.md`.

## Needs eyes — the wireless terminal (Aug 2026)

**0a. The click path, which no test touches.** *(Pairing, opening, depositing items and installing benches are
all confirmed working in game. Withdrawing crashed the client and is fixed but not re-tested; so is the Logistics
tab, which was blank and now mirrors the bus list. Both want a look.)*

Worth knowing what these two had in common, since it is a trap rather than a mistake: **a container action's body
runs on the clicking client as well as the server**, and on a remote client there is no terminal object entity to
reach through. Three bugs have come from that now. `TerminalNullGuardTest` catches the shape from here on --
verified by reintroducing the crash and watching it fail -- but nothing headless can catch the behaviour, because
the Python suite has no client at all. `tests/python/test_wireless_terminal.py` binds the item directly,
because what a click adds is `ItemInteractAction` plumbing rather than anything the feature is about. So this is
unproven: holding the item and clicking a placed Storage Terminal should pair it and say so, and clicking anywhere
else should open the network. `overridesObjectInteract` returns true, which is what stops the click opening the
terminal normally instead — if pairing does nothing, that is the first thing to suspect.

**0b. The case the feature exists for.** *(Confirmed working in game across levels, with and without loading,
including unload, withdraw, save and relog. The region half is now covered by tests as well, since the harness
grew verbs to force an unload -- so what remains here is only the cross-level, level-unloaded path, which one
player on one level cannot reach.)* The harness has one level and one player, so nothing tests a network on
an unloaded level. Worth doing properly: pair on the surface, go down to a cave, wait past 30 seconds so the
surface unloads (the server logs `Unloaded level`), then open it. Expect the level to load, the contents to be
right, and — after closing — the level to unload again about 30 seconds later. Depositing something and then
walking back to check it is really there is the test that matters.

**0c. A sprite is missing.** `items/arcanestoragewirelessterminal.png`, 32x32. It shows the pink `[ER]`
placeholder until it exists. Nothing else about the item is blocked on it.

**0d. Bandwidth, if a large network ever feels slow to open.** The mirror sends every non-empty slot the first
time and only changes afterwards. A full Fallen network is over a thousand stacks, which has never been tried.

## Confirmed in game

**The reworked rule panel and logistics tab, 14 Aug 2026.** *"UI looks good, works as specified earlier."*
All four changes verified: the name-first panel with a status line that costs nothing until there is
something to say and grows the window when it wraps; stopped devices as flow-wrapped name boxes with the
reason on hover and a click that selects the device; the Copy button; and the terminal's rules pane
scrolling as a whole. The layout reservations are gone for good and the two arithmetic faults found before
this pass -- growth measured from the wrong origin, and a double-counted offset in the host-scrolled reflow
-- were fixed before it, so neither was ever seen.

Also confirmed in the same pass: the grey stopped sprite, the chat line's wording, the terminal's red
banner, and that the whole thing feels prompt now that work follows changes rather than a timer.

**One thing the pass surfaced that is an art problem rather than a UI one.** The grey stopped sprite works,
but a stopped import bus and a stopped export bus are then nearly the same picture -- measured, their
outlines are pixel-identical and desaturation removes the green/amber split that was carrying direction. A
silhouette fix is requested in `SPRITES.md`.

**The transfer resolver, 14 Aug 2026.** All ten steps of the script below were run and behave as written --
the resolver, the Apply transaction, the refusal, the two-sided stop, the terminal's banner, the recovery, and
the broken-container heartbeat. Phase 5b is confirmed end to end.

**Phase 5b, 13 Aug 2026.** The Apply button works once it is outside the filter list's rectangle. A rule set that
contradicts a bus sharing the same container is refused when applied, and the soft-fail path -- the device
stopping rather than churning -- was seen to detect the feedback-loop case correctly.

Two faults found in the same pass and fixed: the stopped-reason line reserved no vertical space and was not
wrapping, so it drew over the amount row; and the amount was labelled "Amount of each", which does not say that
an export bus's number is a floor held by the network rather than a target for the chest beside it.

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

### The reworked rule panel and logistics tab — CONFIRMED 14 Aug 2026

Moved to "Confirmed in game" above. The four-point script is kept there in summary; the one thing it
surfaced that is still open is the stopped buses being hard to tell apart, which is an art request rather
than a QA item.

### The transfer resolver — Phase 5b, built while Elias was away, none of it seen in game

**Confirmed 14 Aug 2026 — all ten steps behave as written.** Kept because it is the script to re-run whenever
the resolver or the bus panel changes, and the UI rework above changes what steps 2, 5 and 7 look like.

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

### The logistics tab — confirmed working 13–14 Aug, except the reworked parts above

Elias's words on the first pass: *"working nicely... correctly turns them red when there is an issue and they
are disabled."* Steps 1–8 below were exercised during the ten-step resolver pass. Steps 1, 2, 4 and 5 are the
ones the 14 Aug rework changed, so re-read them against the reworked list at the top of this section.

1. **With nothing wrong**, the issues panel should be absent rather than an empty red box, and the line should
   read that every device is working. The device list on the left should name both buses with their
   coordinates.
2. **Pick a device.** Its rules should appear on the right, briefly showing "Reading this device's rules" while
   they are fetched. *The pane now scrolls as a whole, so the editor is no longer squeezed into it.*
3. **Change something and Apply.** It should take effect exactly as it does from the bus's own panel.
4. **Make a contradiction from here**: give the export bus a floor, then try to give the import bus a higher
   ceiling for the same item. The refusal should appear *in the editor's status line, under the name row*.
5. **Now let a contradiction stand** so both buses stop. *Both should appear as red name boxes with the reason
   on hover*, and both rows in the list should turn red. This is the thing the storage tab's one-line banner
   could not do, so it is the point of the whole tab -- judge whether it reads as informative rather than
   alarming.
6. **Check the storage tab's banner** still says the count and now points here.
7. **Break a bus while its rules are open** in the pane. The pane should fall back to the "pick a device" hint
   rather than showing rules for something that no longer exists, and the list should lose the row.
8. **A bigger network**, if convenient: five or six buses. The list scrolls; the pane does not move.

### Bus names — new, none of it seen

1. **Open a bus.** The top row should be its name, in an editable box, where the type heading used to be. Judge
   whether it reads as a title rather than as a stray input.
2. **Place three import buses and one export bus.** They should be Import Bus #1, #2, #3 and Export Bus #1 --
   the two directions count separately.
3. **Rename one** to something like "Grain Import", from the bus and again from the logistics tab. Both routes
   should work and the new name should appear in the tab's row, in the bus's panel, and in any stopped-device
   reason that mentions it.
4. **Clear the box.** It should go back to its assigned number rather than becoming blank.
5. **Save and reload the world.** Names and numbers should come back. This is the one thing here no test covers:
   the harness cannot reload a world.
6. **Break a numbered bus and place a new one.** The survivors should keep their numbers and the new one should
   take a number nobody is using. Tested headlessly, but worth one look.
7. **Cause a conflict** and read the reason. It should name the other device rather than only giving its
   coordinates.

### The world marker — new, none of it seen

1. **Pick a device in the logistics tab** and look at the world behind the panel. The bus's tile should have a
   pulsing blue outline, about once a second.
2. **Switch to another tab.** The marker should go, since it points at something no longer on screen.
3. **Change the selection.** The marker should follow it.
4. **Close the terminal.** No marker should be left behind. Reopen and check it comes back.
5. **Judge the pulse.** It is meant to catch the eye without competing with a panel being read; say if it is too
   fast, too slow, or too bright.
6. **A bus off screen.** The marker is drawn at the tile, so a device out of view has no visible marker. Worth
   deciding whether that needs an edge-of-screen pointer, which is a bigger piece of work.

Known and deliberate, so not bugs:

- The stopped sprites are luminance-weighted desaturations of the current placeholder art, generated in a
  few lines of Python. They need regenerating when real art lands.
- A device stopped for churn resumes on its own within thirty seconds. If whatever was undoing its work is
  still there, it stops again after about forty moves. That is intended: a stop that could only be lifted by
  editing a rule would need the player to work out what to edit.
- The commits for this work are unsigned. The signing key needs a passphrase typed by a person.
- A bus placed with nothing to connect to has no number until it joins a network, and shows its plain object
  name until then. A number means a position within a network, so there is nothing to number against.
- The red issues panel wraps to four lines. Enough stopped devices will overflow it; the device list is still
  complete and still red per row.
- The logistics tab shows one device's rules at a time rather than a column of expanding rows. That is a
  departure from what was asked for, and the reason is that the editor contains a scrolling category tree:
  nesting one inside each row of another scrolling list means two nested scroll regions, and a content box
  claims the mouse over its whole rectangle once clicked -- which is exactly how the Apply button came to be
  inert. Say if the expanding-row shape is worth the risk and it can be changed.

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

## A separate logistics terminal — a balancing idea, recorded not decided

Raised while looking at the logistics tab: rather than a tab on the storage terminal, logistics could be its own
**Logistics Terminal**, and placing a bus could *require* one on the network. The appeal is gamefeel — the
network gains a visible control room, buses become a deliberate step rather than something available from the
first terminal, and the tab stops competing for room with storage and crafting.

This is noted as cutting against an earlier decision, which is why it is written down rather than acted on. The
crafting interface was made a tab and not a second block, deliberately, and the reasoning is in ROADMAP Phase 3:
Magic Storage's separate Crafting Access blocks are a consequence of Terraria's UI, and one terminal with tabs is
fewer objects to craft, place and keep in sync.

The case for treating logistics differently is real and worth stating fairly:

- **Crafting is a view; logistics is authority.** The crafting tab shows what the network can already make. The
  logistics tab changes what the network *does*, to devices elsewhere on the level. Gating that behind an object
  a player chose to build is a different proposition from gating a view.
- **It is a natural place to hang a cost.** A prerequisite object gives automation a price and a tier, which the
  progression design wants anyway, and it is more legible than making each bus expensive.
- **It removes a tab from a panel that will keep growing.** Storage, crafting, stations and logistics is already
  four.

Against it:

- **Two terminals to keep in sync**, and a second thing to craft and place before anything works.
- **A player who has built buses and then breaks the terminal** has a network doing things with no way to see or
  change them, which needs a rule of its own.
- **Discoverability**: a tab is found by opening what you already have.

Nothing is blocked either way. The tab exists and works, and the editor it uses is a shared component built into
whatever form asks for one -- so moving it into its own container later is a change of host, not a rewrite.

## Deferred, with reasons

- The **storage unit's lighting revision** — it must never look openable. Still owed.
- The **six unused category icons** — a decision, not a bug.
- **The logistics tab's world marker** (pulsing tile outline for the selected bus) — disabled,
  13 Aug 2026, after too much time against too small a feature. The call site in
  `StorageTerminalContainerForm.draw()` is commented out; `drawWorldMarker()` and its diagnostics
  are left in place.

  What was actually wrong, found by reading `FormShader` rather than guessing: every `Form.draw()`
  pushes a shader state carrying a screen offset and a clip rectangle, and pops it when that form
  finishes. `startState` *intersects* its rectangle with whatever was already active rather than
  replacing it. The marker was drawn from the switcher's own `draw()`, outside any form's state,
  so it silently inherited whatever small clip box some other component had left active a moment
  earlier — no exception, because clipping isn't an error. That is why two full rounds of
  coordinate-math fixes and exception-hunting both came back clean: neither was where the bug was.

  The fix believed correct but never confirmed in game: push an explicit shader state before
  drawing — offset `(0,0)`, clip rectangle the full hud buffer, mirroring what `Form.draw()`
  itself does — and pop it after, via `GameResources.formShader.startState(...)` /
  `state.end()`. This compiled and passed the full test suite but was never seen to actually
  render; the feature was cut for time before that last check happened.

  If revisited: rebuild with the call site uncommented, open the logistics tab, select a bus, and
  look. Note that the UI rework of 14 Aug removed the block in `updateLogistics` that set `markerX`
  and `markerY`, since nothing read them any more — so re-enabling needs that restored too, or the
  method returns at its own first guard. If it still does not render, the diagnostic logging already
  in `drawWorldMarker()` (an unconditional per-frame trace, a guard-failure trace, and a try/catch
  around the draw calls) should be the starting point rather than new theories — three rounds of
  plausible-sounding coordinate/lifecycle theories were all wrong or unconfirmed, and empirical
  tracing is what finally found the real mechanism.

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

## What the faster test suite exposed — one failure left, and two real bugs fixed

The Python suite detaches game time from the wall clock: it grants ticks instead of waiting for them, which
took it from 333 seconds to 20. That removed something the suite had been getting for free. Every harness
command used to be marshalled onto the server thread and wait for the next tick, so a command cost 50ms and
a fixture's seven placements spanned seven ticks — meaning anything the engine defers to a tick was always
processed before the next test's first placement. Removing that free settling surfaced whatever relied on it.

**Two were real bugs and are fixed.**

`BusObjectEntity.assignOrdinal` counted peers that had been removed but not yet swept out of
`entityManager`, because engine entity removal is deferred. A bus placed in the same tick as one was broken
therefore took the number after it, and a network holding a single import bus could call it "Import Bus #2".
Reachable in play by breaking and rebuilding within a tick.

The `storage` fixture cleared state and then ticked, which is backwards: `reset` drops the network indexes and
*then* removes the objects, so ticking afterwards let devices that had not gone yet rebuild the indexes reset
had just dropped. It now settles, resets, then settles again.

**The two remaining failures were diagnosed to a single cause and are now marked `xfail`.** The suite is
green across four consecutive runs. Neither is a fault in the mod, and the evidence for that is direct rather
than inferred.

The diagnosis, from logging inside the running server rather than from reasoning about it: **more than one
`BusObjectEntity` gets registered at the same tile over the course of a run.** The bus that actually ticks
behaves correctly — it was logged reaching `NO_CONTAINER` on its heartbeat, with `container=false`, exactly as
designed — but by the time the assertion's query runs, a *different* instance is what
`entityManager.getObjectEntity` returns for that tile, on the same `Level`, and that instance has never
ticked, so it still holds the initial `ACTIVE`. Its identity hash never appears in the tick log at all.

The engine does not create entities on read: `getObjectEntity` is `objectEntities.get(x, y, false)`. So
something in the harness's place/reset cycle re-registers the tile between the last granted tick and the
query. That is where the fix belongs. It takes about seven tests' worth of churn to appear, which is why
removing the free settling exposed it and why no single predecessor test reproduces it — every one of the six
is required, and removing any of them cures it.

The bus-numbering failure is the same artifact reached from the other side: an ordinal derived from the device
list can count an instance no reader will ever see.

**Two dead ends, recorded so they are not repeated.** Filtering `removed()` out of the scheduler's device list
does not fix either failure, because a displaced entity reports `removed() == false` — the flag is not a
reliable staleness signal, which is worth knowing independently. Nor does revalidating on membership change.
Both were kept anyway, on their own merits, and both are described below.

**Two robustness fixes were kept, and they are honestly unrelated to the test failures.** Neither made a
failing test pass; both close real holes found while looking.

`NetworkIndexes.isCurrent` asks whether an entity is still the one its level holds at its own tile, and the
scheduler's device list now filters on it. The hole it closes is leader election: leadership is the lowest tile
order, so a stale device could elect itself, and nothing drives a scheduler on behalf of an entity no reader
can see — no heartbeat, no revalidation, no transfers, until the next topology change. For a player that reads
as a network that quietly died and then healed itself when they touched something unrelated.

A device joining an existing network now triggers revalidation instead of waiting for that network's heartbeat.
The delay was up to a second, and it mattered because a device's initial state is `ACTIVE`, which permits work
— so a bus placed onto an established network could move items before anything checked whether it should,
including against a rule conflict it would have been stopped for.

**Ruled out by measurement, so it is not re-investigated:** not the frame rate (the same tests failed
identically at 20 and 200 frames a second), not the mod's clock (`worldEntity.getGameTicks()` increments in
`WorldEntity.serverTick`, not `frameTick`, so it stays honest while time is frozen), not pytest ordering
(`pytest-randomly` is not installed), and not solved by granting more settling ticks — which is what made
contamination the likelier explanation than mis-calibrated expectations.

`make pytest-clock` runs everything on the game's own clock and is the control: what passes there and fails
by default is tick-granularity dependent.

## Station Units — needs eyes, nothing here is confirmed in game

Sockets moved off the terminal onto a placed Station Unit. Compiles, and 9 new headless tests plus the 40
existing station and crafting tests pass, but **no part of this has been seen in a running game**, and the
parts most likely to be wrong are the parts a headless test cannot see.

1. **The migration, which is the only item here that can lose a player's property.** A terminal in an
   existing world holds up to ten benches in its own inventory; on the first server tick after loading, those
   are dropped as pickups at the terminal's tile and the slots cleared. *Elias has benches installed in his
   current world*, so this runs the first time that world loads. Check: the benches appear on the floor, the
   count matches what was installed, and nothing is left behind — reopening the terminal must not show them
   still installed. A log line under `Arcane Storage: dropped N crafting station(s)` records it.
2. **The Stations tab with no Station Unit.** Should show the explanatory line, not an empty panel or a row
   of dead slots.
3. **The Stations tab with one, then several.** Sockets wrap onto rows at the form's width; one unit is one
   socket at the base tier.
4. **Installing a bench by dragging it into a socket**, and the Crafting tab gaining that bench's recipes.
5. **Breaking a Station Unit with a bench installed** — the bench should drop, and the recipes should go.
6. **Interacting with a Station Unit** reports `<used>/<total> stations installed` and does not open a
   container.
7. **A Station Unit added to settlement storage.** It refuses to be settlement storage at all
   (`getSettlementStorage` returns null), so a hauler must not walk off with an installed bench. Worth
   testing precisely because the failure mode is silent and slow.
8. **Quick-stack inside the terminal** must not file loose items into sockets — the three bulk conventions
   are refused on the unit rather than merely rejected per item, so the buttons should ignore sockets
   entirely rather than appearing to do nothing.

## The custom panel — needs eyes

The terminal's four tabs and the bus rules panel now draw on `ui/arcanestoragepanel`. Geometry was verified
by reconstructing a panel from the slices, but **the engine's own nine-slice reader has never drawn it**, and
that is the only thing that matters.

1. **The frame sits on the form's edge**, with no 8px bleed outside it on any side. This is the measurement
   most likely to be wrong: the visible band occupies the inner third of each 12px slice and the outer 8px is
   transparent, which is supposed to cancel exactly against `edgeMargin = 8`.
2. **All four corners** are closed, with no gap or doubled pixel.
3. **The centre tiles seamlessly** across a tall form — the weave period is 16 and the tile is 64, so it
   should divide evenly.
4. **Every tab and the bus panel** carry it. A single tab left on the vanilla background is the likely
   mistake, since the background is set per tab.
5. **`useCustomPanel = false`** in the mod's settings restores the player's chosen interface style
   everywhere.
6. **Content is not shifted.** `contentPadding` is 0 to match the vanilla form; if anything looks nudged,
   that is the suspect.

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



## The tier ladders — needs eyes

Implemented and covered by 17 headless tests, but nothing below can be seen headlessly.

1. ~~**The three tiers must be distinguishable at a glance on a real tile.**~~ **Done Aug 2026** — placed
   and checked; the tiers read apart under game lighting, and no object showed the pink `[ER]` placeholder.
   The specific worry was Tungsten against Fallen, both desaturated and at identical luminance by
   construction, which is the pairing that survives an editor and converges in play. It did not.
2. **The recipes appear at the right stations and are actually reachable.** Headless tests place objects
   directly and so never exercise a recipe at all. Check each rung shows at its era's Workstation, that the
   ingredients are the intended ones, and specifically that **the rung below is consumed** — that is the
   design's whole claim and a wrong ingredient list would still compile and still register.
3. **A 320-stack unit in the terminal view.** Capacity numbers were only ever asserted as integers. Sixty-four
   Fallen units is 20,480 slots against 2,560 at base, which is eight times the aggregation the interface has
   ever been asked to draw. Performance targets are deliberately deferred, but this is the first change that
   makes the deferred number eight times larger, so it is worth one look at whether the view stays usable.
4. **Mixed-tier networks read correctly in the interface.** Tested numerically, and capacity growth between
   tiers is confirmed in game. Still unverified that the tab shows sensible *names* when tiers are mixed,
   which is the normal state during an upgrade.
5. **Eight sockets of installed benches**, laid out. `buildStationsTab` wraps sockets onto rows computed from
   the form width; one socket has been seen working and eight has not.


## The unit upgrade panel — only eyes can check this

The operation underneath is covered by 21 headless tests, including that nothing is lost. The panel is not
coverable at all: forms are client-side and the harness has no client, so layout, colour and the button's
disabled state have never been rendered once.

0. ~~**The Upgrade button threw a ClassCastException.**~~ **Fixed Aug 2026, needs one confirmation.**
   `executePacket` ran on the clicking client as well as the server, where `getServerClient()` is an
   unconditional cast. The upgrade itself worked -- the packet is sent before the local call, so the server did
   the work and closed the panel -- and only the client logged an error, but the panel's own close never ran on
   that side. Worth one click per ladder to confirm the panel now closes cleanly and the log is silent. A JUnit
   lint test now fails the build if any `executePacket` calls `getServerClient()` without an `isServer` check,
   since no Python test can reach that branch.

0b. **The upgraded object now changes in the world -- confirm it does.** Fixed Aug 2026: `setObject` alters the
   server's world only and sends nothing, because its usual callers are placement paths where the client already
   predicted the change. Nothing predicted this one, so capacity grew while the texture, the name and the panel's
   own tier stayed a rung behind -- the entity was right and the world was wrong. A `PacketChangeObject` now goes
   to every client that can see the tile, following `LadderDownObjectEntity`. Check the sprite, the name and that
   reopening the panel offers the *next* rung. **This was the third bug to hide in the no-client gap**, after the
   button's missing side guard and the bus panel's double-wrapped filter; a new headless test pins the
   server-side half, and nothing can pin the rest.

0d. **The cost rows should now sit clear of the button.** Two attempts, and the second is the one that matches
   vanilla. The rows are `FormFairTypeLabel`s whose text was only set in `draw()`, and `FormFlow.nextY` advances by
   the bounding box a component has *at construction* -- zero for an empty one -- so every row landed at the same y
   with the button on top. Reserving a fixed 26 px slot fixed the stacking but not the overlap, because a row is not
   a line of text: it contains a 32 px item icon, so no constant chosen here was going to be right. Each row now
   gets its real text before being laid out, exactly as `UpgradeStationContainerForm` does, and the flow measures
   it. Check each rung -- one material for base to Demonic, two above it, no button on Fallen -- and that no icon
   is clipped top or bottom.

0e. **Legibility is now the background's job, not the text colour's.** A near-opaque dark quad is drawn over the
   panel's centre, inset 4 px so the purple frame still reads as a border, and all text is plain white. The earlier
   attempt took the interface style's active colour, which is theme-correct and still hard to read, because the
   panel is the mod's own art and darker than the one those colours are chosen for. Only the "not enough" colour
   still comes from the style. Check it against a non-default theme, and check the frame still looks intentional
   rather than like a border around a black box.

0c. **Panel size and legibility.** Doubled to 440x208 with larger fonts, and every label now takes its colour
   from the interface style. Unstyled labels default to near-black, and this form draws on the mod's deep purple
   panel rather than the engine's lighter default, so the text was there and correctly laid out and unreadable.
   Worth checking against a non-default interface theme, since the colour is no longer fixed.

1. **Right-click a unit of each tier.** The panel replaced a chat message, so the regression to watch for is
   nothing appearing at all. A Fallen unit should show "Already at the highest tier" and **no button**, which is
   a different code path from the other three.
2. **The requirement rows colour correctly.** Drawn with `Ingredient.getTooltipText`, the same call vanilla's
   Upgrade Station uses, so a met requirement takes the interface's active colour and an unmet one its error
   colour. Worth confirming against a vanilla crafting cost side by side — if they disagree, the wrong colour
   pair is being passed.
3. **The button greys out and un-greys without reopening.** This is the whole point of pushing rather than
   polling: deposit the last missing bar into a chest on the network from a *second* client, and the first
   client's button should enable on its own. Equally, spend the materials elsewhere and it should grey out.
4. **The used/total figure tracks deposits live**, for the same reason.
5. **Two clients on the same unit.** Both should update, since the event is sent per client with a container
   open on that tile.
6. **An open terminal during an upgrade.** Known-suspect: the terminal's container holds slot references to the
   unit inventories, and an upgrade replaces the object entity underneath it. The panel closes itself on success,
   but a terminal open at the same moment may show stale slots until reopened. Not yet tested either way.
7. **Panel width against long item names.** Rows are centred and wrapped at 204px; `alchemyshard` with a
   five-digit held count is about the worst case.
