"""What the network reports, over sizes a scenario file cannot express without copies of itself.

These do not replace `tests/scenarios/capacity.txt`. The scenario stays as the pasteable regression
record and still runs in the bash suite; this file exists for what a scenario cannot do --
parametrise over sizes, compare computed numbers, and say which side was wrong.
"""

from __future__ import annotations

import pytest

#: StorageUnitObject.SLOTS. Asserting against the constant rather than the number keeps these tests
#: honest if the capacity per unit ever changes: they should follow it, not contradict it.
SLOTS_PER_UNIT = 40


def test_a_lone_terminal_sees_no_units(storage):
    storage.place("terminal", 0, 0)
    assert storage.query("units", 0, 0)["units"] == 0
    assert storage.query("capacity", 0, 0) == {"used": 0, "total": 0}


@pytest.mark.parametrize("units", [1, 2, 5, 8])
def test_capacity_grows_one_unit_at_a_time(storage, units):
    """The claim is arithmetic, so check it as arithmetic rather than as four literals."""
    storage.place("terminal", 0, 0)
    for i in range(units):
        storage.place("unit", 1 + i, 0)

    capacity = storage.query("capacity", 0, 0)
    assert storage.query("units", 0, 0)["units"] == units
    assert capacity == {"used": 0, "total": units * SLOTS_PER_UNIT}


def test_capacity_counts_slots_not_items(terminal):
    """The bug this guards against was mine: I read 40 iron bars as 40 slots.

    A stack occupies one slot, so filling a unit with 40 of one item uses one slot, not forty. The
    scenario that asserted otherwise was wrong and the code was right.
    """
    terminal.harness.fill(1, 0, "ironbar", 40)
    assert terminal.capacity() == (1, SLOTS_PER_UNIT)
    assert terminal.count("ironbar") == 40


def test_the_network_aggregates_across_units(terminal):
    """The whole point of the mod, as one assertion: two units read as one inventory."""
    terminal.harness.place("unit", 2, 0)
    terminal.harness.fill(1, 0, "ironbar", 40)
    terminal.harness.fill(2, 0, "ironbar", 25)

    assert terminal.units() == 2
    assert terminal.count("ironbar") == 65


def test_an_empty_network_accepts_items(terminal):
    assert terminal.fits("ironbar") is True


def test_deposit_all_moves_the_players_items_into_the_network(terminal):
    before = terminal.count("stone")
    terminal.harness.give("stone", 30)
    terminal.open()
    terminal.deposit_all()

    assert terminal.harness.held("stone") == 0
    assert terminal.count("stone") == before + 30


def test_withdraw_returns_items_to_the_player(terminal):
    terminal.harness.fill(1, 0, "ironbar", 40)
    terminal.open()
    terminal.withdraw("ironbar", 10)

    assert terminal.harness.held("ironbar") == 10
    assert terminal.count("ironbar") == 30


def test_a_round_trip_conserves_items(terminal):
    """Conservation is the invariant worth generating sequences against later.

    Counted as network plus player, not network alone. The first version of this test asserted on the
    network total only, and failed with 50 against 40 -- correctly, because an earlier test had left
    ten iron bars in the player's inventory. Two lessons, both kept: the player is now replaced
    between tests, and a conservation check has to count everywhere an item can be, or it is really
    an assertion about one place.
    """
    def everywhere() -> int:
        return (terminal.harness.query("total", "ironbar")["count"]
                + terminal.harness.held("ironbar"))

    terminal.harness.fill(1, 0, "ironbar", 40)
    before = everywhere()

    terminal.open()
    terminal.withdraw("ironbar", 15)
    terminal.deposit_all()

    assert everywhere() == before
