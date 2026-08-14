# Sprite assets — deliverables and specification

What art the mod needs, what each piece has to communicate, and the exact file rules the
game imposes. Written for whoever is drawing, not for whoever is coding.

The technical rules below are read from the game's own drawing code
(`InventoryObject.loadTextures` and `addDrawables`), not from documentation, so they can be
relied on. Anything not verified is marked `[unverified]`.

## DELIVERED AND INSTALLED — 14 August 2026

Requests **A, B, C and D** were generated, verified and installed into `src/main/resources/`. **G is still
not drawn**, because it is blocked on a decision rather than on art. The request below is kept for the
reasoning and the measurements; what follows corrects it where the work proved it wrong.

| # | What | Outcome |
|---|---|---|
| A | Storage Unit relit from above | installed — `objects/arcanestorageunit.png` |
| B | Bus direction in the silhouette | installed — the blocking defect is fixed |
| C | Station Unit | installed, and the feature it was for is implemented |
| D | Terminal nine-slice panel | installed and wired to every form |
| G | Category shortcut icons | **still not drawn** — awaiting the dropdown-versus-icons decision |

**Verified on install, independently of the submission's own numbers:** hard alpha everywhere (zero partially
transparent pixels in all eleven files), every palette within the nine-colour ramp, the anchor `#604a8c`
present, and the Station Unit's silhouette identical to the Storage Unit's at 0 px difference with 276
interior pixels changed — a guaranteed sibling that is unmistakably not the same object.

### Three corrections this work forced, all of which the request had wrong

**1. There is no arrow on the buses, and there never was.** The request said to keep "the colour split and the
arrow exactly as they are". The centre feature is a 4-fold symmetric diamond — symmetric about both axes, so
it points in four directions equally, which is none — and the *Storage Unit's own icon carries the identical
motif at the identical coordinates*. An object with no direction to communicate has the same "arrow". It is
shared family decoration, so bus direction was carried by hue alone: one channel worse than the request
estimated. The diamond is kept on both buses, because removing it as a fake arrow would only make them
inconsistent with the family.

**2. The bus objects were the Storage Unit recoloured, pixel for pixel** — the same nine-index structure map
with a different ramp. That is *why* the silhouettes measured 0 apart, and it is also the root cause of
request A, since the buses inherited the unit's symmetric bevel. A and B were one defect described twice.

**3. Vanilla UI frames are deliberately not lit from above**, which points the opposite way to request A. All
four edge profiles of `ui/primal/formbackground.png` are identical. Equal brightness on four sides is a defect
on a world object standing in a lit scene and the convention on flat UI chrome. Applying A's rule to D would
have been wrong.

### The acceptance measurement request B existed for

`sum((a.alpha>0) != (b.alpha>0))`, import versus export:

| | shipped | now |
|---|---|---|
| `objects/` | **0** | **308** |
| `items/` | **0** | **172** |

The submission reported 572 for the objects pair; measured here with the pair bottom-anchored and padded to
the taller of the two — which is how the game draws them — it is 308. The direction of the result is what
matters and is not in doubt.

Direction is now carried by form rather than hue: the import bus has a bulk-intake hopper above the housing
and the export bus an outlet flaring through its bottom edge. **The import bus object is 32×44, taller than
one tile.** That needs no code change — `BusObject.addDrawables` and the placement preview both position with
`drawY - sprite.getHeight() + 32`, verified — but it does mean the object stands 12 px into the tile above,
which is a real change in how it sits and wants a look in game rather than a look at the file.

Width must stay 32: `frameCount = width / 32` and there is no horizontal centring compensation, so a wider
sheet would sit off its tile. Height is free.

The two `_inactive` files are **generated** from the revised art by
`grey = trunc(trunc(0.299R + 0.587G + 0.114B) * 0.75)` — truncation twice, not rounding, which reproduces all
18 colours of the shipped pair exactly. If a bus sprite changes, rerun the generator rather than hand-editing.

### Two shape families were built, measured and thrown away

Both hit the numeric target and both had to go, which is the useful part. A mouth cut into the housing plus a
nozzle scored 112 px and read as a **lidded cooking pot**. An open funnel with 3.5 px walls scored 206 px and
read as **TV antennae**, with a bored tip that split into **cat ears**.

The transferable rules: at 32 px a form needs enough width to retain an interior after outlining — now
enforced in code as a minimum 40% interior, which the discarded funnel would have failed before rendering —
and **any shape with two upward points reads as a creature**. Passing a measurement is not the same as reading
correctly, so the generators end by rendering a sheet to look at.

### What the panel work established

The nine-slice spec in the request below is correct and was re-derived independently: slice 12, `edgeMargin`
8, corner block point-mirrored, centre tile the full texture width. `contentPadding` is **0**, matching the
vanilla form — every component in the terminal is positioned against 0, so anything else would shift the
whole layout.

Two bugs were found in the *verification* harness rather than in the art, and both produce output
indistinguishable from the art bleeding, so anyone rebuilding this will hit them: tiling the centre from x=0
spills 3584 px into the margin, and repeating edge strips whole overshoots the corner by 4 px on a 112 px span.
Strips must be clipped.

The panel is deliberately quiet, since it frames four tabs, an item grid, a dropdown, a capacity bar and a
filter tree. Its centre weave runs at 7.8% where vanilla runs 17.2%, because vanilla's two colours are 9 luma
steps apart while the nearest pair in this family is 22 — matching its coverage would read as noise.

## OUTSTANDING REQUEST — 14 August 2026

Everything the mod still needs drawn, consolidated. This supersedes the scattered per-phase requests
below, which are kept for their per-sprite intent notes.

**Nine files are genuinely new work.** Another 24 are already delivered and sitting in
`art-submissions/`, waiting on the features that use them rather than on art. Read
[The file contract](#the-file-contract) before starting anything — the frame and anchoring rules are
read from the game's drawing code and are not negotiable.

| # | What | New files | Blocking? | Phase |
|---|---|---|---|---|
| A | Storage Unit lighting revision | 1 revised | no | shipped, cosmetic |
| B | Bus direction in the silhouette | 4 revised + 2 regenerated | **yes, a real defect** | 5, shipped |
| C | Station Unit | 2 new + 6 derived | yes, when the feature lands | 3 |
| D | Terminal panel — nine-slice background | 2 new | no | 6 |
| E | Tier variants | none — 15 delivered | no | 6 |
| F | Remote access items | none — 3 delivered | no | 6 |
| G | Category shortcut icons | 0 or ~8, pending a decision | no | 3 |

---

### A. Storage Unit lighting — one 32×32 revision, carried over

`objects/arcanestorageunit.png` is lit flat rather than from above, so a wall of units reads as
bevelled metal next to vanilla's top-lit furniture. The submission flagged this itself.

The constraint that makes it hard, and it is a hard requirement rather than a preference: **a unit must
never look openable.** The player cannot open one — that is the whole point of the object — so any top
highlight that reads as a *lid seam* is worse than the current flat lighting. Light it from above
without implying a hinge.

Unchanged from the earlier request. Still not blocking, and still the only thing wrong with an
otherwise-finished sprite.

---

### B. Bus direction has to survive greyscale — and it currently does not

**This is the one item here that fixes a defect rather than adding polish**, and it was found by
measuring the shipped files rather than by looking at them.

The delivered buses encode direction twice, exactly as asked: colour (import 66 px of green and *zero*
amber; export 72 px of amber and *zero* green) and an arrow. That was the right instinct. The problem
is what "the arrow" turned out to be — **an interior fill, not a shape.** Measured on the shipped art:

```
objects/arcanestorageimportbus.png  vs  objects/arcanestorageexportbus.png
  -> silhouette pixels differing: 0        (outlines are pixel-identical)
items/arcanestorageimportbus.png    vs  items/arcanestorageexportbus.png
  -> silhouette pixels differing: 0        (same for the icons)
```

Two consequences, one of which is now visible in play:

1. **A stopped bus is drawn desaturated**, and the mod does that itself — `BusObject` swaps in
   `objects/<id>_inactive.png` while the device is stopped, which is a grey copy of the same art.
   Confirmed working in game on 14 Aug. But greyscale destroys the *entire* direction signal, because
   hue was carrying all of it: a stopped import and a stopped export differ by 888 interior pixels of
   grey value on an identical outline. At 32 px, on a cave floor, beside a chest, that is not a
   distinction a player will make — and a stopped bus is precisely when they are hunting for which
   device is which.
2. **The colour-blind claim in the original submission was overstated**, mine to correct since I
   accepted it. It said the arrow alone still carries direction for a colour-blind player. It does not
   carry it *as shape*; a protanope is reading the same value difference as the greyscale case above.

**What to draw.** Add a third, redundant channel: **make the outlines differ.** Keep the colour split
and the arrow exactly as they are — both work — and change the silhouette so the two buses are
different shapes. Cheapest options, in the order I would try them:

- Put the arrow *through* the outline: let the import bus's intake notch bite into its own edge and the
  export bus's spout protrude past it, so one reads concave and the other convex.
- Or move the mounting bracket: the bracket is on the left in both. An import bus clamped on one side
  and an export bus clamped on the other differ in outline at a glance and also hint at flow direction.

**Acceptance, and it is measurable rather than a judgement call:** the object silhouettes must differ by
a visible margin, and so must the icons. Worth checking as `sum((a[i].alpha>0) != (b[i].alpha>0))` over
the two files — the current answer is 0 for both pairs, and anything in the low hundreds is plenty.

**Files.** `objects/arcanestorage{import,export}bus.png` and `items/arcanestorage{import,export}bus.png`,
all 32×32. The two `_inactive.png` files are **generated, not drawn** — regenerate them from the revised
art rather than hand-editing, since they are desaturations of it.

**One frame only, and this is a code limit rather than a preference.** `BusObject.addDrawables` calls
`sprite.initDraw()` on the whole texture with no sprite section, so a multi-frame sheet would stretch
the entire sheet across one tile. Facing frames would be welcome eventually — a bus knows which
neighbour is its container — but they need a code change first, so do not draw them yet.

---

### C. Station Unit — a new object, 32×32, plus three derived tiers

New and not previously requested, from the design decision at ROADMAP line 359: the ten crafting-station
slots currently on the terminal move onto **their own placed object**, which joins a network by the same
connectivity rule as a Storage Unit. Slot count is a ladder of **1 → 2 → 4 → 8** across vanilla's four
station tiers.

**Role.** Carries crafting-station slots. Placing more of them is how a player buys more station
capacity, the same way placing Storage Units buys item capacity.

**What it has to communicate**, in priority order:

1. **That it is a unit** — same family, same footprint, obviously a sibling of the Storage Unit, because
   it obeys the same connectivity rule and players should expect that from the shape.
2. **That it is not the Storage Unit.** These two will stand side by side in the same block, and
   confusing them means a player wonders why their items are not showing up. This needs to survive being
   one tile in a wall of near-identical tiles, which is the same tiling constraint the Storage Unit has.
3. **That it holds tools rather than goods** — a rack, a mount, a bracket, a socket. Something that reads
   as "a thing goes in here" rather than "things are kept in here".

Like the Storage Unit it is **never opened by the player** — its slots are reached through the terminal —
so it needs no lid affordance and should not have one. Whether it gets an `_open` state depends on a
decision not yet made about whether it can be interacted with directly; assume not for now.

**Files.** `objects/arcanestoragestationunit.png` and `items/arcanestoragestationunit.png`, 32×32, one
frame. The three upper tiers follow the settled naming — `arcanestorage{demonic,tungsten,fallen}stationunit`
— and are **derived by recolour, not drawn**, using the same frame-only recolour as request E. So this is
two files of real work and six generated.

**String IDs are provisional**, since the feature is unbuilt. The art does not depend on them.

---

### D. Terminal panel — a nine-slice background, and the layout is now fully specified

Phase 6, deliberately last: it is the one visual change that could not be judged until the layout stopped
moving, and as of 14 Aug it has. `Form.setBackground(GameBackground)` is public and defaults to
`GameBackground.form`, so the terminal can carry its own panel without touching anything global.

**This is not one PNG.** It is a nine-slice pair, and the slice geometry is not a convention to be
guessed — it is read out of `HUD.addOutlines`, `HUD.addCenter` and `GameBackgroundTextures`, and
**verified against the real `ui/primal/formbackground.png`** by checking which two sides of each corner
tile carry the dark outline. All four corners, all four edges and the centre matched the decode exactly.

Two files:

| File | Size | Contains |
|---|---|---|
| `resources/ui/arcanestoragepanel.png` | **64×112** | the frame *and* the centre fill |
| `resources/ui/arcanestoragepaneledge.png` | **48×48** | the frame only, no centre |

Both use `edgeResolution E = 12`, matching vanilla's `form` style. The layout, with `E = 12`:

```
        x: 0-11        12-23       24-47                 48-63
     +-------------+-----------+--------------------+-----------+
y  0 | corner BR   | corner BL | bottom edge strip  |           |   <- y 0-11
  -11|             |           | (repeats across)   |  unused   |
     +-------------+-----------+--------------------+           |
y 12 | corner TR   | corner TL | top edge strip     |           |   <- y 12-23
  -23|             |           | (repeats across)   |           |
     +-------------+-----------+--------------------+           |
y 24 | right edge  | left edge |       unused       |           |   <- y 24-47
  -47| (repeats    | (repeats  |                    |           |
     |  downward)  | downward) |                    |           |
     +-------------+-----------+--------------------+-----------+
y 48 | centre fill, tiled in both directions, using the FULL texture     |
  .. | width and all remaining height. Here 64 wide x 64 tall.           |
     +------------------------------------------------------------------+
```

Only `x 0..4E-1` (0–47) is read above `y = 4E`. **Width beyond 48 exists purely to make the centre
tile bigger**, which is why vanilla's primal is 64 wide: a 64×64 centre tile repeats less often than a
48×48 one. Height above 48 is entirely centre. So the minimum legal size is 48×49, and 64×112 is the
recommendation because it matches vanilla and gives a comfortable centre tile.

**Three things about that layout will catch you out, so they are worth stating plainly:**

- **The corner 2×2 block is point-mirrored.** The top-left corner of the panel lives at the *bottom
  right* of the corner block, at (12–23, 12–23). This is not a mistake in the diagram — it is what
  `addOutlines` does, and it is confirmed against vanilla art: the tile at (12–23, 12–23) is the one
  whose dark outline runs along its own top and left. Getting this wrong produces a panel with its
  corners rotated 180°, which looks like a rounding artefact rather than an obvious error.
- **The centre tile is the full texture width**, not the 24 px column left over on the right. At 64×112
  the centre tile is 64×64. Make it tile seamlessly against itself in both directions, since a tall
  panel repeats it several times. Vanilla's primal centre is 64×64 and uses only two colours — a flat
  fill is entirely acceptable and probably correct.
- **The edge texture is drawn *over* the components, not under them.** `Form.drawEdge` runs after
  `drawComponents`, and `drawEdge` defaults to **true**, so this file is not optional. It is the rim
  redrawn on top so content cannot bleed over the frame — which is why vanilla's is exactly 4E×4E with
  no centre region at all. Draw it as the frame with a transparent middle.

**The single most important number, and it decides how thick to draw the frame.** The form's
`edgeMargin` is **8**: the background is drawn inflated by 8 px on every side of the form's own
rectangle (`x - 8, y - 8, width + 16, height + 16`). Vanilla compensates by leaving the **outer 8 px of
each 12 px slice fully transparent**, so the visible frame is only the inner 4 px. Measured on
`ui/primal/formbackground.png`: the left edge slice is opaque at columns 8, 9, 10, 11 only, and the top
edge slice at rows 8, 9, 10, 11 only.

The two facts cancel exactly — `8 px transparent margin` minus `8 px of inflation` — so **vanilla's
visible frame begins precisely at the form's own edge and is 4 px thick, growing inward.**

So: draw a **4 px frame occupying the inner third of each 12 px slice, and leave the outer 8 px
transparent.** A frame drawn 12 px thick across the whole slice would instead bleed 8 px outside the
panel on every side. This is the mistake the geometry invites, and nothing warns about it.

The rounded corners follow from the same thing — vanilla's corner tiles are fully transparent along
their two outward sides, which is what makes the panel read as rounded rather than as a hard box.

`contentPadding` is ours to choose — vanilla's form uses 0, its tooltip and text box use 2.

**The layout above is verified, not inferred.** It was decoded from `HUD.addOutlines` / `addCenter`, then
checked against the real vanilla texture two ways: every corner tile's transparent sides match the
position the code assigns it, and reconstructing a 200×120 panel from these slices alone produces a
closed frame with a uniform interior fill and no gaps.

**What it has to communicate.** This is the piece that makes the mod feel like its own thing, so the
arcane purple anchor `#604a8c` belongs here. But it frames a dense interface — four tabs, an item grid, a
category dropdown, a capacity bar, a filter tree — so it has to stay quiet: a frame with presence and a
centre that does not compete with item icons sitting on it.

**The trade-off to decide before this ships, not while drawing it.** `GameBackground.form` delegates to
`Settings.UI.form`, which is the player's chosen interface style, and Necesse ships several. A custom
panel therefore *ignores that choice* for our form. That is the point when it makes the mod feel
distinct and the cost when a player has deliberately themed their game. Registering a whole
`GameInterfaceStyle` is the other option and is the wrong one — it re-skins the entire game rather than
one interface. A reasonable middle path is a setting, defaulting to the custom panel.

Implementation is about 40 lines and needs no research: instantiate `GameBackgroundTextures(12, 8, N,
loader, edgeLoader)` against our own textures and wrap it in a thin `GameBackground` subclass that
delegates its ten methods. Nothing global changes.

---

### E. Tier variants — delivered, no drawing needed

15 files in `art-submissions/2026-08-09-tiers-and-ui/`, matching the settled four-tier naming
(`arcanestorage{demonic,tungsten,fallen}{unit,terminal}` plus `terminal_open`). Silhouettes are verified
pixel-identical to the base sprites, and they use the **frame-only** recolour so the housing takes the
tier material while the glowing core stays arcane purple — a Fallen unit is unmistakably a higher tier
and still unmistakably Arcane Storage.

Held on Phase 6 building the ladder, not on art. When it lands they get copied in and the recolour script
rerun if the base sprites have changed. The five-versus-four tier mismatch that blocked this is settled:
four tiers, vanilla's ladder.

### F. Remote access items — delivered, no drawing needed

`items/arcanestorage{shard,charm,sigil}.png`, 32×32, in `art-submissions/2026-08-09-priority2-3/`.
Item-only, no object sprites. Escalation is carried by size, brightness and enclosure, with opaque area
rising 18% → 29% → 46% so the ordering is readable without a tooltip.

Held on remote access, which is in ROADMAP's Deferred list. The submission's own noted weakness stands:
the middle tier reads as "a slightly bigger shard", which is fine if the three ever appear side by side
and worth a stronger silhouette if they only ever appear alone.

### G. Category shortcut icons — blocked on a decision, not on art

Six white-mask icons were delivered and **none are used.** Not wasted effort exactly, but a design change
outran them: the category filter shipped as a **dropdown over the game's own category tree**, which is
better than a fixed icon row because mod-added categories appear in it for free. Two further facts settle
why the icons cannot simply be wired up:

- `ItemCategory` has **no icon or texture field at all** — vanilla categories are text-only, so there is
  no slot to put an icon in.
- The six buckets do not match Necesse's real top-level categories. Most visibly `consumable` — all food,
  59 items — has no icon, while `ammo`, a subcategory, has one.

So: **draw nothing here until Elias picks.** Either drop the six, or keep the dropdown and add a short row
of one-click shortcuts drawn against the real category names — which needs `consumable` and `wiring`
added, `placeable` renamed to `objects`, and about eight icons total to be worth having.

If it goes ahead, the technical spec is settled and unusual enough to restate: **category icons must be
flat pure-white masks with detail carried by transparent gaps.** `ButtonIcon` takes a `colorGetter` from
the button state, so the game **tints them at draw time** — vanilla's own are 100% `#ffffff`. Drawing them
in arcane purple with top-lit shading would be wrong. 32×32 on a `FormInputSize.SIZE_32` row; icons are
centred at native size and never scaled, so ≤ 24×24 would be needed for a `SIZE_24` row instead.

---

### Deliberately not requested

So nothing is drawn that will not be used:

- **A capacity gauge.** Listed as an open candidate in the UI section below; that is now stale. Capacity
  feedback shipped as `FormProgressBarText` using vanilla's `progressBarOutline` plus a four-step fill
  colour, and it works. **No art needed** — this is the derive-from-vanilla policy landing correctly.
- **Tab icons.** Necesse ships `FormTabTextComponent`, so tabs are text and the labels are in the locale.
- **Logistics tab error boxes.** They use vanilla `FormTextButton` chrome in `ButtonColor.RED` with the
  reason as a tooltip. Confirmed working in game 14 Aug; nothing to draw.
- **Crafting station slots.** Each shows that station's own vanilla item texture, faded.
- **Silo connectors for wirelessly linked silos.** In ROADMAP's Deferred list, none started. Drawing a
  paired connector against an undesigned feature is exactly the waste the tier variants avoided by
  waiting.
- **A fullness state for the Storage Unit.** There is no fullness hook — `InventoryObject` supports
  exactly one alternate state, `_open`. Do not design a sprite whose readability depends on showing how
  full it is.
- **`preview.png`.** Installed at **1024×512**, which is a clean 8× downscale to the 128 px height the
  mod-info panel forces. It may want revisiting for the Workshop listing in Phase 7, but not as pixel art.

### Corrections to this document, 14 Aug 2026

Recorded because a stale spec is worse than none:

- `preview.png` is **1024×512**, not the 512×512 stated further down. Both scale cleanly; the claim was
  simply out of date.
- The **capacity gauge is solved without art** (see above), so it is no longer an open UI candidate.
- The **category icons are unused**, and the reason is a design change rather than an oversight.
- **Conduit connection shapes are delivered and installed** — 512×32, sixteen frames, measured correct.
  Still awaiting in-game QA of a real network.

## Already delivered and installed

### Conduit connection shapes — DELIVERED, August 2026

Installed as `objects/arcanestorageconduit.png`, 512×32, sixteen frames. The object switches to
bitmask rendering on its own: `frameCount()` reads the sheet width and uses the neighbour mask as
the frame index at sixteen frames or more, falling back to rotation below that, so this replaced
the four-frame sheet with no code change.

Verified independently of the submission's own report, by measuring the installed file:

- Sixteen frames, hard alpha only, exactly the nine palette colours, `#604a8c` present.
- Every frame reaches exactly the edges its bitmask claims, 12 px wide, and **zero** pixels on
  edges it does not claim — so no through-edge carries a cap and the 2 px bar at 32 px joins
  cannot return.
- A vertical run's bottom row is pixel-identical to its top row, and a horizontal run's right
  column to its left column, so runs are seamless by measurement rather than by eye.

Two things about how it was made are worth keeping:

**The tiles are drawn procedurally from centre-line segments**, not generated then repaired. Each
shape is a set of segments; the channel is every pixel within 5.5 px of that path. The awkward
requirements then hold by construction — a segment crossing the boundary fills the edge exactly
12 px wide, and only a segment *ending inside* the tile gets a cap, so a through-edge cannot
accidentally acquire one.

**A claim in `tools/build_conduit_sheet.py` was wrong and is now corrected.** It said offline
rotation avoids directional shading being lit from the wrong side. It does not — a 90° rotation
moves a highlight whenever it happens. What makes every orientation correct is that the authored
cross-section is symmetric about the channel axis and the junction node is four-fold symmetric.
**So if anyone adds a directional highlight to these tiles, the rotations break, and the selftest
will not catch it:** it checks which edges are reached, not how they are shaded.

The source art now lives in the repo, which it did not before: `art/conduit-tiles/` holds the six
tiles and the icon, and `art/make_conduit_tiles.py` regenerates them. Previously the committed
`tools/build_conduit_sheet.py` had uncommitted inputs, so a clone could not rebuild the sheet.

```bash
art/make_conduit_tiles.py art/conduit-tiles          # the six tiles + the inventory icon
tools/build_conduit_sheet.py art/conduit-tiles \
    src/main/resources/objects/arcanestorageconduit.png
```

The inventory icon is generated from the same profile rather than drawn, so it cannot drift from
the world art. It is a loose length of pipe — both ends stopping short of the edge, so both are
capped — with the junction gem at the centre. The previous icon was left over from the plain
four-frame sheet and no longer resembled the object it places.

**Awaiting in-game QA:** everything above is measured on the files, and the shapes have only been
checked in a rendered preview of a 15×8 network. Nothing has been seen in the game.

## The file contract

Every placeable needs **two** files, and they are loaded by different systems:

| Path | Purpose | Required |
|---|---|---|
| `src/main/resources/objects/<stringID>.png` | the object standing in the world | yes |
| `src/main/resources/items/<stringID>.png` | its inventory and crafting icon | yes |
| `src/main/resources/objects/<stringID>_open.png` | alternate sprite shown while a player has it open | optional |

Ship only the object texture and the object places and works correctly while the inventory
and crafting preview show a pink `[ER]` placeholder. This has already caught us once —
`GameObject.generateItemTexture()` is just `GameTexture.fromFile("items/" + getStringID())`
and nothing in the container chain overrides it.

### Layout rules

- **Frames sit side by side, 32px wide each.** The number of frames is
  `texture.getWidth() / 32`, and the frame drawn is the object's placement rotation modulo
  that count. One frame (32 wide) means rotation is ignored. Four frames (128 wide) gives
  four facings and placement rotation starts working with no code change.
- **Height is free, and grows upward.** The sprite is bottom-anchored to its tile
  (`drawY - texture.getHeight() + 32`), so a 32×64 image is a two-tile-tall object standing
  in a one-tile footprint. Each frame is `32 × full texture height`.
- **Sprite height does not change collision.** Our objects declare `Rectangle(32, 32)`, so
  the footprint stays one tile however tall the art is.
- `_open` is loaded with `fromFileRaw` inside a try/catch, so leaving it out is safe. It is
  swapped in whenever the object entity reports `isInUse()` — i.e. while the container is
  open, for as long as it is open.
- Item icons: **32×32** works and is verified in game for the two current placeholders.

### One thing the engine will not do for you

There is **no fullness hook**. `InventoryObject` supports exactly one alternate state,
`_open`. A unit that visually fills up as it gains items would need custom draw code, so
do not design a sprite whose readability depends on showing how full it is.

## Art direction

The palette anchor is already in the code: the storage unit's map colour is
`Color(96, 74, 140)` — arcane purple. Treat that as the family colour, and reserve it for
the mod's own parts so an Arcane Storage network reads as one system on a screen full of
vanilla furniture.

`[unverified]` The suggestions below are stylistic opinion, not measured against extracted
game art. The house policy is to derive from Necesse's
own sprites where possible rather than draw from scratch, precisely so that fit is
guaranteed rather than judged.

## Conduit connection shapes — reference detail

**Status: requested — see the outstanding request at the top of this file for what to draw.**
The code is already in place and falls back gracefully, so this is a drop-in replacement. The
installed 128×32 four-frame sheet renders straights only, so a run that turns a corner currently
shows two straights meeting at right angles instead of an elbow.

### Draw six tiles, not sixteen frames

There are only **six conduit shapes**. Sixteen is the number of *states*: 1 empty + 4 caps +
2 straights + 4 elbows + 4 tees + 1 cross. Everything past the six is a rotation, so it is
generated rather than drawn.

Deliver six 32×32 files — `stub.png`, `cap.png`, `straight.png`, `elbow.png`, `tee.png`,
`cross.png` — drawn in these canonical orientations, and run:

```bash
tools/build_conduit_sheet.py <dir with the six> src/main/resources/objects/arcanestorageconduit.png
tools/build_conduit_sheet.py --selftest   # proves the rotation mapping matches the bitmask
```

| Tile | Draw it reaching | Rotations generated |
|---|---|---|
| `stub` | no edge; a capped nub, centred | none |
| `cap` | **north** only | east, south, west |
| `straight` | **north and south** (vertical) | horizontal |
| `elbow` | **north and east** | the other three corners |
| `tee` | **north, east and south** (opening east) | the other three |
| `cross` | all four | none |

Rotating offline rather than at draw time is deliberate. The engine *can* rotate a sprite
(`TextureDrawOptionsEnd.rotate`), but rotated textures show dark edges — which is why the build
has a `preAntialiasTextures` task — vanilla objects use frames rather than runtime rotation, and
any directional shading would rotate with the sprite and end up lit from the wrong side. An exact
90° rotation of a square tile, done once, is lossless and free at runtime.

### The output file

`objects/arcanestorageconduit.png`, **512×32** — sixteen 32×32 frames in a horizontal row,
replacing the current 128×32. Produced by the script above; not drawn by hand.

### Frame order is not a choice — it is an index

The frame number **is** the neighbour bitmask, computed at draw time from what is actually
adjacent. Bits are `north = 1`, `east = 2`, `south = 4`, `west = 8`, matching the order the
network walk uses. A bit set means the pipe must reach that edge of the tile.

| Frame | Bits | Reaches | Shape |
|---|---|---|---|
| 0 | — | nothing | isolated stub: a short capped nub, centred |
| 1 | N | up | end cap pointing up |
| 2 | E | right | end cap pointing right |
| 3 | N+E | up, right | elbow, up-to-right |
| 4 | S | down | end cap pointing down |
| 5 | N+S | up, down | vertical straight |
| 6 | E+S | right, down | elbow, right-to-down |
| 7 | N+E+S | up, right, down | tee, opening east |
| 8 | W | left | end cap pointing left |
| 9 | N+W | up, left | elbow, up-to-left |
| 10 | E+W | left, right | horizontal straight |
| 11 | N+E+W | up, right, left | tee, opening north |
| 12 | S+W | down, left | elbow, down-to-left |
| 13 | N+S+W | up, down, left | tee, opening west |
| 14 | E+S+W | right, down, left | tee, opening south |
| 15 | all four | all | four-way cross |

Frames 5 and 10 are the existing vertical and horizontal tiles and can be reused unchanged.
**Every frame must be in its numbered position**, including the ones that feel redundant —
there is no remapping layer, and a misplaced frame draws the wrong shape everywhere.

The harness asserts this convention against real placed objects
(`tests/python/test_conduits.py`), so if the numbering here and the code ever diverge, the
tests say so rather than the game quietly drawing nonsense.

### Requirements carried over from the current sheet

- The channel is ~12 px across, low visual weight, and **reaches the exact tile edge** on every
  side a bit is set for, so runs join with no gap. The existing tiles already do this.
- **No permanent end caps on through-edges.** The earlier horizontal tile's black caps produced
  a 2 px bar at every 32 px join; that fix must survive. Caps now belong *only* to frames 1, 2,
  4, 8 and the stub at 0, where the pipe genuinely ends.
- Same nine-colour ramp, containing `#604a8c` exactly.
- Hard alpha, no partial transparency.

### Where the junction art should read from

The corners and tees in the banner at the top of the README are the target look: a bend that
keeps the channel's width through the turn, with the bright core following the bend rather than
breaking at it. Tees should read as one continuous run with a branch joining it, not as three
stubs meeting.

## DECIDED: tier naming — four tiers, vanilla's ladder

This was flagged as blocking. It is settled, and the submitted tier art already matches, so no
files need renaming.

Necesse's stations upgrade through exactly four steps, so ours do too: **base → Demonic →
Tungsten → Fallen**. A fifth tier would have to invent a material step the game does not have.

| Tier | Unit string ID | Terminal string ID | Station Unit string ID |
|---|---|---|---|
| 1 | `arcanestorageunit` | `arcanestorageterminal` | `arcanestoragestationunit` |
| 2 | `arcanestoragedemonicunit` | `arcanestoragedemonicterminal` | `arcanestoragedemonicstationunit` |
| 3 | `arcanestoragetungstenunit` | `arcanestoragetungstenterminal` | `arcanestoragetungstenstationunit` |
| 4 | `arcanestoragefallenunit` | `arcanestoragefallenterminal` | `arcanestoragefallenstationunit` |

The Station Unit column is **provisional** — that object is unbuilt (request C above), and its ladder is
1 → 2 → 4 → 8 slots. The art does not depend on the names.

Base tier carries no prefix, so the string IDs already shipped stay valid and the existing
world data keeps working.

Display names follow the same pattern as vanilla's own stations: "Demonic Storage Unit".

**Icon sizing needs no new request.** `FormInputSize.SIZE_32` takes the delivered 32×32
category icons as they are, and rows may differ in size, so the category row can be `SIZE_32`
while the existing search, sort, quick-stack and restock buttons stay `SIZE_24`.

## Priority 1 — DELIVERED and installed (Aug 2026)

The terminal, unit and conduit sprites plus their item icons are in
`src/main/resources/`, replacing the placeholders, along with `preview.png`. Two of them
changed behaviour rather than just appearance:

- **The terminal is 32×64**, so it stands two tiles tall in a one-tile footprint. Safe because
  sprites are bottom-anchored and grow upward, and collision comes from `Rectangle(32, 32)`.
- **The conduit is 128×32, which is four frames, which turns rotation on.** Frame order
  alternates vertical / horizontal, which is correct rather than merely defensive: the default
  place option in `GameObject` uses `playerDir` as the rotation, and facings 0/2 are vertical
  while 1/3 are horizontal, so a conduit orients itself to the way the player faces.
- `objects/arcanestorageterminal_open.png` now exists, so the open state activates for free —
  `InventoryObject` swaps it while `isInUse()`, which our container already drives through
  `startUser`/`stopUser`.

One acknowledged deviation: the unit is not top-lit, because the top-lit version read as a
lidded box and a unit must never look openable. Fixing the lighting without implying a lid is
the open problem.

## Priority 1 (original brief) — historical

Kept for the per-sprite intent notes below, which still describe what each object has to
communicate. Ignore the status language: all of it is delivered and installed, and the conduit's
string ID is settled as `arcanestorageconduit`.

These three unblock the mod as it stands. The first two currently ship as 32×32
placeholders that want replacing.

### `arcanestorageterminal`

**Role.** The single point of interaction with a network. A design decision, not an
accident: the terminal is the *only* way to reach stored items, and it holds **zero slots**
of its own — all capacity lives in the units.

**Function.** Right-clicked to open the aggregated view of everything in the network. Shows
a lit/active state while open.

**Files.** `objects/arcanestorageterminal.png`, `items/arcanestorageterminal.png`, and
`objects/arcanestorageterminal_open.png`.

**Size.** 32×48 or 32×64. This is the piece that most benefits from extra height — it has
presence and the player walks up to it deliberately.

**Notes and style.** It should read as an *interface*, not as a container: a screen, scrying
surface, lectern or pedestal rather than a box with a lid. Players must never mistake it for
somewhere items are kept, because they are not. The `_open` variant is cheap and high value
— a lit screen or raised glow while in use gives immediate feedback that costs no code. Keep
the silhouette distinct from every vanilla chest.

### `arcanestorageunit`

**Role.** Where items actually live. Capacity is entirely a property of units.

**Function.** Placed in bulk and chained together; units conduct connectivity to each other,
so players build blocks and lines of them. Never opened directly by the player.

**Files.** `objects/arcanestorageunit.png`, `items/arcanestorageunit.png`.

**Size.** 32×32.

**Notes and style.** Two constraints matter more than detail here. First, it must **tile
well against itself** — players will place 2×2 blocks and long runs, and a sprite with a
strong asymmetric feature turns a wall of units into visual noise. Second, it must read as
**subordinate to the terminal**: obviously the same family, obviously not the thing you
interact with. Since it is never opened, it needs no lid affordance at all, which is freeing
— it can look like a sealed vault or crystal cell rather than a chest.

### `arcanestorageconduit` — string ID not yet decided

**Role.** Extends a network's reach without adding capacity. The remaining Phase 2
deliverable.

**Function.** Conducts connectivity between distant parts of a network, so a network is not
limited to one contiguous block of units.

**Files.** `objects/<id>.png`, `items/<id>.png`.

**Size.** 32×32 for a single frame, or **128×32 for four rotations** — worth it here. This
is the one object players will lay out in directional runs, so facings earn their keep, and
rotation comes free from the frame count.

**Notes and style.** Should look **connective**: cable, rune channel, pipe, ley line. It
needs to read correctly in a row, so the art wants to meet its own edges cleanly. Lower
visual weight than a unit — it is infrastructure, not storage.

## Priority 2 — planned, and well suited to a derive-and-recolour workflow

### Import and export buses (Phase 5)

Two placeables, one object plus one item each, 32×32.

**Function.** An import bus attaches to an ordinary container and pulls its contents into
the network — which is what lets settlers keep using chests they already understand. An
export bus pushes items out on a threshold rule, the obvious target being a Shipping Chest
for selling.

**Notes and style.** These two must be **instantly distinguishable at a glance and in the
crafting list**, because installing the wrong one silently moves items the wrong way. Direction
is the whole message: mirrored arrows, inward versus outward flow, or a colour split. They
should look like attachments — something that clamps onto a chest — rather than freestanding
furniture.

### Tier variants (Phase 6)

The storage units have five tiers and the terminal/network four, aligned to Necesse's own
station ladder: base → **Demonic** → **Tungsten** → **Fallen**, with the material spine
running logs/stone/iron → gold, quartz, demonic bar, void shard → tungsten bar, ancient
fossil bar → essences and crystal.

**Notes and style.** These are deliberately *the same object in a better material*, which is
exactly the case where deriving from the tier-1 sprite beats drawing each one. Keep the
silhouette constant and change material, trim and glow, so a player reads tier at a glance
without relearning the shape. Aligning to the vanilla material spine means each upgrade
lands where the player is already upgrading.

**Blocked on a decision:** the counts do not line up — five unit tiers against a four-tier
vanilla ladder — so tier names and string IDs are not settled. Worth resolving before
drawing four variants of anything.

## Priority 3 — later

Remote access is **item-only**, three sub-tiers, `items/<id>.png` at 32×32. Reach is
measured against the settlement and nearest waystone rather than raw tiles, so the art can
lean on that: a waystone shard, a homing charm. No object sprites needed.

## UI — much smaller than expected

**No custom UI art is required today.** The terminal's form uses only vanilla components
(`Form`, `FormItemList`, `FormLocalLabel`), which bring their own art.

For the Phase 3 interface work, vanilla already supplies the icon *content*, used with
vanilla button chrome (`FormContentIconButton`, `FormInputSize.SIZE_24`, `ButtonColor.BASE`).
From `GameInterfaceStyle`:

| Need | Existing icon — do not draw |
|---|---|
| Search | `button_search_24` |
| Sort | `inventory_sort` |
| Quick stack | `inventory_quickstack_in`, `inventory_quickstack_out` |
| Deposit all | `container_loot_all` |
| Trash | `button_trash_24` |
| Favourite / lock | `star_icon`, `hotbar_locked`, `hotbar_unlocked` |

That left two candidates for original UI art, and **both are now settled — see the
consolidated request at the top of this file.** Neither needs drawing today:

- **Category filter icons** — six were delivered and none are used, because the filter shipped
  as a dropdown over the game's own category tree. `ItemCategory` has no icon field at all.
  Blocked on a decision, not on art.
- **A capacity gauge** — solved without art. It ships as `FormProgressBarText` with vanilla's
  `progressBarOutline` and a four-step fill colour.

**Icon content is drawn at its native size and never scaled** (verified Aug 2026). The loader
constrains nothing; `FormContentIconButton.getIconDrawX/Y` centres the texture and calls
`.draw(x, y)` with no dimensions, so an oversized icon simply overflows the button. `SIZE_24`
is a 24×24 button, so its icon content must be **≤ 24×24**, and vanilla uses exactly 24×24 for
every icon listed above. Prefer even dimensions, since the centring is integer division.

**Consequence worth knowing:** vanilla's own 32×32 slot-style icons do **not** fit a `SIZE_24`
button. Use `FormInputSize.SIZE_32` for a row that wants them. Different rows may use different
sizes, so the category row can be `SIZE_32` while the toolbar stays `SIZE_24`.

## Non-sprite asset

`src/main/resources/preview.png` — required for Workshop upload. **Installed: 1024×512.**

There is no required size and no validation (verified Aug 2026): `LoadedMod` reads it straight
into a texture. But `ModProvider.provideModInfoContent` calls `shrinkHeight(128, false)`, and
that method *sets* height to 128 and scales width to match, with no minimum — so a smaller
image is upscaled rather than left alone. The installed 1024×512 is an exact 8x downscale to
the forced 128 px height, which keeps pixel art crisp, and is a reasonable Workshop thumbnail.
Avoid heights that are not integer multiples of 128.

## Summary of what to draw first

| # | Files | Size |
|---|---|---|
| 1 | `objects/arcanestorageterminal.png` + `_open` + `items/arcanestorageterminal.png` | 32×48 or 32×64 |
| 2 | `objects/arcanestorageunit.png` + `items/arcanestorageunit.png` | 32×32 |
| 3 | conduit object + item | 32×32, or 128×32 for four facings |


## Requested: the two buses (Phase 5, Aug 2026) — DELIVERED, then superseded

**Delivered and installed. Superseded by request B at the top of this file**, which corrects one
thing this brief got wrong: it asked for "an arrow, or an asymmetric silhouette", and what was
delivered has the arrow but an identical silhouette. That is fine until a stopped bus is drawn
grey, which is now a shipped feature. Kept below for the intent notes.

`objects/arcanestorageimportbus.png` and `objects/arcanestorageexportbus.png`, plus matching
`items/` icons. Currently **placeholders**: the Storage Unit's sprite recoloured green and amber by
`tools/tint_sprite.py`, which is honest enough to test with and reads as "a unit in another colour".

What they need to communicate, in order of importance:

1. **Direction.** An import bus takes from a container and gives to the network; an export bus does the
   reverse. Two objects that differ only in hue will be placed the wrong way round constantly. An arrow,
   or an asymmetric silhouette, matters more than the palette.
2. **That they are not storage.** A bus holds nothing and cannot be opened. It should read as a fitting
   or a valve rather than as a box — the Storage Unit is the box.
3. **That they belong to the conduit family**, since they conduct like one. The conduit's palette
   (96, 74, 140) is the anchor.

32x32, one frame, bottom-anchored like the other objects. Facing frames would be welcome later — a bus
knows which neighbour is its container, so it could draw itself pointing at it — but nothing depends on
that yet, and one frame is what the code expects.
