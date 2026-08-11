# Testing layout

One automated suite, plus a manual backlog.

```
make pytest      53 tests   ~46s   tests/python/*.py     the whole automated suite
make test         unit      ~1s    src/test/java         game-independent logic only
docs/QA_BACKLOG.md                                       everything visual — Elias runs these by hand
```

Nothing automated touches a form. Every UI change in this mod is unverified until it is looked at, and
a commit should say so rather than implying otherwise.

## There used to be two suites, and that was a mistake

Until Aug 2026 there was also a bash suite of eight scenario files under `tests/scenarios/`, run by
`tools/run_scenario.sh`. It is gone; everything it asserted is now in `tests/python/`.

The reasons given for keeping both did not survive being questioned:

- *"Persistence needs two boots and pytest has a session-scoped server."* True of how the fixture was
  written, not of pytest. The Python client can restart a server now — `harness.restart()` stops it
  cleanly, so the world is saved, and boots it again on the same world — and `test_persistence.py` is
  one readable test instead of two files whose numbers had to be kept in step by hand.
- *"The bash runner needs no venv."* Bash has its own failure modes; this was a preference dressed up
  as a constraint.
- *"Scenario files can be run inside a live game."* This one is real, but it argues for keeping the
  *file format*, not a parallel assertion suite. `harness run <name>` still exists, and
  `make scenario FILE=...` still runs an ad-hoc file, so a throwaway script to set up a scene for
  visual QA is still one command. What is gone is a second set of assertions to keep in step.

Two suites means two places to update when behaviour changes, two failure formats to read, and a
standing question about which one owns a given rule. That cost is real and the benefits were not.

## What the suite covers

| File | What it pins |
|---|---|
| `test_topology.py` | What counts as one network: rows, gaps, diagonals, breaks, two terminals, and a block that must not be double-counted |
| `test_conduits.py` | Reach without capacity, and the frame mask (bit0=N, bit1=E, bit2=S, bit3=W) |
| `test_network.py` | Capacity accounting, aggregation, deposit and withdraw round trips |
| `test_transfers.py` | Quick-stack, deposit-all, restock, and the aggregation budget at 64 units |
| `test_crafting.py` | Crafting from network contents, including through a conduit run, and refusing to part-craft |
| `test_stations.py` | Installed stations gate recipes; fueled stations are refused |
| `test_cursor_clicks.py` | The server half of the click conventions, and item conservation |
| `test_in_use.py` | The "someone is using me" state other clients render from, including its two-second expiry |
| `test_persistence.py` | Everything above surviving a save and a reload. Costs a boot, so it asserts four networks in one pass |

## Where to put a new test

- Anything automatable → `tests/python/`.
- Anything about what something looks like → `docs/QA_BACKLOG.md`.
- A throwaway script to build a scene to look at → any `.txt` file plus `make scenario FILE=...`, or
  `harness run <name>` in a live game with `make run HARNESS=1`. Not a test, and not checked in.

## The one thing to know about isolation

The `harness` fixture clears a radius around spawn and empties the player between tests; the `storage`
fixture also walks the object entities and removes every storage object, wherever it is. A test that
places something far from spawn should use `storage`, not `harness` — the radius will not reach it, and
the level-wide totals will notice.
