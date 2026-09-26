# Changelog

One entry per released version, written for players rather than for the repository. The Workshop change note and
the GitHub release notes are both taken from here, so they cannot disagree.

Dates are release dates. Anything still unreleased sits under Unreleased until it ships.

## 1.2.0

**Buses can count in their chest instead of the network.** A new *Count in* choice under the number on both
buses. On an export bus, *Container* keeps the chest stocked: set stone and sand to 100 and the chest is kept at
100 of each, topped back up whenever you or a settler take some out, while the network keeps the rest. On an
import bus it leaves that many behind: set torches to 10 and the bus takes everything but 10 torches. Each bus
still only moves one way, so a stocked chest that is over its number, or an import chest that is under it, is
left alone. Two buses on the same chest that would pass items back and forth forever are refused when you press
Apply. *Network*, the default, changes nothing.

**Another mod's broken item no longer crashes the terminal.** Opening the recipe tab with certain mods
installed, Safe Haven QOL among them, crashed the game. An item whose icon never loaded now draws as the
game's own missing-texture placeholder and can still be taken out as normal.

**Items whose saved data does not survive being sent over the network can be taken out again.** Clicking one
did nothing, so it stayed in storage for good. This could affect bags and pouches from other mods. When the
network holds only one version of the item, that is the one you get. When it holds several, nothing is taken,
so you never get a different version from the one you clicked.

**Fixed an error inside Arcane Storage that could interrupt unrelated item moves elsewhere in the game.** It
is now logged and recovered from instead.

## 1.1.1

**Necesse 1.3.3 compatibility.** No behaviour changes of its own; targets the game's latest update, whose own
patch notes are content, AI, and bugfixes with nothing in Arcane Storage's path. Necesse's own network
handshake refuses a client on a different version than the server, independent of any mod, so play across a
mismatched pair still is not possible -- keep the server and every client on the same version.

## 1.1.0

**The storage and crafting tabs can now group items by category**, with a None/Coarse/Fine dropdown per tab
instead of one fixed grouping. Fine is the closest match to how the terminal already grouped before this
setting existed, so an upgrade changes nothing until the dropdown is opened. Crafting's list of sources also
moved into its own dropdown, freeing the row it used to take as a strip of tickboxes.

**Holding a Wireless Terminal no longer swallows every click.** Right-clicking a chest, a placed Storage
Terminal, or anything else interactable now interacts with that object as normal; the terminal's own network
only opens when there is nothing else to click on. Pairing is unaffected: right-clicking a Wireless
Transceiver with the terminal in hand still pairs to it.

**Arcane Storage objects now respect a private settlement's access rules.** A storage terminal, bus, access
point, base station, or wireless transceiver placed inside a settlement that is private and owned now refuses
a player who is neither the owner nor on the owning team, the same rule the settlement's own containers
already enforce. Nothing changes outside a settlement, or in one that is public or unowned.

## 1.0.6

**Storage Units now slow spoiling, more so per tier.** Base is unaffected; Demonic halves the rate, Tungsten
quarters it, Fallen cuts it to an eighth — stronger than a vanilla Cooling Box, since it is the top of a
four-rung climb rather than a match for one specific vanilla object. No fuel needed; it is a property of the
unit itself.

## 1.0.5

**Arcane Conduits no longer block movement.** They now walk over like a torch or a flower patch, requested by
players laying out a base, since a solid conduit fought the layout it exists to make possible.

**The Arcane Base Station and Arcane Access Point now have an animated status light**, cycling through frames like
a transceiver's TX/RX LED, instead of a single static "on" sprite. Purely cosmetic: it plays only while the device
is active and carries no meaning of its own.

## 1.0.4

**A crash is fixed.** The storage terminal's crafting tab lists every recipe in the game so bench indices never
shift, which also means it can render a tooltip for a recipe it did not create. A recipe naming a global
ingredient that nothing ever registered an item under, whether from another mod, a removed item, or a typo,
crashed the instant its tooltip was drawn. Such a recipe could never actually be crafted anyway, so it is now
left out of the terminal's list instead.

## 1.0.3

**Nine languages, translated by AI as a first pass rather than left English-only.** German, Spanish, Brazilian
Portuguese, Russian, Simplified Chinese, Japanese, Turkish, Indonesian and Vietnamese now ship alongside
English. The nine were picked by checking two things: how well Necesse's own locale covers that language, and
how much a translation actually helps players of it, using the EF English Proficiency Index as a guide. This is
a best-effort first pass, not a claim of professional translation. Corrections and additional languages from
native speakers remain very welcome, and the mod stays open to them. The game falls back to English for any
string a translation happens to miss, so a partial file is always safe.

## 1.0.2

**Two crashes are fixed.** Opening the Access Point's channel dropdown before picking a band could crash the
game outright — any dropdown with nothing in it could, this was just the one a player hit first. And the
storage terminal's crafting tab, grouped by category, could crash if another installed mod shipped a craftable
recipe for an item it never gave a crafting category — the terminal now falls back to a general category
instead of crashing.

## 1.0.1

**The Settings tab no longer understates Fallen wireless reach.** The Fallen row read "Whole level", exactly as
the Tungsten row did, so the upgrade looked as though it bought nothing. Fallen also reaches *other* levels, which
no tile count can express, and the reach itself was always correct — only the row describing it was wrong. The two
rows now read "This level, any distance" and "Any level, any distance".

**Arcane Conduits and the two buses are made at a Workstation.** They were craftable straight from the inventory,
which sounds like a convenience and was not: it put the three most-placed items in the mod into the one crafting
list with no categories and no search, so they read as missing. They cost the same as before, and every workstation
tier can make them, so an upgraded bench loses nothing.

## 1.0.0

First release. Unified storage across many containers with search and sorting, crafting from the whole network,
import and export buses with transfer rules, a four-rung upgrade ladder for storage and crafting units, wireless
access through a transceiver and terminal, and wirelessly bridged clusters through base stations and access points.
