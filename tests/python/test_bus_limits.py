"""Every limit the rule panel can express, in both directions.

Two wrong versions of this preceded the tests, both found in game and neither by a unit test. The first
honoured only the two "each item" limit modes, and since the panel's default is TOTAL_ITEMS the number a
player typed was read, saved, and silently discarded. The second took the whole-container modes literally
and capped the network's *entire* item count: in a network holding more than the number that leaves zero
headroom, so an import bus stopped dead and an export bus treated everything as surplus.

A bus's number is therefore per item, whatever mode the filter carries, and the panel no longer offers the
mode dropdown. The number is measured on the **network** in both directions, per D24: an import bus fills the
network to it, an export bus leaves that much in the network and ships the rest. It is deliberately not a cap
on the target chest.

`ruleglobal` sets the panel-wide number, `rulecategory` limits a whole category, and neither could be set
headlessly before -- which is exactly why both shipped broken.

Each test sets its rules before putting anything in a chest, and then lets time pass rather than poking a bus
by hand. That ordering is not incidental: since the scheduler landed, a bus acts as soon as something changes,
so a rule set after the items arrive is a rule set after the work is already done -- which is also the order a
player would use, configuring a bus and then filling the chest.
"""

from __future__ import annotations

import pytest


@pytest.fixture
def wired(storage):
    """Terminal, one unit, and a chest at 3,0 for a bus at 2,0 to trade with.

        y=0:   T  U  ?  C
    """
    storage.place("terminal", 0, 0)
    storage.place("unit", 1, 0)
    storage.place("storagebox", 3, 0)
    return storage


def test_an_import_bus_fills_the_network_to_the_panel_limit(wired):
    """Elias's case exactly: a number typed into the panel, in the mode the panel starts in."""
    wired.place("importbus", 2, 0)
    wired.do("ruleglobal", 2, 0, "total", 20)
    wired.fill(3, 0, "stone", 200)

    wired.settle(20)

    assert wired.query("item", 0, 0, "stone")["count"] == 20, "the network fills to 20 and stops"
    assert wired.query("container", 3, 0, "stone")["count"] == 180, "the rest stays in the chest"


def test_an_export_bus_leaves_the_amount_in_the_network(wired):
    """The mirror, and D24's reserve floor: what the number protects is the network, not the chest."""
    wired.place("exportbus", 2, 0)
    wired.do("rule", 2, 0, "only", "stone")
    wired.do("ruleglobal", 2, 0, "total", 20)
    wired.fill(1, 0, "stone", 200)

    wired.settle(20)

    assert wired.query("item", 0, 0, "stone")["count"] == 20, "the network keeps 20 and ships the rest"
    assert wired.query("container", 3, 0, "stone")["count"] == 180


def test_a_full_network_does_not_block_an_import_with_a_limit(wired):
    """The regression that mattered. Elias set an amount and the import stopped importing entirely, because
    the number was read as a cap on everything the network held rather than on the item being moved."""
    wired.place("importbus", 2, 0)
    wired.do("ruleglobal", 2, 0, "total", 20)
    wired.fill(3, 0, "stone", 100)
    wired.fill(1, 0, "ironbar", 500)

    wired.settle(20)

    assert wired.query("item", 0, 0, "stone")["count"] == 20, \
        "500 iron bars elsewhere are irrelevant to how much stone the network should hold"


def test_the_number_means_the_same_thing_in_every_mode(wired):
    """The panel no longer offers the mode dropdown, but a filter saved earlier can carry any mode, and none
    of them may turn the number into a no-op or a blockade again."""
    wired.place("importbus", 2, 0)

    for mode in ["total", "each"]:
        wired.do("reset")
        wired.place("terminal", 0, 0)
        wired.place("unit", 1, 0)
        wired.place("importbus", 2, 0)
        wired.place("storagebox", 3, 0)
        wired.do("ruleglobal", 2, 0, mode, 20)
        wired.fill(3, 0, "stone", 100)
        wired.fill(1, 0, "ironbar", 500)

        wired.settle(20)

        assert wired.query("item", 0, 0, "stone")["count"] == 20, f"mode {mode} capped stone at 20"


def test_the_each_item_mode_gives_every_item_its_own_allowance(wired):
    """The mode that already worked, pinned now that the others do too."""
    wired.place("importbus", 2, 0)
    wired.do("ruleglobal", 2, 0, "each", 20)
    wired.fill(3, 0, "stone", 100)
    wired.fill(1, 0, "ironbar", 15)

    wired.settle(20)

    assert wired.query("item", 0, 0, "stone")["count"] == 20, "iron bars are counted separately"


def test_a_stacks_mode_from_an_older_filter_still_means_something(wired):
    """An iron pickaxe does not stack, so three stacks of it is three items and needs no assumption about
    any item's stack size."""
    wired.place("importbus", 2, 0)
    wired.do("ruleglobal", 2, 0, "eachstacks", 3)
    wired.fill(3, 0, "ironpickaxe", 10)

    wired.settle(20)

    assert wired.query("item", 0, 0, "ironpickaxe")["count"] == 3


def test_the_tightest_limit_wins(wired):
    """A per-item limit under a looser panel limit, folded the way vanilla folds its counters."""
    wired.place("importbus", 2, 0)
    wired.do("ruleglobal", 2, 0, "each", 100)
    wired.do("rule", 2, 0, "stone", 30)
    wired.fill(3, 0, "stone", 200)

    wired.settle(20)

    assert wired.query("item", 0, 0, "stone")["count"] == 30


def test_a_category_limit_applies_to_everything_in_it(wired):
    """A limit on a category, named by an item in it. The buses ignored these entirely before now."""
    wired.place("importbus", 2, 0)
    wired.do("rulecategory", 2, 0, "stone", 25)
    wired.fill(3, 0, "stone", 100)

    wired.settle(20)

    assert wired.query("item", 0, 0, "stone")["count"] == 25


def test_no_limit_still_means_move_everything(wired):
    """The default has to stay unchanged: a bus with no limit set is not suddenly capped at zero."""
    wired.place("importbus", 2, 0)
    wired.fill(3, 0, "stone", 200)

    wired.settle(20)

    assert wired.query("item", 0, 0, "stone")["count"] == 200
