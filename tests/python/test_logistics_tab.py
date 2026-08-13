"""The logistics tab's data and its write path.

The tab itself is client-side and no test here can look at it. What can be tested is everything it draws from
and everything it writes through: the terminal's survey of its network, and the route a rule set takes when it
is written from the terminal rather than from the bus. Those are the parts that can be wrong without being
visibly wrong.

The membership check is the one with teeth. The tab addresses buses by coordinate, so without it a crafted
packet could rewrite a bus anywhere on the level.
"""

import pytest

from necesse_harness.process import HarnessError


def logistics(storage):
    """The two_buses layout, which puts both directions on one chest, with a terminal to survey it.

        y=0:   T  U  I  C
        y=1:      U  c  E
    """
    storage.place("terminal", 0, 0)
    storage.place("unit", 1, 0)
    storage.place("importbus", 2, 0)
    storage.place("storagebox", 3, 0)
    storage.place("unit", 1, 1)
    storage.place("conduit", 2, 1)
    storage.place("exportbus", 3, 1)
    storage.settle(20)


def test_the_survey_lists_every_bus_not_only_the_broken_ones(storage):
    """The tab configures devices as well as reporting on them, so it must list working ones too."""
    logistics(storage)
    storage.open(0, 0)
    storage.settle(25)

    buses = storage.query("buses", 0, 0)
    print(f"\nsurvey: {buses['list']}")
    assert buses["count"] == 2, "both buses are on the network"
    assert buses["stopped"] == 0
    assert "import" in buses["list"] and "export" in buses["list"]


def test_the_survey_is_in_tile_order(storage):
    """Rows must keep their places between surveys, or a player loses the one they were reading."""
    logistics(storage)
    storage.open(0, 0)
    storage.settle(25)

    first = storage.query("buses", 0, 0)["list"]
    storage.settle(40)
    assert storage.query("buses", 0, 0)["list"] == first, "the order must not depend on when it was surveyed"


def test_a_stopped_bus_is_named_with_its_reason(storage):
    """What the red panel is built from: which device, and why."""
    logistics(storage)
    # A ceiling above a floor on a shared container: the two buses would undo each other forever.
    storage.do("rule", 3, 1, "only", "stone")
    storage.do("ruleglobal", 3, 1, "each", 20)
    storage.do("rule", 2, 0, "only", "stone")
    storage.do("ruleglobal", 2, 0, "each", 50)
    storage.open(0, 0)
    storage.settle(30)

    buses = storage.query("buses", 0, 0)
    print(f"\nafter a contradiction: {buses['list']}")
    assert buses["stopped"] == 2, "both sides of a contradiction stop, not one"
    assert "rule_conflict" in buses["list"]

    problems = storage.query("problems", 0, 0)
    assert problems["count"] == 2, "and the banner counts the same devices"


def test_rules_written_from_the_terminal_are_validated_the_same_way(storage):
    """There must be no way to reach a contradictory configuration by choosing the easier interface."""
    logistics(storage)
    storage.do("rule", 3, 1, "only", "stone")
    storage.do("ruleglobal", 3, 1, "each", 20)
    storage.open(0, 0)
    storage.settle(25)

    # Accepted through the terminal: agrees with the export bus's floor.
    storage.do("terminalrules", 0, 0, 2, 0, "stone", 20, "accepted")

    # Refused through the terminal: a ceiling above that floor, on a container both buses touch.
    storage.do("terminalrules", 0, 0, 2, 0, "stone", 50, "refused")


def test_a_refusal_from_the_terminal_applies_nothing(storage):
    """Atomicity holds on this route too, or the two interfaces would not mean the same thing."""
    logistics(storage)
    storage.do("rule", 3, 1, "only", "stone")
    storage.do("ruleglobal", 3, 1, "each", 20)
    storage.do("rule", 2, 0, "only", "ironbar")
    storage.open(0, 0)
    storage.settle(25)

    before = storage.query("busstate", 2, 0, "stone")
    assert not before["allows"], "stone starts unticked on the import bus"

    storage.do("terminalrules", 0, 0, 2, 0, "stone", 50, "refused")
    after = storage.query("busstate", 2, 0, "stone")
    print(f"\nstone after a refusal from the terminal: allows={after['allows']} target={after['target']}")
    assert not after["allows"], "a refused set must not have ticked the item anyway"
    assert after["target"] == before["target"]


def test_the_terminal_will_not_write_a_bus_that_is_not_on_its_network(storage):
    """The membership check. Without it, coordinates alone would be authority."""
    logistics(storage)

    # A second, unconnected network with its own bus, four tiles clear of the first.
    storage.place("importbus", 8, 0)
    storage.place("storagebox", 9, 0)
    storage.open(0, 0)
    storage.settle(25)

    assert storage.query("buses", 0, 0)["count"] == 2, "the far bus is not on this terminal's network"
    storage.do("terminalrules", 0, 0, 8, 0, "stone", 20, "notfound")


def test_the_survey_follows_the_network_as_it_changes(storage):
    """A device list that went stale would send a player to look at a bus that is no longer there."""
    logistics(storage)
    storage.open(0, 0)
    storage.settle(25)
    assert storage.query("buses", 0, 0)["count"] == 2

    storage.do("break", 3, 1)
    storage.settle(25)
    assert storage.query("buses", 0, 0)["count"] == 1, "a broken bus leaves the list"
