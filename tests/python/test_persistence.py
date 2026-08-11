"""Persistence across a save and a reload.

Ported from `tests/scenarios/persistence/`, which needed two server boots driven by a shell script.
The Python client can restart a server now, so this is one test that reads top to bottom instead of a
pair of files whose numbers had to be kept in step by hand.

**This test costs a boot**, which is by far the most expensive thing in the suite. That is the reason
it builds four different networks and asserts all of them in one pass rather than being split up: the
restart is the expensive part, so one restart should carry everything that needs one.

What actually has to survive is narrow, and worth stating because most of the mod deliberately
persists nothing: unit inventories are the state. Membership is not stored at all — it is recomputed
from the layout on every call — so what is really being checked is that the objects and their
inventories come back, and that the walk over them still reaches the same conclusions.
"""

from __future__ import annotations


def test_networks_and_their_contents_survive_a_restart(storage):
    # A plain pair of units.
    storage.place("terminal", 0, 0)
    storage.place("unit", 1, 0)
    storage.place("unit", 2, 0)
    storage.fill(1, 0, "ironbar", 25)
    storage.fill(2, 0, "ironbar", 25)

    # A conduit run, so reach is exercised and not just adjacency.
    storage.place("terminal", 0, 6)
    storage.place("conduit", 1, 6)
    storage.place("conduit", 2, 6)
    storage.place("unit", 3, 6)
    storage.fill(3, 6, "stone", 20)

    # A block, where multi-path traversal must still not double count after a reload.
    storage.place("terminal", 0, 10)
    for x, y in [(1, 10), (2, 10), (2, 11), (1, 11)]:
        storage.place("unit", x, y)

    storage.fill(1, 10, "ironbar", 10)

    # And one far from spawn, because the area around spawn is what gets cleared and reloaded first;
    # a network in a region that has to be loaded on demand is the interesting case.
    storage.place("terminal", -40, -40)
    storage.place("unit", -39, -40)
    storage.fill(-39, -40, "stone", 5)

    before = {
        "pair": (storage.query("units", 0, 0)["units"], storage.query("item", 0, 0, "ironbar")["count"]),
        "conduit": (storage.query("units", 0, 6)["units"], storage.query("item", 0, 6, "stone")["count"]),
        "block": (storage.query("units", 0, 10)["units"], storage.query("item", 0, 10, "ironbar")["count"]),
        "far": (storage.query("units", -40, -40)["units"], storage.query("item", -40, -40, "stone")["count"]),
        "totals": (storage.query("total", "ironbar")["count"], storage.query("total", "stone")["count"]),
    }
    assert before == {
        "pair": (2, 50),
        "conduit": (1, 20),
        "block": (4, 10),
        "far": (1, 5),
        "totals": (60, 25),
    }, "the state to be saved is not what this test intended to build"

    storage.restart()

    after = {
        "pair": (storage.query("units", 0, 0)["units"], storage.query("item", 0, 0, "ironbar")["count"]),
        "conduit": (storage.query("units", 0, 6)["units"], storage.query("item", 0, 6, "stone")["count"]),
        "block": (storage.query("units", 0, 10)["units"], storage.query("item", 0, 10, "ironbar")["count"]),
        "far": (storage.query("units", -40, -40)["units"], storage.query("item", -40, -40, "stone")["count"]),
        "totals": (storage.query("total", "ironbar")["count"], storage.query("total", "stone")["count"]),
    }

    assert after == before
