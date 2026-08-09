# AGENTS.md

Guidance for AI coding agents working in this repository. Read
[CONTRIBUTING.md](CONTRIBUTING.md) too — it has the build, test and style rules, and they apply
equally. This file covers what tends to go wrong when an agent works here specifically.

## What this is

A storage-network mod for Necesse, a Java sandbox game. Java 8 language level, Gradle, packaged as
a jar the game loads at runtime. Layout:

```
src/main/java/arcanestorage/{object,objectentity,container,network,command}/
src/main/resources/{objects,items}/*.png      sprites, one per string ID
tests/scenarios/*.txt                          harness scenarios (data, not Java)
tools/*.sh                                     headless server runner
docs/{ROADMAP,SPRITES,QA_BACKLOG}.md           keep these current
```

## The habit that matters most: grep the game's source

Necesse's source has **never been publicly released**. A local decompile of `Necesse.jar` — around
6500 files — is the only API documentation that exists. The wiki covers a fraction and lags the
build.

So do not reason about what the engine probably does. Read it. Two failure modes have already
produced confidently wrong answers in this project:

- **Searching with the wrong vocabulary.** A grep for `StorageNetwork|SharedInventory|
  RemoteInventory` found nothing and produced the conclusion that Necesse has no cross-container
  aggregation. It has a complete one, under the term **settlement storage**. Terraria terms do not
  transfer: use *object*, *object entity*, *tile*, *recipe tech*, *settler*, *incursion*.
- **Treating absence as proof.** Grep can only fail to find. `Necesse.jar` is also not the whole
  game — `Server.jar` is separate, and holds the dedicated-server and platform classes. An
  "it does not exist" claim about anything server-related is invalid unless that jar was searched
  too. This has been wrong three times.

When you assert an API exists or does not, say what you searched for, so the claim can be judged.

## Prefer the engine's own mechanisms over inventing one

The engine already provides more than it looks. Before building something, check whether it ships:
special container slots exist for sort (`-2`), quick-stack (`-3`) and restock (`-4`); crafting
stations already pull from nearby containers for the player; `Container.craftInventories` is the
hook for crafting from somewhere other than the backpack; item search has a real implementation in
`ItemSearchTester` whose syntax players already know from the crafting station.

Three concrete gains from having done this rather than hand-rolling: network quick-stack and
restock pass our own unit inventories to the engine's `quickStackToInventories` /
`restockFromInventories`, so no item movement is hand-written; sorting uses
`Comparator.naturalOrder()` over `InventoryItem`, so the network sorts exactly as the player's own
sort button does; and the conduit derives its frame count as `width / 32`, which turned out to be
precisely what `InventoryObject` does.

## Verification, and reporting it honestly

Build after every change. `make test` and `make scenarios` both have to pass.

**Nothing headless draws a pixel.** Compiling is not working, and a mod that compiles can still
fail at load. When a change touches registration, resources, rendering or input, say plainly that
it was compiled but not run, and add the check to `docs/QA_BACKLOG.md`. Do not tick a
`docs/ROADMAP.md` box on the strength of a green build.

Do not launch the game. `make run` takes over the display and needs Steam; the maintainer runs
in-game testing. Give a numbered test script instead of trying to test it yourself.

State what you actually checked versus what you assumed. Being told a theory was wrong is more
useful than confident prose — several findings in this repo's history exist because a test
contradicted the author, and the commit messages say so on purpose.

## Repo hygiene, which cannot be undone

This repo is developed inside a larger private workspace containing the decompiled game source and
agent-facing notes. **Only ever run `git` from inside this repository.** Never commit `reference/`,
`necesse-src/`, wiki dumps, `.kiro/`, secrets, or `gradle.properties` (machine-specific absolute
paths). `.gitignore` blocks all of it, including several workspace-only filenames under `docs/`
that are deliberately shadowed. If a commit seems to need those rules relaxed, stop and ask.

## Scope discipline

No configuration options, and no abstraction for a feature that does not exist yet. Solve the
problem in front of you. `docs/ROADMAP.md` is the order of work; a change that does not serve a
phase in it probably should not be made.
