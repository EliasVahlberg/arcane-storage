"""What the buses do over time, rather than what one transfer computes.

Diagnostics rather than acceptance tests: they exist to characterise the "weird lockups" Elias saw in game,
and they are written to *report* numbers as well as assert, so a run says what happened. Every earlier bus
test drove `transferOnce` by hand, which verifies arithmetic and steps over scheduling entirely -- so nothing
here could have been caught before the harness could let time pass.

Two candidate causes, which need opposite fixes:

* perpetual motion, where two devices each do exactly what their rule says and undo each other, and
* work that is merely slow enough to look stuck, since a bus moves one item type per second.
"""

from __future__ import annotations

import pytest


@pytest.fixture
def two_buses(storage):
    """An import bus and an export bus on one network, both attached to the same chest.

        y=0:   T  U  I  C
        y=1:      U  c  E

    T terminal, U unit, I import bus, c conduit, E export bus, C chest. Both buses touch the chest, so both
    can move the same items, and both are on the network, so each sees what the other did.
    """
    storage.place("terminal", 0, 0)
    storage.place("unit", 1, 0)
    storage.place("importbus", 2, 0)
    storage.place("storagebox", 3, 0)
    storage.place("unit", 1, 1)
    storage.place("conduit", 2, 1)
    storage.place("exportbus", 3, 1)
    return storage


@pytest.mark.xfail(strict=True, reason="no termination guarantee yet: contradictory rules churn forever")
def test_two_rules_that_disagree_still_settle(two_buses):
    """The failure an event-driven rewrite would make faster rather than fix.

    An import bus told to keep 50 in the network and an export bus told to leave 20 there are each obeying
    their own rule, and together they describe no reachable state. Today the one-second interval throttles it
    to a move per bus per second, which is why it reads as a lockup rather than as a runaway -- and why making
    updates event-driven without a termination rule would make it worse.

    What a fix must produce: the system stops moving items, whether by hysteresis, by a single authority per
    item, or by refusing the contradiction and telling the player.
    """
    two_buses.fill(3, 0, "stone", 100)
    two_buses.do("rule", 2, 0, "stone", 50)
    two_buses.do("rule", 3, 1, "only", "stone")
    two_buses.do("ruleglobal", 3, 1, "each", 20)

    two_buses.settle(60)
    two_buses.do("busstatsreset")
    first = two_buses.query("item", 0, 0, "stone")["count"]

    two_buses.settle(120)
    moves = two_buses.query("busstats")["moves"]
    second = two_buses.query("item", 0, 0, "stone")["count"]

    print(f"\nnetwork held {first}, then {second}, after {moves} further moves")
    assert moves == 0, f"still churning: {moves} moves after the network should have settled"


def test_a_settled_network_stops_doing_work(storage):
    """The invariant a scheduler should give us, and the assertion that would prove it.

    One bus, one rule, nothing to do once it is satisfied. Moves must stop; scans currently do not, which is
    the cost side of polling.
    """
    storage.place("terminal", 0, 0)
    storage.place("unit", 1, 0)
    storage.place("importbus", 2, 0)
    storage.place("storagebox", 3, 0)
    storage.fill(3, 0, "stone", 40)

    storage.settle(60)
    storage.do("busstatsreset")
    storage.settle(60)

    stats = storage.query("busstats")
    print(f"\nafter settling: {stats}")
    assert stats["moves"] == 0, "nothing left to move"


@pytest.mark.xfail(strict=True, reason="one item type per bus per second; a mixed chest drains too slowly")
def test_how_long_a_mixed_chest_takes_to_drain(storage):
    """The other candidate: not stuck, just slow. One item type per bus per second.

    A chest holding a spread of items is the normal case -- it is what a settler fills -- so this is the
    number a player experiences.
    """
    storage.place("terminal", 0, 0)
    storage.place("unit", 1, 0)
    storage.place("importbus", 2, 0)
    storage.place("storagebox", 3, 0)
    items = ["stone", "ironbar", "oaklog", "clay", "copperbar", "goldbar", "quartz", "leather"]
    for item in items:
        storage.fill(3, 0, item, 10)

    storage.settle(100)

    left = {item: storage.query("container", 3, 0, item)["count"] for item in items}
    remaining = sum(left.values())
    print(f"\nafter 100 ticks (5 seconds) {remaining} of 80 items are still in the chest: {left}")
    assert remaining == 0, "five seconds is enough to drain eight item types"


@pytest.mark.xfail(strict=True, reason="polling: an idle bus rediscovers its network and rescans every second")
def test_what_polling_costs_while_nothing_happens(storage):
    """Idle cost, per bus, with nothing to do: the chest is adjacent and empty.

    An earlier version of this put the chest out of reach, which made the test pass for the wrong reason --
    the bus returned before ever walking the network. A bus with no attached container is not idle, it is
    disconnected.
    """
    storage.place("terminal", 0, 0)
    storage.place("unit", 1, 0)
    storage.place("importbus", 2, 0)
    storage.place("storagebox", 3, 0)

    storage.do("busstatsreset")
    storage.settle(100)

    stats = storage.query("busstats")
    print(f"\nidle for 100 ticks with one bus and an empty chest: {stats}")
    assert stats["walks"] <= 1, "an idle bus should not rediscover the network on a timer"
