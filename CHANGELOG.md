# Changelog

One entry per released version, written for players rather than for the repository. The Workshop change note and
the GitHub release notes are both taken from here, so they cannot disagree.

Dates are release dates. Anything still unreleased sits under Unreleased until it ships.

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
