"""Station Units — sockets as a placed resource rather than a constant.

Sockets used to live on the terminal: ten of them, always there, free. They now live on a Station Unit,
which is a block the player crafts and places on the network like a Storage Unit. These tests cover the
three things that change as a result — that sockets come and go with the unit, that a network with none
is a working network rather than a broken one, and that two units address their sockets in an order both
sides can derive independently.

That last one is the only part here that is subtle, and it is the reason the design has a sort in it. A
slot index is what the client sends when it moves a bench, but each side discovers network membership by
walking the network itself, and a walk's visit order depends on where it started. Sorting by tile is what
makes an index mean the same thing to both sides without either asking the other.
"""

from __future__ import annotations

import pytest


def test_a_network_with_no_station_unit_can_still_craft_by_hand(storage):
    """Zero sockets is a normal state. It has to be: it is where every network starts."""
    storage.place("terminal", 0, 0)
    storage.place("unit", 1, 0)
    storage.settle(5)

    assert storage.query("stations", 0, 0)["sockets"] == 0

    # torch is a hand recipe -- @inventory, i.e. RecipeTechRegistry.NONE -- so it needs no bench and must
    # stay available. It also needs a sapling, which is the sort of detail that turns a passing test into a
    # misleading one: without it the craft fails for want of an ingredient and looks like a refusal.
    storage.fill(1, 0, "oaklog", 10)
    storage.fill(1, 0, "oaksapling", 10)
    storage.open(0, 0)
    reply = storage.call("craft", "torch")

    assert reply.ok is True, "hand recipes must not depend on owning a Station Unit"


def test_a_station_recipe_is_refused_until_a_station_unit_exists(storage):
    """The bench has nowhere to go, so the recipe it would unlock stays refused."""
    storage.place("terminal", 0, 0)
    storage.place("unit", 1, 0)
    storage.fill(1, 0, "oaklog", 8)
    storage.open(0, 0)
    storage.settle(5)

    refused = storage.call("install", "workstation")
    assert refused.ok is False, "there is no socket to install into"

    assert storage.call("craft", "storagebox").ok is False


def test_placing_a_station_unit_adds_its_socket(storage):
    """One unit, one socket at the base tier. The ladder to 8 arrives with tiering."""
    storage.place("terminal", 0, 0)
    storage.place("unit", 1, 0)
    storage.settle(5)
    assert storage.query("stations", 0, 0)["sockets"] == 0

    storage.place("stationunit", 0, 1)
    storage.settle(5)

    assert storage.query("stations", 0, 0)["sockets"] == 1


def test_a_second_station_unit_adds_a_second_socket(storage):
    """Capacity scales by placing more, which is the whole point of moving sockets off the terminal."""
    storage.place("terminal", 0, 0)
    storage.place("unit", 1, 0)
    storage.place("stationunit", 0, 1)
    storage.place("stationunit", 1, 1)
    storage.settle(5)

    assert storage.query("stations", 0, 0)["sockets"] == 2


def test_a_station_unit_reached_through_a_conduit_still_counts(storage):
    """Connectivity is the same rule as for a Storage Unit, deliberately: nothing new to learn.

        y=0:   T  U  c  c  S
    """
    storage.place("terminal", 0, 0)
    storage.place("unit", 1, 0)
    storage.place("conduit", 2, 0)
    storage.place("conduit", 3, 0)
    storage.place("stationunit", 4, 0)
    storage.settle(5)

    assert storage.query("stations", 0, 0)["sockets"] == 1


def test_a_station_unit_off_the_network_does_not_count(storage):
    """Two tiles clear of anything, so nothing conducts to it."""
    storage.place("terminal", 0, 0)
    storage.place("unit", 1, 0)
    storage.place("stationunit", 5, 5)
    storage.settle(5)

    assert storage.query("stations", 0, 0)["sockets"] == 0


def test_breaking_the_station_unit_takes_its_socket_and_its_bench(storage):
    """The bench drops rather than vanishing: it is the player's property."""
    storage.place("terminal", 0, 0)
    storage.place("unit", 1, 0)
    storage.place("stationunit", 0, 1)
    storage.fill(1, 0, "oaklog", 8)
    storage.open(0, 0)
    storage.settle(5)
    storage.do("install", "workstation")

    assert storage.do("craft", "storagebox").ok is True

    storage.do("break", 0, 1)
    storage.settle(5)

    assert storage.query("stations", 0, 0)["sockets"] == 0
    assert storage.call("craft", "storagebox").ok is False, "the Workstation went with the unit"


def test_sockets_are_addressed_in_tile_order_not_discovery_order(storage):
    """Both sides sort by tile, so an index means the same thing to each without asking the other.

        y=0:   T  U
        y=1:   S1
        y=2:   S2   <- reached through S1, so discovered second either way

    The assertion is about the reported order rather than about a click, because a headless test cannot
    click. What it can check is that the order the container publishes is the tile order and not the
    order the walk happened to visit -- which is the property the client relies on.
    """
    storage.place("terminal", 0, 0)
    storage.place("unit", 1, 0)
    storage.place("stationunit", 0, 2)
    storage.place("stationunit", 0, 1)
    storage.settle(5)

    reported = storage.query("stations", 0, 0)

    # Compared as numbers, not as the strings they arrive as. A tile key is tileX shifted into the high
    # word, so near spawn these are thirteen-digit values and a lexicographic comparison would agree with
    # a numeric one right up until two coordinates differed in digit count -- a test that passes for the
    # wrong reason until the day it does not.
    order = [int(value) for value in reported["order"]]

    assert reported["sockets"] == 2
    assert order == sorted(order), (
        "sockets must be published in tile order regardless of the order units were placed or walked"
    )


def test_an_upgraded_bench_covers_the_lower_one_so_a_socket_is_freed(storage):
    """The composition that makes tiering worth having, checked at the base of the ladder.

    A Demonic Workstation reports the Workstation tech too, so a network that installed both is wasting a
    socket. This is the behaviour the roadmap claims makes upgrading feel like tidying rather than merely
    growing, so it is worth pinning before the upper rungs exist.
    """
    storage.place("terminal", 0, 0)
    storage.place("unit", 1, 0)
    storage.place("stationunit", 0, 1)
    storage.fill(1, 0, "oaklog", 8)
    storage.open(0, 0)
    storage.settle(5)

    storage.do("install", "demonicworkstation")

    assert storage.do("craft", "storagebox").ok is True, (
        "a Demonic Workstation must cover plain Workstation recipes, or upgrading would cost a socket"
    )
