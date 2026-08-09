# Sprite assets — deliverables and specification

What art the mod needs, what each piece has to communicate, and the exact file rules the
game imposes. Written for whoever is drawing, not for whoever is coding.

The technical rules below are read from the game's own drawing code
(`InventoryObject.loadTextures` and `addDrawables`), not from documentation, so they can be
relied on. Anything not verified is marked `[unverified]`.

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

## REQUEST: conduit connection shapes — a 16-frame sheet

**Status: needed. The code is already in place and falls back gracefully, so this is a
drop-in replacement.** The installed 128×32 four-frame sheet renders straights only, so a run
that turns a corner shows two straights meeting at right angles instead of an elbow.

### The file

`objects/arcanestorageconduit.png`, **512×32** — sixteen 32×32 frames in a horizontal row,
replacing the current 128×32.

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
(`tests/scenarios/conduits.txt`), so if the numbering here and the code ever diverge, the
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

| Tier | Unit string ID | Terminal string ID |
|---|---|---|
| 1 | `arcanestorageunit` | `arcanestorageterminal` |
| 2 | `arcanestoragedemonicunit` | `arcanestoragedemonicterminal` |
| 3 | `arcanestoragetungstenunit` | `arcanestoragetungstenterminal` |
| 4 | `arcanestoragefallenunit` | `arcanestoragefallenterminal` |

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

## Priority 1 (original brief) — everything currently implemented, plus the next thing being built

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

That leaves only two candidates for original UI art, and both are better deferred until the
interface exists and its shapes are known:

- **Category filter icons**, one per category shown over the pooled list.
- **A capacity gauge** showing how full the network is. `processing_arrow_empty` and
  `processing_arrow_full` exist as progress art and may be adaptable.

**Icon content is drawn at its native size and never scaled** (verified Aug 2026). The loader
constrains nothing; `FormContentIconButton.getIconDrawX/Y` centres the texture and calls
`.draw(x, y)` with no dimensions, so an oversized icon simply overflows the button. `SIZE_24`
is a 24×24 button, so its icon content must be **≤ 24×24**, and vanilla uses exactly 24×24 for
every icon listed above. Prefer even dimensions, since the centring is integer division.

**Consequence worth knowing:** vanilla's own 32×32 slot-style icons do **not** fit a `SIZE_24`
button. Use `FormInputSize.SIZE_32` for a row that wants them. Different rows may use different
sizes, so the category row can be `SIZE_32` while the toolbar stays `SIZE_24`.

## Non-sprite asset

`src/main/resources/preview.png` — required for Workshop upload. **Installed: 512×512.**

There is no required size and no validation (verified Aug 2026): `LoadedMod` reads it straight
into a texture. But `ModProvider.provideModInfoContent` calls `shrinkHeight(128, false)`, and
that method *sets* height to 128 and scales width to match, with no minimum — so a smaller
image is upscaled rather than left alone. 512×512 is an exact 4x downscale to 128 in-game,
which keeps pixel art crisp, and is also a reasonable Workshop thumbnail. Avoid heights that
are not integer multiples of 128.

## Summary of what to draw first

| # | Files | Size |
|---|---|---|
| 1 | `objects/arcanestorageterminal.png` + `_open` + `items/arcanestorageterminal.png` | 32×48 or 32×64 |
| 2 | `objects/arcanestorageunit.png` + `items/arcanestorageunit.png` | 32×32 |
| 3 | conduit object + item | 32×32, or 128×32 for four facings |
