# Testing layout — two suites, and why

There are two automated suites plus a manual backlog. The split is deliberate, but it was only
recorded in one docstring until now, which is why it looked like drift.

```
make pytest      32 tests   ~29s   tests/python/*.py
make scenarios    8 + 1     ~35s   tests/scenarios/*.txt
docs/QA_BACKLOG.md                 everything visual — Elias runs these by hand
```

Neither suite touches a form. Every UI change in this mod is unverified until it is looked at.

## What each is for

**pytest** is for anything that needs to compute, parametrise, or wait. It gets a real server, a real
player and the mod's own container, and it reads state back through query verbs. Prefer it by default:
its failure messages name which side was wrong, and `@pytest.mark.parametrize` covers three kinds of
non-station in three lines.

**Scenario files** are for the three things pytest cannot do here:

1. **They run in a live game.** `harness run <name>` executes one from a server console or from
   in-game chat, so a scenario doubles as a setup script for visual QA — `make run HARNESS=1`, then
   run one to build the scene you want to look at. A pytest file cannot be handed to a running game.
2. **They survive a restart.** The pytest server is a session fixture: one boot for the whole suite,
   so save-and-reload cannot be expressed in it. `tests/scenarios/persistence/` is two files across
   two boots — write last in the first boot so the shutdown save catches it, then verify in a second.
3. **They need no venv.** `tools/run_scenario.sh` is bash and the harness jar. Useful when the Python
   environment is the thing under suspicion.

## The overlap is intentional, in two files

`capacity.txt` and `container_roundtrip.txt` are now largely covered by `tests/python/test_network.py`.
They are kept rather than deleted because of reason 1 above: they are the scripts to run in a live
world when checking the same behaviour by eye. `container_transfers.txt` is only *partly* covered —
deposit-all is in pytest, quick-stack and restock are not.

Unique to scenarios: `topology.txt`, `multipath.txt`, `conduits.txt`, `performance.txt` (which asserts
a per-call budget against the largest network the mod allows), the persistence pair, and quick-stack
and restock.

## Where to put a new test

- Needs a number computed, several cases, or a wait → pytest.
- Needs to be run inside a live game, or across a restart → scenario.
- Is about what something looks like → `docs/QA_BACKLOG.md`, and say so in the commit rather than
  implying it was verified.

## Known gap

Nothing in pytest covers persistence, because the server fixture is session-scoped. Fixing that means
teaching the Python client to restart a server, which is on the harness's own backlog. Until then the
scenario pair is the only save/reload coverage, and it is worth keeping green for that reason alone.
