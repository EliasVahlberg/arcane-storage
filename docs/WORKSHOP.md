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

Steam Workshop descriptions are **BBCode**, not Markdown, and the game passes the box's contents
through untouched: `descTextBox.getText()` goes straight to `steamUGC.setItemDescription` with no
conversion (`SteamDevModProvider:469`). Markdown bold would therefore appear as literal asterisks, so
the text below is already BBCode. Paste it as is.

2478 characters against the 5000 the box allows.

---

Necesse gives you plenty of chests. It does not give you a way to see what is in all of them at once.

Arcane Storage adds a network you build out of Storage Units and conduits, and a terminal that shows everything on it as one searchable inventory. Craft straight from the whole network, using the crafting stations you have installed. Search, sort, and filter by category or by whether an item stacks.

[h3]Building a network[/h3]

Place a Storage Terminal, run Arcane Conduits to Storage Units, and everything they hold becomes one inventory. Add units to grow it. There is no fixed cap.

Install crafting stations into a Station Unit and the terminal's crafting tab offers their recipes, drawing materials from anywhere on the network.

[h3]Moving items automatically[/h3]

Import and Export Buses sit next to any container and move items in or out of the network. Each bus has its own item rules and can be renamed, so a farm chest, a mine chest and a smelter row can each behave differently. A bus shows which container it serves.

[h3]Reaching further[/h3]

A Wireless Terminal opens the network from anywhere, once it is paired with a Wireless Transceiver. Arcane Access Points and Base Stations link distant storage over a band, so an outbuilding on the far side of the base is on the same network as everything else.

[h3]Four tiers[/h3]

Units, station units, transceivers and base stations upgrade through base, Demonic, Tungsten and Fallen rungs, following the same ladder the game's own crafting stations use. Each rung buys capacity, reach or channels.

[h3]Two interface themes[/h3]

A slate style and a dark style, or the game's own if you have themed it yourself. Changed in the terminal's settings tab.

[h3]Before you install[/h3]

[b]This is not a client mod.[/b] A server and everyone connecting to it need it installed.

[b]Removing the mod removes its objects[/b], and anything stored inside them goes with it. Empty the network first. A Storage Unit can be emptied into the rest of the network from its own panel, which is the intended way to relocate one.

[h3]Help and bug reports[/h3]

A full player guide, with every item and what it does, is at
[url=https://github.com/EliasVahlberg/arcane-storage/wiki]github.com/EliasVahlberg/arcane-storage/wiki[/url]

Bugs and crash reports are welcome in the discussion board on this page. A crash log helps a great deal: on Windows it is in [i]%APPDATA%\Necesse[/i], and on Linux in [i]~/.config/Necesse[/i].

---

## Notes for the upload itself

- The upload button only appears in a **dev-loaded** session, so the upload has to be done from
  `make run` rather than from a Steam launch.
- `resources/preview.png` must be in the jar. It is, and `releasecheck` does not currently assert it,
  which is worth remembering if the resource layout ever changes.
- The mod id `elias.arcanestorage` must never change between versions.
- **Leave the "update description" checkbox ticked.** It gates whether the description is sent at all
  (`SteamDevModProvider:398` and `:469`), and it defaults to ticked, so the only way to lose the text
  is to untick it.
- The description box is a 5000 character `FormTextBox` inside a scrolling content box, so a multi-line
  paste is fine.
- The preview is re-saved from the loaded texture rather than copied from the jar
  (`SteamDevModProvider:513`), so what ships is whatever `resources/preview.png` the running mod loaded.
