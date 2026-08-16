# Workshop store page — the text to paste at upload

None of this lives in the jar. The upload form takes the title from `mod.info`'s `name`, pre-fills the
description box with `mod.info`'s `description`, and offers the tags as checkboxes. Read from
`SteamDevModProvider:405-434`. The description box holds 5000 characters, so the long version below fits
with room to spare.

Kept here so the wording is version controlled and can be revised between releases without being
retyped from memory.

## Tags to check

Three of the seven the form offers:

- **New content** — the mod adds objects and items rather than changing existing ones.
- **New features** — the storage network, the crafting tab and the wireless access are behaviour the
  base game does not have.
- **Interface** — the terminal is most of what a player interacts with.

Deliberately not **Tweaks**, since nothing vanilla is altered, and not **Client mod**. The form adds
that last one itself, and only for a mod declaring `clientside = true`, which this is not.

## Description

Paste from here down.

---

Necesse gives you plenty of chests. It does not give you a way to see what is in all of them at once.

Arcane Storage adds a network you build out of Storage Units and conduits, and a terminal that shows
everything on it as one searchable inventory. Craft straight from the whole network, using the crafting
stations you have installed. Search, sort, and filter by category or by whether an item stacks.

**Building a network**

Place a Storage Terminal, run Arcane Conduits to Storage Units, and everything they hold becomes one
inventory. Add units to grow it. There is no fixed cap.

Install crafting stations into a Station Unit and the terminal's crafting tab offers their recipes,
drawing materials from anywhere on the network.

**Moving items automatically**

Import and Export Buses sit next to any container and move items in or out of the network. Each bus has
its own item rules and can be renamed, so a farm chest, a mine chest and a smelter row can each behave
differently. A bus shows which container it serves.

**Reaching further**

A Wireless Terminal opens the network from anywhere, once it is paired with a Wireless Transceiver.
Arcane Access Points and Base Stations link distant storage over a band, so an outbuilding on the far
side of the base is on the same network as everything else.

**Four tiers**

Units, station units, transceivers and base stations upgrade through base, Demonic, Tungsten and Fallen
rungs, following the same ladder the game's own crafting stations use. Each rung buys capacity, reach or
channels.

**Two interface themes**

A slate style and a dark style, or the game's own if you have themed it yourself. Changed in the
terminal's settings tab.

**Before you install**

This is not a client mod. A server and everyone connecting to it need it installed.

Removing the mod removes its objects, and anything stored inside them goes with it. Empty the network
first. A Storage Unit can be emptied into the rest of the network from its own panel, which is the
intended way to relocate one.

A full player guide, with every item and what it does, is at
https://github.com/EliasVahlberg/arcane-storage/wiki

Bugs and crash reports are welcome in the pinned discussion on this page. A crash log helps a great
deal: on Windows it is in `%APPDATA%\Necesse`, and on Linux in `~/.config/Necesse`.

---

## Notes for the upload itself

- The upload button only appears in a **dev-loaded** session, so the upload has to be done from
  `make run` rather than from a Steam launch.
- `resources/preview.png` must be in the jar. It is, and `releasecheck` does not currently assert it,
  which is worth remembering if the resource layout ever changes.
- The mod id `elias.arcanestorage` must never change between versions.
