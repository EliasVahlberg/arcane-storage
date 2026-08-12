# Working with other mods

A storage mod earns its keep in a heavily modded game: more item types, more crafting stations, a
longer ladder to climb. Magic Storage and AE2 are both mods people install *because* of their other
mods. So third-party content working here is a feature, not a courtesy — and the way to get it is to
ask the game's own questions rather than to recognise the game's own classes.

## Necesse has no tags and no annotations for this

Searched Aug 2026 for a tag system: no registry, no annotation, and nothing resembling Minecraft's
tags. The five annotations in `necesse/engine/modLoader/annotations/` are all about patching
(`ModEntry`, `ModMethodPatch`, `ModConstructorPatch`, `ModCustomPatch`, `ModCustomPatchMethod`), and
`necesse/apiDoc/` holds only `@APIDocInclude` and `@APIDocExclude`. This is an absence claim, so it is
worth saying what was searched; a tag registry under another name would not have shown up.

What the game has instead is **capability interfaces**, and it uses them consistently:

| Kind | Examples |
|---|---|
| Object entities | `OEInventory`, `OEUsers`, `OEVicinityBuff`, `OEWireHandler` |
| Objects | `SettlementWorkstationObject`, `ChairObjectInterface`, `TableObjectInterface`, `RoomFurniture`, `SettlerBedObject`, `HappinessObject`, `TorchHolderInterface`, `ForestryJobObject`, `EggNestObjectInterface` |
| Items | `Enchantable`, `UpgradableItem`, `SalvageableItem`, `ItemInteractAction`, `TickItem`, `PlaceableItemInterface`, `FishItemInterface` |

Plus three registry-based classifications that behave like tags: `GlobalIngredientRegistry` (the
`anylog` idea — "any of these count"), `RecipeTechRegistry` (which station a recipe belongs to), and
`ItemCategory`'s tree, which is what the terminal's category filter and search already walk.

**The consequence for us:** "what is this thing capable of" is answerable, and answering it through an
interface costs nothing extra while working for mods we have never heard of. "What class is this thing"
is answerable too, and is a trap.

## The rule that came out of this: ask what a station needs, not what it extends

The station gate is the first place it mattered, and the first version got it wrong in a way worth
recording.

Installing a bench into the terminal gives the terminal that bench's recipes. A Forge must not be
installable, because fuel is enforced in `FueledCraftingStationContainer.applyCraftingAction` — the
behaviour of the *container*, not of the object — so an installed Forge would smelt for free. The first
fix asked `instanceof FueledCraftingStationObject`. That is correct for vanilla and worthless for a
mod: a modded smelter with its own fuel that did not extend that class would have installed and smelted
for nothing.

The gate now asks a behavioural question — *does this station need to be standing somewhere?* — and
reads the answer from two places the game already maintains:

1. **`SettlementWorkstationObject`'s defaults.** This is the game's interface for "settlers can craft
   here", and its default methods are a description of a station that needs nothing from its tile:
   `canCurrentlyCraft` returns true, `getFuelRequestOptions` and `getFuelInventoryRange` return null,
   `isProcessingInventory` returns false, `tickCrafting` does nothing. A station that overrides any of
   them is saying, in the game's own vocabulary, that it cannot be reduced to an item in a slot.
   `getMaxCraftsAtOnce` is deliberately not consulted: batching does not imply placement.
2. **`GameObject.getNewObjectEntity`.** In this engine placed state *is* an object entity. All
   installable vanilla stations inherit the base implementation, which returns null;
   `FueledCraftingStationObject` overrides it to create an `AnyLogFueledInventoryObjectEntity`, so
   every fueled station in the game is caught by this half alone — including the Cooking Station,
   which overrides nothing itself.

Both are read once per object class by reflection on the declaring class, and cached. A missing method
is treated as "needs its placement": that means the mod is running against a different build of the
game than it was compiled against, and a refused bench is a visible annoyance while free smelting is
not.

**What is verified.** `tests/python/test_stations.py` runs the rule over the whole vanilla set: 19
stations install, and 7 are refused — Forge, Cooking Station, Cooking Pot, Roasting Station, Compost
Bin, Grain Mill, Cheese Press. The last three are refused one step earlier and for a different reason,
which is itself a finding: they are `GameObject implements SettlementWorkstationObject`, not
`CraftingStationObject`, so they have no `getCraftingTechs()` to offer.

**The honest limit.** A modded station could keep placed state without touching either signal, and
would slip through. It would also be broken for settlers, which is the reason to expect the overlap
rather than to assume it.

**Why `CraftingStationObject` is still named at all.** `getCraftingTechs()` is declared there, so
extending it is the only way an object can state which recipes it unlocks. Asking for it is therefore
as general as the game allows, not a shortcut.

## Membership is a role, not a type

Built Aug 2026, before Phase 5 rather than after, for the reason below.

Three interfaces in `arcanestorage.network`, each mapping onto a distinction the code already made by
recognising one of this mod's own objects:

| Interface | Implemented on | Means |
|---|---|---|
| `NetworkStorage extends OEInventory` | an object entity | contributes its slots to the network |
| `NetworkNode` | a placeable object | the network visibly meets this |
| `NetworkConductor extends NetworkNode` | a placeable object | membership passes through this |

`NetworkStorage` extends the game's own `OEInventory` rather than declaring a new accessor, and that is
the load-bearing decision: `OEInventoryContainerSlot(OEInventory, int)` is what vanilla builds container
slots from, so a foreign member's slots go straight into the terminal's container with no adapter, and
the member inherits vanilla's answers for quick-stack, restock, sort and settlement storage instead of
reimplementing them. The one method added is `getObjectEntity()`, because membership is recomputed from
the world on every access and a broken member has to drop out; for an object entity the implementation
is `return this;`. `isOnNetwork()` defaults to "as long as it exists" and is overridable, so a member
can leave temporarily — powered down, sealed, mid-transfer — without the player breaking anything.

The terminal is a `NetworkNode` and deliberately *not* a `NetworkConductor`: the network meets a
terminal and does not pass through it, which is what stops one terminal bridging two separate groups of
units. Units and conduits conduct.

Both readers now ask the same question. The walk spreads through `NetworkConductor` and collects
`NetworkStorage`; the conduit's drawn shape joins to `NetworkNode`. Previously the walk compared object
IDs against `ArcaneStorage.CONDUIT` and the sprite compared three IDs of its own, so a third-party pipe
could not have joined either, and the two could in principle have disagreed about what "adjacent"
means.

**Vanilla chests still do not join.** They implement `OEInventory` but not `NetworkStorage`, so joining
remains something an object opts into. Silently absorbing a nearby chest would be surprising, and a unit
is recognisable to the player precisely because it cannot be opened.

**How this is verified**, because "another mod can join" is a claim that is easy to make and easy to get
wrong: `src/test/java/arcanestorage/network/NetworkStorageTest.java` implements a `ForeignSilo` the way
a mod author would have to — the interface, an `Inventory`, and nothing else from this mod — and asserts
that the network counts its slots. If that test ever needs another `arcanestorage` type, the seam is not
a seam. The 79 headless tests then confirm our own units still behave identically through the same path.

## What this does not cover yet

Import and export buses (Phase 5) are the first new member type, and they should be built *through*
these interfaces rather than beside them. That is the reason the seam landed before Phase 5 instead of
after: our own code being its first user is the only way to find out whether it is usable, and a seam
nothing has passed through is a guess.

Installed crafting stations are not part of this. A station is an item in a slot, not a member of the
network, and its compatibility question is answered by the placement rule above.

**Not yet, deliberately:** a published integration API. An interface nobody has implemented is a
guess. The order that works is to make the internals general, ship, and let the first real request
shape the public surface.


## Filters: use `ItemCategoriesFilter`, and nothing of our own

Anything in this mod that asks "which items, and how many" uses
`necesse.inventory.itemFilter.ItemCategoriesFilter`, and its editor is
`necesse.gfx.forms.presets.ItemCategoriesFilterForm`. The buses do; per-unit filters (tier 2 in the parity
doc) should; a station-feeding device should.

Three reasons, in the order they matter for compatibility with other mods:

1. **A modded item lands in the tree for free.** `ItemCategory` is a registry-backed tree and every item
   registers into it, so a filter written against the master category covers items this mod will never
   know about. A hand-rolled item list would have needed a decision about unknown items, and any decision
   would have been wrong for someone.
2. **Per-category rules work on modded items too.** A player who ticks a category gets a mod's items in
   that category without ticking each one, and tri-state inheritance already expresses "this category
   except that item".
3. **The player already knows the panel**, so a mod that adds hundreds of items does not make our
   interface harder to learn — it makes the existing tree longer, which is the same problem vanilla's own
   panel already solves with search.

The one thing to watch: `getAddAmount` and `getRemoveAmount` take an `InventoryRange`, which is a range
within a single `Inventory`. Anything network-wide has to sum across members itself, or a per-item limit
silently becomes per-container. See `BusObjectEntity.allowedToMove`.

## Opening a container with extra content: wrap it exactly once

`PacketOpenContainer.ObjectEntity(containerID, oe, content)` and its `LevelObject` sibling are byte-identical
in composition: each writes `tileX`, `tileY`, then **`putNextContentPacket(content)`**.
`ContainerRegistry.registerOEContainer` reads the two ints and then `getNextContentPacket()`, handing the
result to the container. So the content passed to the factory must be the payload itself, unwrapped.

Wrapping it first costs a day of debugging and looks like anything but a layering mistake. The client reads a
length prefix as the payload, and because a length under 64 KB begins with zero bytes, the first fields decode
as plausible defaults rather than as garbage — an enum reads as its first constant, a boolean as false — so the
symptom is a *believable empty object*, not an exception. Ours then got written back over the real one, so
editing a bus's rules erased them.

Two things follow for any future container that ships content:

- **Test the hand-off through bytes.** `Packet.getPacketData()` plus the packet's `byte[]` constructor makes
  the client's side reachable from a headless test: compose it, round-trip it, unwrap it the way
  `registerOEContainer` does, and assert on the result. `BusContainer.openPacket` exists as a seam for this,
  and `query busopenpacket` reports both sides' counts.
- **Object entity state has a first-class path that is not the open packet.** `ObjectEntity.setupContentPacket`
  / `applyContentPacket` carry it, `server.network.sendToClientsWithEntity(new PacketObjectEntity(ent), ent)`
  pushes a change (this is what `OEInventory` does after an inventory change), and `PacketRequestObjectEntity`
  pulls it on demand. Prefer that when state is needed outside a container — a tooltip, a sprite, a second
  player watching. The open packet is only the right vehicle for what one client needs while a panel is open.

## A filter's limits are defined over one inventory; a network is many

`ItemCategoriesFilter` expresses four kinds of limit, and `getAddAmount` folds them all together with the
tightest winning: an item's own `ItemLimits`, a limit on any **category** above it (walking `parent` upward),
and the panel-wide `maxAmount` under each of the four `ItemLimitMode`s. Vanilla measures `TOTAL_STACKS` with
the *moved* item's stack size, so mirroring that is faithful rather than approximate.

The numbers are the filter's; the summing is necessarily ours, because `getAddAmount`/`getRemoveAmount` take
an `InventoryRange` -- a range within one inventory -- and a network is many. Evaluating them per unit would
quietly turn "the network keeps 200" into "each unit keeps 200".

`BusObjectEntity.networkShouldHold` folds all four into one ceiling on the item being moved, converting a
whole-network cap by subtracting what everything else occupies, so each direction's arithmetic stays a single
line. Two traps, both of which shipped broken:

- **`TOTAL_ITEMS` is the default mode**, so a bus honouring only the "each item" modes discarded the number a
  player typed, silently, having read and saved it. A control that does nothing is worse than one that is
  absent.
- **Honouring the whole-container modes literally is just as wrong for a network.** "The container holds at
  most 20 items in total" is a sensible rule for a chest with 40 slots and a useless one for a network: any
  network holding more than 20 things leaves zero headroom, so an import bus stops dead and an export bus sees
  the entire contents as surplus. Both were observed in game.
- **So a bus's number is per item, and the mode dropdown is not offered.** The four modes describe a
  container; only "per item" survives the move to a network. This also makes the panel coherent, since the
  per-item rows mean the same thing as the panel-wide number.
- **Category limits are easy to miss entirely**, because the panel makes them look like a tidier way to tick
  items rather than a limit in their own right.

Reusing a vanilla form is still the right call, but this is the shape of its cost: the widget carries the
assumptions of the thing it was built for, and the ones that do not transfer have to be found and removed
rather than inherited.
