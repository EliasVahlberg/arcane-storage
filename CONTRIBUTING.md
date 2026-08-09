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
make scenarios    # every scenario against a real headless server, plus a save/load restart
make persistence  # just the two-boot restart pair
```

`make scenarios` boots the real dedicated server twice and takes about 20 seconds.

### Scenarios are data, not Java

A scenario is a list of chat commands under `tests/scenarios/`, run by
`/arcanestorage run <name>` or by the harness:

```
arcanestorage place terminal 0 0
arcanestorage place unit 1 0
arcanestorage give 0 0 ironbar 100
arcanestorage expect units 0 0 1
arcanestorage expect item 0 0 ironbar 100
```

Verbs: `place fill clear reset break report expect give open close withdraw deposit depositall
quickstack restock click run echo`. `expect` kinds: `units item capacity fits mask total held`.
Coordinates are relative to world spawn, so scenarios are world-independent.

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

## Commits

Conventional commits: `type(scope): summary`, under 72 characters, present tense. Small and
logical.

Explain **why** in the body, not what — the diff already says what. Record what you verified and
how, and be explicit about what you did not: "compiles but never run in game" is far more useful
than silence. If a test corrected an assumption you held, say so; that is the most valuable thing
a commit message can carry.
