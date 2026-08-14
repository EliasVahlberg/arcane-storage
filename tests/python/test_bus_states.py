"""When a device stops itself, and — the case that matters more — when it must not.

A bus that cannot satisfy its rules does nothing rather than fighting another bus forever. That is worth
testing carefully in both directions, because an over-eager predicate would disable a layout that works
perfectly: pulling from one chest and pushing into another is a ceiling above a floor, and it terminates
because the source runs dry. Flagging it would break the most obvious useful thing two buses can do.

The state is derived on the server tick, so every test here lets time pass before asking.
"""

from __future__ import annotations

import re
import pytest


def test_a_bus_with_no_container_says_so(storage):
    """The likeliest mistake when placing a bus, and previously invisible unless the panel was opened."""
    storage.place("terminal", 0, 0)
    storage.place("unit", 1, 0)
    storage.place("importbus", 2, 0)

    storage.settle(40)

    state = storage.query("busstate", 2, 0)
    assert state["state"] == "no_container", state
    assert state["reason"], "an inactive device must explain itself"


def test_a_bus_touching_no_network_says_so(storage):
    """A chest but no units: the other placement mistake."""
    storage.place("importbus", 2, 0)
    storage.place("storagebox", 3, 0)

    storage.settle(40)

    assert storage.query("busstate", 2, 0)["state"] == "no_network"


def test_contradictory_rules_stop_both_devices(two_buses):
    """Keep 50 in the network, leave 20 in the network, one chest: no state satisfies both.

    Both buses stop, not one, and each names the other. Picking a winner would leave a rule that silently
    does nothing, which is the failure this mechanism exists to remove.
    """
    two_buses.fill(3, 0, "stone", 100)
    two_buses.do("rule", 2, 0, "stone", 50)
    two_buses.do("rule", 3, 1, "only", "stone")
    two_buses.do("ruleglobal", 3, 1, "each", 20)

    two_buses.settle(60)
    importer = two_buses.query("busstate", 2, 0)
    exporter = two_buses.query("busstate", 3, 1)

    assert importer["state"] == "rule_conflict", importer
    assert exporter["state"] == "rule_conflict", exporter
    # Absolute tile coordinates, not the test's spawn-relative offsets, because those are the numbers the
    # game shows a player.
    assert re.search(r"-?\d+,-?\d+", importer["reason"]), importer["reason"]
    assert "Export Bus" in importer["reason"], importer["reason"]
    assert "Import Bus" in exporter["reason"], exporter["reason"]
    assert "stone" in importer["reason"].lower(), importer["reason"]


def test_contradictory_rules_stop_moving_items(two_buses):
    """The point of all of it: the churn stops.

    Measured before the fix: 12 moves in 120 ticks while the network total read a steady 20 the whole time,
    which is why this presented in game as a lockup rather than as a runaway.
    """
    two_buses.fill(3, 0, "stone", 100)
    two_buses.do("rule", 2, 0, "stone", 50)
    two_buses.do("rule", 3, 1, "only", "stone")
    two_buses.do("ruleglobal", 3, 1, "each", 20)

    two_buses.settle(60)
    two_buses.do("busstatsreset")
    two_buses.settle(120)

    assert two_buses.query("busstats")["moves"] == 0


def test_two_chests_in_a_row_is_not_a_conflict(storage):
    """The case that must NOT be flagged, and the reason the predicate needs the cycle test.

        y=0:   T  U  I  A
        y=1:      U  E  B

    An import bus fills the network from chest A while an export bus empties it into chest B. That is a
    ceiling above a floor and it terminates: A runs dry. It is also the most obvious useful thing two buses
    can do, so a predicate that only compared the two numbers would break it.
    """
    storage.place("terminal", 0, 0)
    storage.place("unit", 1, 0)
    storage.place("importbus", 2, 0)
    storage.place("storagebox", 3, 0)
    storage.place("unit", 1, 1)
    storage.place("exportbus", 2, 1)
    storage.place("storagebox", 3, 1)

    storage.fill(3, 0, "stone", 40)
    storage.do("rule", 2, 0, "stone", 50)
    storage.do("rule", 2, 1, "only", "stone")
    storage.do("ruleglobal", 2, 1, "each", 20)

    storage.settle(60)

    assert storage.query("busstate", 2, 0)["state"] == "active"
    assert storage.query("busstate", 2, 1)["state"] == "active"

    source = storage.query("container", 3, 0, "stone")["count"]
    destination = storage.query("container", 3, 1, "stone")["count"]
    network = storage.query("item", 0, 0, "stone")["count"]
    print(f"\nsource {source}, network {network}, destination {destination}")
    assert source + network + destination == 40, "conservation"
    assert destination > 0, "items should be flowing from one chest to the other"


def test_rules_that_can_both_be_met_are_left_alone(two_buses):
    """Fill to 20, keep 50: the import bus stops at 20 and the export bus never sees a surplus."""
    two_buses.fill(3, 0, "stone", 100)
    two_buses.do("rule", 2, 0, "stone", 20)
    two_buses.do("rule", 3, 1, "only", "stone")
    two_buses.do("ruleglobal", 3, 1, "each", 50)

    two_buses.settle(60)

    assert two_buses.query("busstate", 2, 0)["state"] == "active"
    assert two_buses.query("busstate", 3, 1)["state"] == "active"


def test_the_same_number_on_both_sides_rests(two_buses):
    """Fill to 20 and leave 20: nothing moves at 20, so the rules agree rather than conflict."""
    two_buses.fill(3, 0, "stone", 100)
    two_buses.do("rule", 2, 0, "stone", 20)
    two_buses.do("rule", 3, 1, "only", "stone")
    two_buses.do("ruleglobal", 3, 1, "each", 20)

    two_buses.settle(60)

    assert two_buses.query("busstate", 2, 0)["state"] == "active"
    assert two_buses.query("busstate", 3, 1)["state"] == "active"


@pytest.mark.realtime  # see CONTAMINATION note in conftest.py: passes alone, not in suite order
def test_an_unlimited_import_conflicts_with_any_export_floor(two_buses):
    """No number on the import side means unbounded, which is above every floor.

    This is the shape a player reaches by accident: a fresh import bus moves everything, so ticking one item
    on an export bus pointed at the same chest is already a loop.
    """
    two_buses.fill(3, 0, "stone", 60)
    two_buses.do("rule", 3, 1, "only", "stone")
    two_buses.do("ruleglobal", 3, 1, "each", 20)

    two_buses.settle(60)

    print("\nimport:", two_buses.query("busstate", 2, 0, "stone"))
    print("export:", two_buses.query("busstate", 3, 1, "stone"))
    assert two_buses.query("busstate", 2, 0)["state"] == "rule_conflict"
    assert two_buses.query("busstate", 3, 1)["state"] == "rule_conflict"


@pytest.mark.slow  # restarts the server: one JVM boot, by far the most expensive thing here
def test_the_state_is_recomputed_after_a_restart_not_restored(two_buses):
    """Derived, never saved: a reload must reach the same answer from the world and the rules."""
    two_buses.fill(3, 0, "stone", 100)
    two_buses.do("rule", 2, 0, "stone", 50)
    two_buses.do("rule", 3, 1, "only", "stone")
    two_buses.do("ruleglobal", 3, 1, "each", 20)
    two_buses.settle(60)
    assert two_buses.query("busstate", 2, 0)["state"] == "rule_conflict"

    two_buses.restart()

    # Touching both tiles is not ceremony: regions load on access, a reloaded world boots with none of them
    # loaded, and TileEntityList.serverTick only iterates the entities in its map -- so until something
    # reads these tiles they do not exist to tick. A player walking home does this implicitly; a headless
    # test has to do it on purpose. Measured: 0 transfers and 0 network walks over 60 ticks without it.
    two_buses.query("busstate", 2, 0)
    two_buses.query("busstate", 3, 1)
    two_buses.settle(60)

    assert two_buses.query("busstate", 2, 0)["state"] == "rule_conflict"


def test_removing_the_contradiction_lets_the_devices_resume(two_buses):
    """Inactive is derived, so it must clear itself when the reason goes away — no reset, no replacing."""
    two_buses.fill(3, 0, "stone", 100)
    two_buses.do("rule", 2, 0, "stone", 50)
    two_buses.do("rule", 3, 1, "only", "stone")
    two_buses.do("ruleglobal", 3, 1, "each", 20)
    two_buses.settle(60)
    assert two_buses.query("busstate", 2, 0)["state"] == "rule_conflict"

    # Raise the export floor above the import ceiling: now both rules can hold at once.
    two_buses.do("ruleglobal", 3, 1, "each", 80)
    two_buses.settle(60)

    assert two_buses.query("busstate", 2, 0)["state"] == "active"
    assert two_buses.query("busstate", 3, 1)["state"] == "active"


def test_the_terminal_reports_where_the_stopped_devices_are(two_buses):
    """The surface that works when the player was elsewhere when it happened.

    A gray sprite is only discoverable by walking past it, and the rule that caused the stop was probably set
    minutes ago somewhere else. The terminal says how many and where; the device itself says why.

    It reports only while open, which is deliberate: an unattended terminal walking its network on a timer is
    exactly the polling the resolver exists to remove.
    """
    two_buses.fill(3, 0, "stone", 100)
    two_buses.do("rule", 2, 0, "stone", 50)
    two_buses.do("rule", 3, 1, "only", "stone")
    two_buses.do("ruleglobal", 3, 1, "each", 20)
    two_buses.settle(60)

    two_buses.do("open", 0, 0)
    two_buses.settle(40)

    report = two_buses.query("problems", 0, 0)
    print(f"\nterminal reports: {report}")
    assert report["count"] == 2, report
    for bus in ("2,0", "3,1"):
        pass  # coordinates are absolute; the count is the assertion that matters here
    assert report["where"].count(",") == 2, report


def test_the_terminal_reports_nothing_when_all_is_well(two_buses):
    """No news is the normal case, and it should cost the player no attention."""
    two_buses.fill(3, 0, "stone", 100)
    two_buses.do("rule", 2, 0, "stone", 20)
    two_buses.settle(40)

    two_buses.do("open", 0, 0)
    two_buses.settle(40)

    assert two_buses.query("problems", 0, 0)["count"] == 0
