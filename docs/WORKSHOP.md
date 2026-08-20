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

2643 characters against the 5000 the box allows.

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

Bugs and crash reports are welcome in the discussion board on this page. Two files help, and they are not in the same place. If the game crashed, [i]latest-crash.log[/i] is in the game's own install folder, next to the Necesse executable. The ordinary log is [i]latest-log.txt[/i], in [i]%APPDATA%\Necesse[/i] on Windows or [i]~/.config/Necesse[/i] on Linux.

---

## Change notes — there is no box for these

`[verified — source, Aug 2026]` **The game writes the Steam change note itself and the dialog offers no field for
it.** `submitItemUpdate` is called with a hardcoded second argument, `"Mod version " + version + " for game
version " + gameVersion` (`SteamDevModProvider:522-525`), so every update's note on Steam reads "Mod version 1.0.1
for game version 1.3.2" no matter what is typed anywhere.

So a readable summary of what changed has to reach players another way: in the description, or as a pinned
announcement or discussion on the item. The text below is kept for that purpose rather than for a change note box,
and it comes from `CHANGELOG.md` so the two cannot drift.

---

[h3]1.0.4[/h3]

[b]A crash is fixed.[/b] The storage terminal's crafting tab lists every recipe in the game so bench indices never shift, which also means it can render a tooltip for a recipe it did not create. A recipe naming a global ingredient that nothing ever registered an item under, whether from another mod, a removed item, or a typo, crashed the instant its tooltip was drawn. Such a recipe could never actually be crafted anyway, so it is now left out of the terminal's list instead.

[h3]1.0.3[/h3]

[b]Nine languages, translated by AI as a first pass rather than left English-only.[/b] German, Spanish, Brazilian Portuguese, Russian, Simplified Chinese, Japanese, Turkish, Indonesian and Vietnamese now ship alongside English. The nine were picked by checking two things: how well Necesse's own locale covers that language, and how much a translation actually helps players of it, using the EF English Proficiency Index as a guide. This is a best-effort first pass, not a claim of professional translation. Corrections and additional languages from native speakers remain very welcome, and the mod stays open to them. The game falls back to English for any string a translation happens to miss, so a partial file is always safe.

[h3]1.0.2[/h3]

[b]Two crashes are fixed.[/b] Opening the Access Point's channel dropdown before picking a band could crash the game outright — any dropdown with nothing in it could, this was just the one a player hit first. And the storage terminal's crafting tab, grouped by category, could crash if another installed mod shipped a craftable recipe for an item it never gave a crafting category — the terminal now falls back to a general category instead of crashing.

[h3]1.0.1[/h3]

[b]The Settings tab no longer understates Fallen wireless reach.[/b] The Fallen row read "Whole level", exactly as the Tungsten row did, so the upgrade looked as though it bought nothing. Fallen also reaches other levels, which no tile count can express. The reach itself was always correct, only the row describing it was wrong. The two rows now read "This level, any distance" and "Any level, any distance".

[b]Arcane Conduits and the two buses are now made at a Workstation.[/b] They were craftable straight from the inventory, which sounds like a convenience and was not: it put the three most-placed items in the mod into the one crafting list with no categories and no search, so they read as missing. They cost the same as before, and every workstation tier can make them, so an upgraded bench loses nothing.

---

## Reading the result of an upload

`[verified — source + a confused upload, Aug 2026]` **Nothing about the outcome reaches the log.**
`onSubmitItemUpdate` (`SteamDevModProvider:172-202`) raises an on-screen `NoticeForm` on every path and writes no
line at all, so a log ending at "Updating item with call handle" is neither success nor failure. That is genuinely
confusing in the moment, because every earlier step does log.

What the notices say:

| Notice | Locale key | Meaning |
|---|---|---|
| Updating file... | `moduploadupdating` | still in flight |
| Successfully created/updated mod | `moduploadsuccess` | done; continuing opens the item page in the overlay |
| You have not accepted the Steam Workshop terms of service. Try again. | `moduploadnotaccepted` | the EULA gate, and the upload did not happen |
| Could not create file: <message> | `moduploadcreatefailed` | failed, with the Steam result code |

Two further things that look like symptoms and are not. The "upload to workshop" button is the generic entry point
rather than a status, so it reads the same before and after. And whether the description was sent cannot be told
from the log either, because `setItemDescription` (`:508`) has no `println` — only the store page shows that.

Also worth knowing: a log line reading **Started item update** rather than **Created item** confirms the existing
item was resolved and is being updated, since that path only runs with a `PublishedFileID` in hand.

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
