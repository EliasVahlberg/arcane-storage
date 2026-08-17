# Contributing

## You need a Necesse installation

The build compiles against `Necesse.jar` from a licensed install, and the test harness runs the
game's own `Server.jar`. There is no way around this, and it has one consequence worth stating
up front: **there is no CI, and there cannot be.** No hosted runner may hold the game's jars, so
every check in this document runs on your machine. Say what you ran.

```bash
cp gradle.properties.example gradle.properties
# edit necesseGameDir and org.gradle.java.home
make doctor      # confirms the JDK, the game install and Steam are found
make build       # under a second once warm
```

JDK 17 specifically. The mod targets language level 8, which newer JDKs refuse to compile, so
`org.gradle.java.home` must point at a 17 install even if your default `java` is newer.

## Run the Makefile, not `./gradlew`

The Makefile is not a convenience wrapper. It encodes `< /dev/null`, `--console=plain`, and
`pipefail` with `tee`, because a bare `./gradlew … | tail` can be **kernel-stopped** by
SIGTTIN/SIGTTOU when it touches a terminal it no longer controls — state `T`, indistinguishable
from a hang while the daemon waits for a client that will never reply. That cost half an hour
once. Never pipe `gradlew` through `tail` or `head`.

If a build appears to take minutes, diagnose it rather than waiting: `ps -o pid,stat,wchan:20,cmd
-p <pid>`, where `T` means stopped, not busy.

## Tests

```bash
make test         # JUnit over the network traversal — pure logic, no game
make pytest       # the whole suite against a real headless server, including a save/load restart
```

`make pytest` boots the real dedicated server twice, about 46 seconds in total: once for the session
and once more for the persistence test, which restarts the server so that what it asserts is being
read back from disk. Per boot that is roughly 3s of JVM start, mod load and world generation, plus a
save the runner waits on deliberately. Everything between boots is milliseconds, so tests are cheap to
add — the boot is the whole cost.

**A run can never hang.** A deadlock does not stop the game: the engine's `ThreadFreezeMonitor`
writes a crash log and leaves the JVM alive with its command thread no longer reading stdin. The
runner therefore has its own deadline (`HARNESS_DEADLINE`, 180s), checks the server is alive
between commands, bounds how long it waits for a stop, and prints any crash log the game wrote
during the run. Before that, a deadlock looked like a test run that took as long as whatever
timeout happened to be wrapped around it — 400 seconds in one case — while reporting nothing.

### Tests are Python, driven through the harness

The suite lives in `tests/python/` and talks to a real dedicated server through the harness's
request/reply protocol:

```python
def test_capacity_counts_slots_not_items(terminal):
    terminal.harness.fill(1, 0, "ironbar", 40)
    assert terminal.capacity() == (1, 40)      # one slot, not forty
```

The harness is its own mod:
[necesse-headless-harness](https://github.com/EliasVahlberg/necesse-headless-harness). It has to
be installed into `~/.config/Necesse/mods` (`make install` in its repo) rather than dev-loaded,
because the game accepts exactly one dev mod and this one holds that slot. Install it as a sibling
checkout; the tooling here looks for `../necesse-headless-harness` unless `HARNESS_DIR` says
otherwise.

Generic verbs come from the harness: `place fill clear break give open close click quickstack
restock player run echo`, and `expect item total held`. This mod registers the rest —
`report reset withdraw deposit depositall depositcursor install bench`, and
`expect units capacity fits mask inuse` — from `arcanestorage.harness.ArcaneStorageVerbs`, which is
also where a new one goes. Note it deliberately *replaces* the harness's `expect item` and
`expect total`: the generic versions read one tile's inventory and every inventory on the level, and
here those must mean what the network can see.

Coordinates are relative to world spawn, so tests are world-independent.

### Scene files, which are not tests

A verb sequence is also a plain text file a running game can execute, and `tests/scenes/` uses that
to build states worth looking at:

```
place terminal 0 0
bench 0 0 64 1
```

`harness run full_network` in a game launched with `make run HARNESS=1`, or
`make scene FILE=tests/scenes/full_network.txt` headlessly. This exists because the UI cannot be
tested automatically, so the next best thing is making the state behind a visual check one line to
produce. **No assertions belong here** — those go in `tests/python/`. There used to be a second,
bash-driven suite of these; [docs/TESTING.md](docs/TESTING.md) records why there is now one suite.

`tests/python/conftest.py` holds this mod's fixtures -- a `terminal` with a unit beside it, a
`storage` fixture that runs `reset` so isolation does not depend on a clear radius, and the queries
that mean something here. It mirrors `ArcaneStorageVerbs` on the Java side: the harness supplies the
generic driver, this repo supplies its own vocabulary.

Use Python when the test wants a value, a size sweep, or a diff on failure. Use a scenario when the
value of the test is that you can paste it into a live server. Both run against the same code, and
`reset`, `withdraw`, `deposit`, `depositall`, `report` and `bench` are available either way.

Prefer adding a scenario over adding a unit test when the behaviour involves the level, objects,
or containers — the harness exercises the real server, real packets and real persistence, which
is where the interesting bugs are. Keep unit tests for pure graph logic.

Two rules the harness has already caught people out on:

- **Reads do not load regions.** The object layer resolves an unloaded region as *empty* rather
  than loading it, so a coordinate you never visited reads as nothing. The command forces the
  addressed region to load first. A consequence: `expect total` only sees loaded regions, so
  assert per-network numbers before totals.
- **`session/` scenarios need a real player** and cannot run headlessly. Run those from chat in
  game.

### What the tests cannot tell you

Nothing headless draws a pixel. Anything about rendering, input, or layout has to be checked in
game, and belongs in [docs/QA_BACKLOG.md](docs/QA_BACKLOG.md) with a note on what to look at.
Only tick a [docs/ROADMAP.md](docs/ROADMAP.md) box once it has actually been seen working —
except where the harness genuinely verifies it, in which case say which is which.

Also remember `initResources()` is client-only. A dedicated server never calls it, so
server-only crashes are a distinct failure class that only `make server` catches.

## Code

Three-space indent, matching the game's own style. Lines up to ~120 characters. Package
`arcanestorage`, one concern per package: `object`, `objectentity`, `container`, `network`,
`command`.

**Use the engine's own abstractions.** Singleplayer is a real server —
`ServerSingleplayerNetwork extends ServerNetwork` and routes through the same `Server` and
`packetManager` over a loopback transport — so building on containers, inventories and packets
exercises the multiplayer path from day one. Hand-rolling state outside them is the way to get
burned, even when it looks easier.

**The game's source was never released.** The only real API documentation is a local decompile of
`Necesse.jar`, and the game's own 6493 classes are the best examples of how to use its API. Before
claiming an API does or does not exist, grep for it — and search with Necesse's vocabulary rather
than Terraria's, because they differ: *object* (placeable), *object entity* (a container's backing
state), *tile* (ground), *recipe tech* (crafting station), *settlement storage* (the engine's own
cross-container index).

**Never commit the decompiled source, or any bulk extraction of the game's assets.** It is
proprietary. `.gitignore` blocks `reference/`, `necesse-src/`, `res.data` and friends; do not
relax those rules.

## Art

Sprites live in `src/main/resources/objects/<stringID>.png` and
`src/main/resources/items/<stringID>.png`. **A placeable object needs both** — the object texture
draws it in the world, the item texture is its inventory icon, and shipping only the first gives a
pink `[ER]` placeholder in the inventory and crafting preview.

Frames are laid out horizontally at 32px each and the frame count is derived from texture width,
so **adding frames changes behaviour with no code change**. See [docs/SPRITES.md](docs/SPRITES.md)
for the palette, the required dimensions, and the conduit's 16-frame connection index — where the
frame number *is* a neighbour bitmask, so frame order is a contract rather than a preference.

## The player wiki

The pages under `docs/wiki/` are the source of truth. The
[GitHub wiki](https://github.com/EliasVahlberg/arcane-storage/wiki) is a published copy, generated by
`tools/publish-wiki.sh`.

**Do not edit the wiki through GitHub's web editor.** The next publish deletes everything the wiki holds and
writes the current `docs/wiki/` in its place, so an edit made there is lost without warning. Change the page in
`docs/wiki/`, open a pull request for it like any other change, and publish afterwards.

Editing here rather than there buys three things. The pages can use relative paths to the sprites the mod
actually ships, so a picture cannot drift from the texture it claims to show. A documentation change can be
reviewed alongside the code change that made it necessary. And `WikiTest` runs on every build, which checks that
every link and image resolves, that no screenshot is unreferenced or oversized, and that the prose contains no em
dashes or semicolons, both of which are house style here.

```bash
bash tools/publish-wiki.sh --dry-run    # show what would change
bash tools/publish-wiki.sh              # publish
```

## Translations

Translations are welcome and need no Java. Every player-facing string already goes through the game's
localization system, so a translation is one file and no code.

```bash
tools/translations.py template de     # start src/main/resources/locale/de.lang
tools/translations.py glossary de     # the game's own wording for terms these strings borrow
tools/translations.py check           # validate before opening a pull request
```

Use the language codes the game itself uses, which are the file names in `<install>/locale/`. They are not
all ISO codes: Swedish is `se`, not `sv`. A file named for anything else is either ignored or, worse, loaded
as the wrong language, because the loader matches by file name suffix and `broken.lang` ends with `en.lang`.

Three things matter more than the wording:

- **Keep every `<placeholder>` exactly as written.** They are substituted at runtime, so dropping `<count>`
  does not read awkwardly, it silently removes the number the sentence was reporting. This is enforced by a
  test rather than left to review.
- **Do not add or rename keys.** A key that is not in `en.lang` is read by nothing.
- **Partial is fine.** The game falls back to English per key, so an incomplete file works and can be
  merged. `check` reports coverage rather than failing on it.

Run the glossary before translating. These strings deliberately reuse Necesse's own vocabulary, and the game
ships official translations of all of it, so taking the wording from there means a player reads the same term
in this mod as on the bench they are standing at. The glossary prints one of our sentences alongside each
term, because a game term does not always carry our sense of it -- the game's "Content" is a settler's mood,
not a container's contents.

## Commits

Conventional commits: `type(scope): summary`, under 72 characters, present tense. Small and
logical.

Explain **why** in the body, not what — the diff already says what. Record what you verified and
how, and be explicit about what you did not: "compiles but never run in game" is far more useful
than silence. If a test corrected an assumption you held, say so; that is the most valuable thing
a commit message can carry.
