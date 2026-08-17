# Steam Workshop release roadmap — v1.0.0

> **Uploaded 16 August 2026.** Workshop item `3784515578`, published from a dev-loaded session, held
> initially in Steam's automated content check as any new item with an external link is. Everything
> below is kept as the record of what was done and why, not as outstanding work. What is genuinely
> still open is in [Deferred](#deferred-and-not-blocking-100), plus the two post-release items under
> [Player-facing documentation](#player-facing-documentation-and-support-added-16-aug-2026).
>
> One thing the upload itself taught, which no amount of pre-flight checking had surfaced: the
> `preview.png` in this repository is not the file Steam receives. The game re-encodes the loaded
> texture with stb's PNG writer, which is far less efficient than the stored compression, so a 680KB
> source arrived as 1,126,429 bytes and was refused for exceeding one megabyte. It is now 800x400,
> and `ConventionsTest` bounds the projected encoded size rather than the file size, because the file
> size is the misleading number. See `git show 3b0bc4d`.

What has to be true before this mod is uploaded, in the order it should be done. Everything here is
either a player-visible gap, a convention the game documents, or a risk that only shows up on
someone else's machine.

Provenance follows the rest of this repo: `[verified]` = read in the decompiled source or exercised
here; `[open]` = not yet answered.

**Target version is 1.0.0.** The mod `id` (`elias.arcanestorage`) can never change after upload, so
it is frozen from here; the version is not. Save-data keys are effectively frozen too — see
[Save compatibility](#save-compatibility-starts-now).

## Corrections: three of these are partly built

Worth stating up front, because it changes what the work is.

**Sorting already exists.** `StorageTerminalContainerForm.SortMode` has three modes — `GROUP`,
`NAME`, `AMOUNT` — on a cycling `sortButton`. What is missing is *persistence*: `sortMode` is a
field on the form, so it resets every time the terminal is opened. So the deliverable is memory, not
sorting.

**An item-ID sort would be genuinely new, and probably not worth having.** `[verified]`
`Item.compareTo` sorts by `ItemCategory` first and then by **display name**, not by ID — so `GROUP`
is "category, then alphabetical" and nothing in the terminal currently orders by registry ID.
`item.getID()` exists and the comparator is three lines. But its one real benefit is clustering all
of one mod's content together, which is exactly what the [own category](#its-own-crafting-and-item-category)
work delivers properly. Recommendation: decide after the category lands, and expect the answer to be
no. A fourth state on a cycling button costs the player something too.

**Theme and recipe costs already persist.** `ArcaneStorageSettings` holds `theme` (default `SLATE`),
`groupCraftingByCategory`, the wireless ranges, the band ranges and channel counts, and a `CostTable`
of nested sections; all of it is written to and read from `<cfg>/mods/elias.arcanestorage.cfg`, and a
settings tab was added to the terminal in `0b26f9a`. So the model exists. The gaps are narrower and
different from "there are no settings" — see [Settings](#settings-the-real-gaps).

## Feature work

### Empty a storage unit into the rest of the network — **done**

Shipped: a button on the panel that already opens when a unit is clicked, present at every tier including the top,
since relocating a maxed-out unit is if anything the likelier case. Six scenarios in `test_unit_emptying.py`.

Worth recording because it changes how the feature reads: **breaking a full unit was never destructive.**
`StorageUnitObjectEntity` inherits `InventoryObjectEntity.getDroppedItems`, which returns every stack, so contents
land on the floor. This replaces up to forty item entities on the ground, in a game where anyone nearby can pick them
up — convenience, not rescue.

Every outcome reports itself in chat, including the ones where nothing moves, because a silent button is
indistinguishable from a broken one. The unit is excluded from its own target set by identity, which a test covers
directly: without it the operation would insert each stack back where it came from and report success.

One path is not covered by tests and is left for in-game eyes: `partial`, where the destination fills mid-move.
Filling a 40-slot unit needs forty distinct item kinds, and a test hardcoding forty string IDs breaks when any one is
renamed. The outcome is separated from `no_room` in the code and worded differently to the player.

### Persist the terminal's sort mode

Sorting itself now works and the active mode is visible on the button (`d5c8723`), so persistence is all that is
left of this. Note the bug that fix corrected: the comparators were always right and the engine discarded their
result, so anything written here about sorting before that commit was describing a feature that did not run.

- The chosen mode survives closing the terminal, changing level, and restarting the game.
- Per player, not per terminal or per world — the player is who has the preference.
- `[open]` Where it lives. The mod config is per-machine and not per-player, so a shared save would
  disagree with itself; player-attached data survives the right lifetime. Decide against
  `PlayerData`/GND before writing it.
- Filtering explicitly does **not** persist. A terminal that opens with a filter still applied looks
  empty and reads as broken.

### Stackable vs non-stackable filter

- A toggle cycling all / stackable only / non-stackable only, so gear can be found without wading
  through materials, and materials without wading through gear.
- Session-only, consistent with the search box, the craftable-only toggle and the source tickboxes,
  all of which are already transient by design.
- `[open]` Whether "non-stackable" is the right test for "gear". The engine's stack size is the cheap
  proxy and it is what the player sees; equipment type is the precise one. Check what
  `ItemCategory.equipmentManager` gives before assuming the proxy.

## Access points and base stations are not really on the network

**The largest known correctness gap, and a release blocker.** Observed in play: with storage joined to a network
through an Access Point and an Arcane Base Station,

- items in those storage units **do not appear** in the terminal's storage view;
- the capacity they add **is not counted** in slots across the network;
- a station unit reached through an access point **is not recognised**, and the Stations tab will not let stations
  be added regardless.

What does work: items inserted at the terminal still travel and land in the connected unit, and buses accept those
containers into their config panel — though whether the behaviour behind that is correct has not been established.

So the wireless path carries goods but is invisible to every read. That asymmetry is the diagnosis: something on
the write path resolves the far side and the read paths do not.

**The rule the fix must follow.** A wireless pair is a *connection mechanism* and nothing more. The path into a base
station branches to all of its channels exactly as though the far side were joined by conduits, so that nothing
downstream — the storage view, capacity, the stations tab, buses, crafting, the transfer resolver — has any idea a link
is involved. Each of those needing its own handling of "and also the wireless side" is how three of them ended up
without it.

**The cause, found and not guessed. It is not a missing `LinkLookup`.** All six network walks already pass
`BandIndex.linksOn`, so the earlier suspicion recorded here — that some walk omits it — was wrong. The fault is that
**membership is computed independently on each side**, and the client cannot compute it correctly even in principle:

1. `BandIndex extends LevelData`, and `LevelDataManager` is populated **only** from `loadSaveData`. The engine has no
   packet path for level data at all, so a client's `getLevelData` returns null, `BandIndex.existing` returns null,
   `linksOn` returns null, and a client-side walk cannot cross a link.
2. Syncing the link map would not be enough. **A client holds only the regions near its player** — the server sends
   entities per region through `ServerClient.addLoadedRegion` — so a silo 200 tiles away has no object entities on the
   client. A client walk that crossed the link would arrive and find nothing.
3. `StorageTerminalContainer`'s own comment records the assumption this breaks: "membership is discovered
   independently per side". That is sound only while the whole network is inside the client's loaded regions, which a
   wireless link violates by construction — being far away is the entire point of the feature.

Items still travel because the server moves them, and the server's view is correct. Every symptom is a **read** on the
client: the storage view, the capacity readout and the station tab are built from container slots the client registers
from its own walk. Bus config panels appear to work because each opens against its own local object entity with
server-sent content.

**So the fix is server-authoritative membership**, and the machinery already exists for the wireless terminal, which
hit this same wall first: `RemoteNetworkShape` sends unit sizes and socket counts in the open packet, the client builds
stand-ins, and `SlotMirrorEvent` keeps contents live from `IndexedInventories.slotChanged`. `StorageTerminalContainer`
already has the membership-taking constructor that accepts stand-ins, and the local open packet already has an
`extraContent` slot that is currently passed null.

Converging the local path onto that model is the fix that satisfies the rule above: one container, one shape, one
mirror, with "local" being the case where the terminal happens to be on your level. The tradeoff is that the local
path stops reading live entity inventories and reads mirrored snapshots instead, which costs a batched packet per tick
per open terminal — what the remote path already pays.

Acceptance: a unit behind an access point is indistinguishable from a unit behind a conduit, in the storage view, in
capacity, in the stations tab and in a bus's target set — asserted by taking existing scenarios and replacing a
conduit run with a link. **Note the scenario harness runs server-side, so it cannot catch this class of bug at all**;
these three symptoms were invisible to 258 passing tests. In-game verification is the only check that counts here.

### Implemented, awaiting in-game verification

Membership is now server-authoritative. `NetworkShape` travels in the terminal's open packet — the station and unit
tiles with their sizes, in the tile order that fixes every slot index — and the client uses it instead of walking.
Each entry is resolved against the client's own level where it can be, so a base with no wireless links behaves
exactly as before and keeps the engine's inventory sync; where it cannot, a `MirroredMember` stands in with an
inventory of the stated size.

Contents for stand-ins arrive by the mirror that already existed for the wireless terminal, now moved into the shared
base container so there is one implementation rather than two. **The client asks for what it needs**, listing the
slots it stood in for, because the alternative — the server inferring what the client can see from its loaded regions
— derives the same answer from different mutable state on two machines, and every disagreement is either a slot
nobody updates, which is this bug again, or one everybody updates twice.

Verified so far: 258 scenarios and 34 JUnit tests green, including four new `NetworkShapeTest` cases covering the
packet round trip, a negative tile, and an unresolvable member becoming a stand-in of the right size rather than being
dropped — dropping one is what used to shift every slot index after it. The server side of the shape is smoke-tested
by every scenario that opens a terminal, since the harness opens through `interact`.

Not verified: everything client-side, which is the half that was broken. Needs a base with an Access Point and Base
Station, checking that the far side's items appear in the storage view, that its capacity counts, that a Station Unit
behind the link can take stations, and that a normal base with no links looks and behaves exactly as it did.

### Fixed on the way here: a terminal did not conduct

The same class of fault, found while building unit emptying and confirmed by the same symptom in play — a base
station on a conduit run to the left of a terminal could not see a transceiver on a run to the right.

`StorageTerminalObject` implemented `NetworkNode` but not `NetworkConductor`, deliberately, so that "one terminal
cannot bridge two separate groups of units". But a terminal's own walk starts at its tile and expands outward, so it
*already* aggregated every group touching it. The groups were joined for the purpose of showing a combined inventory
and separate for every other purpose — not a rule, a disagreement. Measured: with a terminal at the origin and units
at (1,0) and (0,1), the pool from the terminal was 2 and from either unit was 1.

Terminals conduct now, and the question "does this tile conduct?" moved from seven hand-written call sites to one,
`UnitNetwork.conductorsOn`. That also closed the upgrade-cost bug filed as issue #2, where materials stored across a
terminal did not count toward an in-place upgrade.



## Its own crafting and item category

`[verified]` This is supported, not a hack. `Recipe.setCraftingCategory(String... categoryTree)`
sets a per-recipe category override, and `ItemCategory` has four managers —
`masterManager`, `craftingManager`, `equipmentManager`, `foodQualityManager`. Vanilla creates its own
tree in `ItemCategory`'s **static initialiser**, with sort strings like `A-A-A` and `Z-E-A`, so the
tree is fully built before any mod loads and a mod adding to it in `init()` is additive.

```java
ItemCategory.craftingManager.createCategory("<sort>", "arcanestorage");   // once, before recipes
recipe.setCraftingCategory("arcanestorage");                              // per recipe
```

- `createCategory` must come first: `getCategory` **throws** `IllegalStateException` on an unknown
  tree, and `setCraftingCategory` calls `getCategory`. So a missing create is a hard crash at load,
  not a cosmetic miss.
- The display name comes from locale `[itemcategory]` keyed on the last element of the tree.
- Do the **item** category as well, via `masterManager`, not only the crafting one. That is what
  makes the mod's items group together in the player's own inventory sort — and in this terminal's
  `GROUP` mode, since that is the same comparator.
- Free win: the terminal's category filter is already built from the game's own tree, so an
  Arcane Storage entry appears in it with no extra work.
- `[open]` Whether to nest — one flat `arcanestorage` category, or children for storage, logistics
  and wireless. Flat is right until the item count justifies otherwise.
- Acceptance: every recipe this mod registers, and every item, appears under Arcane Storage rather
  than scattered through Furniture and Misc; nothing vanilla moves.

## Settings: the real gaps

Not "there are no settings" — the config file and the terminal tab both exist. What is actually
missing:

1. **In-game discoverability.** The values live in a text file players will not find. The terminal
   tab helps for interface settings but is the wrong place for anything a server owner sets.
2. **No server → client sync.** `[verified]` Mod settings are read independently on each side and
   nothing is synced, and the engine sends no recipe data — so if a server's cost table differs from
   a client's, the two disagree about what a recipe costs. Presentation (theme) is safe to keep
   local; anything the server enforces must be read server-side, and anything both sides compute
   from must be sent.
3. ~~**The terminal settings tab has never been seen in game.**~~ Opened and confirmed in the 16 Aug pass. The
   predicted ~302 px of content in a ~404 px tab fits.

`[verified]` **CustomSettingsLib was evaluated and rejected as a dependency.** It would put settings
in the game's own menu and it has `addServerSettings` plus a `PacketReadServerSettings` — the two
things wanted here. Against that: it is one more Workshop subscription for every player and one more
jar on every dedicated server (this mod is `clientside = false`); its `SettingsFormPatches` reflects
a private field, takes the *last* component in the settings form and unchecked-casts it to
`FormTextButton` assuming it is the back button, and throws `RuntimeException` on reflection failure,
so a game update that renames the field or appends a component breaks the **vanilla settings menu**
for every dependent mod; and it is CC BY-NC-SA 4.0 against this MIT repo. Its string-keyed
boolean/int/string/selection model also has no representation for `CostTable`.

So: **steal the two ideas, take neither the dependency nor the patch.** A settings-menu entry can be
added without hijacking the back button, and server-authoritative values need a packet, which this
mod already has the machinery for.

### Decided for 1.0.0: no settings-menu entry, and the sync gap is documented rather than closed

**No menu entry.** Discoverability is friction and nothing else. The terminal tab already covers every setting a
player rather than a server owner would change, which is the theme and the crafting layout. A menu entry means either
patching the vanilla settings form, which is the fragile thing CustomSettingsLib was rejected for doing, or building a
separate window, and neither earns its risk on the eve of a first release.

**The sync gap cannot be closed the way it first appears.** Costs are read from the table when recipes are registered,
in `postInit`, independently on each side, and the engine sends no recipe data. So the client's `Recipe` objects
already differ by the time anything could be sent, and no packet at container-open time can repair that. The real fix
is detection rather than transmission: the server sends a fingerprint of its effective table, the client compares it
against its own and says plainly that the two disagree. That is small and it is the next release's work, not this
one's.

What ships instead is a warning in the place the mistake is made. The `COSTS` section of the config file now carries a
comment saying that changing costs on a server obliges every connecting player to use the same values. `SaveData` takes
a comment for a section as well as for a value, which is what makes this possible. It reaches exactly the person who
can cause the problem, at the moment they are causing it, which no amount of documentation elsewhere does.

Out of the box there is no mismatch, because both sides ship the same defaults. Only an edit creates one, and the
person who edits is the person who can distribute the file.

## Conventions compliance

The wiki documents conventions this repo has never been audited against. All of these are checkable
by script rather than by eye.

- **Resource layout.** Everything under `resources/` in the jar `[verified]` — confirmed, including
  `resources/preview.png` at the path `LoadedMod` reads. The per-type subfolders (`items/<stringID>`,
  `objects/<stringID>`, `buffs/`, `biomes/`, `projectiles/`) were audited for completeness during the
  release check and nothing is missing.
- **Unique file names.** The wiki's warning is mutual: another mod using the same path overwrites our
  file, and ours overwrites theirs. Audit every shipped resource path for a collision with a vanilla
  path, and confirm everything carries the `arcanestorage` prefix.
- **String ID uniqueness.** Collisions are silent. Check every registered ID against
  `reference/grep/locale.tsv` (7528 keys, and the keys *are* the registry IDs).
- **Locale completeness.** Every registered ID has an `en.lang` entry, and every `<placeholder>` in a
  value is one the code actually passes. A missing key shows the raw ID to the player.
- **`mod.info` fields.** `[verified]` `id` lowercase and stable, `gameVersion` 1.3.2 matching the
  build, `clientside = false` correct, no `optionalDependencies`. Only the `description` is worth a
  second read, since it is what the mod list shows.
- `[open]` Anything else the wiki's Modding page documents as convention that has not been checked.

## Art and store page

- **Showcase images exist as of 16 Aug 2026.** Eight captures in
  `art-submissions/2026-08-16/screenshots/`: the storage, crafting, logistics and station views, an access point, a
  base station, the settings tab, and one with no interface at all. `[open]` whether any belong in the tracked
  repository — they are roughly 2 MB each and the store page does not read them from here.
- **`preview.png` doubles as the banner. Decided: no name on it.** Both were rendered and compared at 268 and at
  150 pixels wide, which is what settled it, and neither argument that was expected to decide it did. At the larger
  size the text sits over the crafting table, which is the focal point of the art. At thumbnail size the art is
  unreadable either way, so a name does not rescue it. What decides it is redundancy: Steam shows the mod's name as
  text beside the image in the browse grid, on the item page and in the in-game mod list, so a name baked into the
  image is duplicated in every place the image appears and only costs the art. Shipping the 1024x512 image unchanged.
- **Two orphaned textures are being deleted**: `ui/arcanestoragepanel.png` and
  `ui/arcanestoragepaneledge.png`, the old purple pair, referenced by nothing and backed up in
  `art-submissions/2026-08-14-buses-and-unit/ui/`.
- **From QA backlog 0c, the sprites are installed but unseen.** Whether the three rungs of each
  family are told apart at a glance on the ground and in a hotbar, and whether the transceiver reads
  as a device rather than as another chest.
- **Workshop text is not in the repo.** `[verified]` The upload takes the title from `mod.info`'s
  name, the description from a text box typed in game, and tags picked there. Have the description
  and tag choice ready before starting the upload, and mention that removing the mod loses whatever
  is inside its objects.

## QA before upload

The scenario suite covers the logic; none of it covers the client. These are the ones where failure
is player-visible and nothing automated will catch it. Full detail in `QA_BACKLOG.md`.

**P0 and P1 were both cleared in game on 16 Aug 2026**, which closes the wireless terminal withdraw and pairing
path, benches dropping when a terminal is broken, the transfer resolver and Phase 5 buses, the `make server` boot,
the Stations tab, live bench install and uninstall, collapsible sections, the capacity bar colours, grid click
conventions, and the settings tab from `0b26f9a`. Two entries below outlive them.

- **Multiplayer has never been run.** Two clients via `make run` + `make dev`, one hosting: a second
  player opening the same terminal, withdrawing while the first has it open, and seeing the grid
  update. This is the largest untested surface in the mod, and containers are exactly where desync
  shows.
- ~~**A world round-trip.**~~ **Done, 16 Aug 2026.** Confirmed across both a reload and a full game
  exit: bus custom names, Access Point band and channel tuning, and the Wireless Terminal's pairing
  to a transceiver all come back. Those three were chosen because the scenario suite already proves
  unit contents, network shape and bus rules survive a real server restart, and does not reach any of
  them. The gap that remains is automated coverage of the three, which is issue #5 rather than a
  release blocker now that each has been seen working.
- Per backlog item 0h, leave a gap between scenario runs or a suite started before the previous
  server has finished shutting down fails scattered across files, which reads as a real regression.

### Fixed after the P0/P1 pass, each found by playing rather than by testing

Recorded because all four were invisible to 273 scenarios and 40 unit tests, which is the argument for the pass
having been worth doing.

- **A container wider than one tile was only found from some sides** (`813c1fa`, issue #3). A multi-tile object
  registers its entity on the master tile only, so which side worked depended on the rotation it was placed with.
- **The wireless terminal showed nothing** (`57a95c8`). A refactor left two identical stand-in classes, and only one
  of them was recognised.
- **Deposit all emptied a locked hotbar** (`230f30e`). "Locked" means two unrelated things in this engine, and the
  one the padlock sets was not being read. A deliberate divergence from vanilla, which enforces that flag only on
  the way in.
- **Two of the three sort modes had never worked** (`d5c8723`). `FormItemList` re-sorted the grid by its own
  comparator over whatever the form had produced. GROUP concealed it perfectly by being the same comparator. The
  active mode is now shown on the button as well.
- **Every dropdown panel drew base-game wood** (`67bc834`), in a mod whose whole interface is themed.

## Save compatibility starts now

Uploading makes the save format a promise. Anything a player builds in 1.0.0 has to keep working in
1.0.1, which means the GND keys and object-entity save data are frozen in the same way the mod `id`
is: additive changes only, with defaults for anything absent. Worth one deliberate read of what is
persisted before upload rather than a migration path afterwards.

Related, and worth saying on the store page rather than discovering in a bug report: removing the mod
from a world removes its objects, and with them whatever they hold. Said on the store page now, in
`WORKSHOP.md`, along with the fact that a Storage Unit can be emptied into the rest of the network from its own panel,
which is the intended way to relocate one.

### The deliberate read, done 16 Aug 2026

The whole surface, which is smaller than expected:

| Where | Keys |
|---|---|
| `BandIndex`, level data | `BAND/{id, tier, x, y, n}` and `CH/{x, y}` |
| `ArcaneAccessPointObjectEntity` | `bandId`, `channel`, `customName` |
| `BusObjectEntity` | `customName`, `ordinal`, and a `FILTER` section written by the filter itself |
| Item GND, `RemoteBinding` | `asrlevel`, `asrx`, `asry` |

Unit and station contents are an engine-managed `Inventory` saved by `InventoryObjectEntity`, so they are not a
commitment of this mod's at all. Item GND keys share one namespace across every mod, which is why those three carry an
`asr` prefix rather than reading as `level`, `x` and `y`.

**A suspected blocker turned out not to be one.** The bus ordinal is persisted, and the open question about deriving it
deterministically from tile coordinates looked like something that had to be settled before upload, since renumbering a
player's buses afterwards would be renaming things they had learned. It does not: assignment is guarded at
`BusObjectEntity:583` on `ordinal != 0`, so a bus is numbered once, at placement, and the number is then read from the
save forever. Changing the rule later can only change what a newly placed bus receives. The comment there already says
why it is saved rather than derived, which is that deriving would renumber the survivors every time a bus was broken.

So there is nothing to decide here before upload, and nothing to migrate from. Everything above is additive-only from
the moment 1.0.0 is published.

## Player-facing documentation and support, added 16 Aug 2026


### A player wiki — wanted for 1.0.0

Written for players rather than developers: plain explanations, every item shown with its own texture, and links out
to [the official Necesse wiki](https://necessewiki.com) for base game items rather than re-explaining them.

**Decision: the pages live in this repository, not in the GitHub wiki.** The GitHub wiki is a separate git repo, so
it cannot reference the mod's own sprite files by relative path and would need either duplicated images or absolute
raw URLs. It also versions independently of the code, so a page could describe a release that does not exist yet.
Keeping the pages here means one clone holds everything, a page points at the texture that actually ships, and the
documentation moves with the release it documents.

`[open]` Whether to render them through GitHub Pages later. The markdown reads acceptably on GitHub as it stands,
and a player following a link from the store page lands on a document rather than on source code.

Note for whoever writes them: pixel art scaled by a browser is blurred, and GitHub markdown offers no way to ask
for nearest-neighbour. Ship pre-scaled copies of each sprite rather than resizing 32×32 files in HTML.

### A bug report channel — deliberately after the release

Researched 16 Aug 2026. Necesse's modding community is Discord-centric, but the pattern worth copying is what
Necesse Expanded (~21k subscribers) does: a **pinned Workshop discussion thread** on the mod's own page. Every
subscriber already has a Steam account, so it is the lowest friction option that exists, and the author does not
have to read the comments to find reports.

Its one real limit is that Workshop discussions accept no file attachments, so a crash log has to be pasted or
linked. Two things follow, and both were verified on this machine rather than assumed:

- `~/.config/Necesse/latest-log.txt` is the ordinary client log, and `~/.config/Necesse/logs/` keeps timestamped
  archives.
- `<install>/latest-crash.log` is a different file, written by `ThreadFreezeMonitor` on a crash or deadlock,
  alongside any JVM `hs_err_pid*.log`. The Windows equivalents under `%APPDATA%\Necesse\` are documented but were
  not checked here.

GitHub Issues stays the internal tracker: it needs an account, which is the wrong ask of a player. A Discord server
or a form service with anonymous file upload can be added later if the thread proves insufficient.


## Deferred, and not blocking 1.0.0

- **Quick-deposit button for the void bag, lunchbox and similar containers** — deposit their contents
  into the network in one click. Wanted, not yet ironed out: which containers qualify, whether it is a
  terminal button or a player-inventory one, and what happens when the network is full. Tracked here
  so it is not lost, to be designed after release.
- The far-side-threshold / production-connector feature.
- The three code-polish sweeps: a load-time registration self-check (the
  `NetworkNode`-versus-`NetworkConductor` mistake silently severed networks, twice), sweeping each
  fault's class rather than each instance, and one home for panel chrome.
- Dropping "Arcane" from the tiered rung names — one line in `en.lang`.

## Blocked on art in progress — **done, 16 Aug 2026**

Placed objects now draw the same texture as their item icon. The buses gained a directed set that also points at
the container they serve (`3364827`), and all eight unit sprites — storage and station, across four rungs — became
their own icons byte for byte (`376cba8`). Both confirmed in game.

One consequence is deliberate: the old unit art was full-bleed, so a bank of units abutted into an unbroken wall,
and the icon art is a centred cabinet, so tile edges are now visible between neighbours. That matches how vanilla
chests and barrels look placed in a row, which is the standard being followed.
