"""Every limit the rule panel can express, in both directions.

Written after Elias found in game that setting a limit did nothing. The buses honoured only the two
"each item" modes, and the panel's default mode is TOTAL_ITEMS, so the number a player types was read,
stored, saved, and then silently discarded. Category limits were ignored outright.

One number, read from both sides: an import bus fills the network to it, an export bus drains the network
to it. `ruleglobal` sets the panel-wide limit and its mode, `rulecategory` limits a whole category, and
neither could be set headlessly before -- which is exactly why both shipped broken.
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
    wired.fill(3, 0, "stone", 200)
    wired.do("ruleglobal", 2, 0, "total", 20)

    wired.do("transfer", 2, 0, 10)

    assert wired.query("item", 0, 0, "stone")["count"] == 20, "the network fills to 20 and stops"
    assert wired.query("container", 3, 0, "stone")["count"] == 180, "the rest stays in the chest"


def test_an_export_bus_drains_the_network_to_the_panel_limit(wired):
    """The mirror. Elias set 20 on an export bus and watched it export everything."""
    wired.place("exportbus", 2, 0)
    wired.fill(1, 0, "stone", 200)
    wired.do("rule", 2, 0, "only", "stone")
    wired.do("ruleglobal", 2, 0, "total", 20)

    wired.do("transfer", 2, 0, 10)

    assert wired.query("item", 0, 0, "stone")["count"] == 20, "the network drains to 20 and stops"
    assert wired.query("container", 3, 0, "stone")["count"] == 180, "the rest went to the chest"


def test_the_total_mode_is_a_budget_shared_by_every_item(wired):
    """TOTAL_ITEMS caps the network as a whole, so what is already stored eats into the same budget."""
    wired.place("importbus", 2, 0)
    wired.fill(3, 0, "stone", 100)
    wired.fill(1, 0, "ironbar", 15)
    wired.do("ruleglobal", 2, 0, "total", 20)

    wired.do("transfer", 2, 0, 10)

    assert wired.query("item", 0, 0, "stone")["count"] == 5, "15 iron bars leave room for 5 stone"


def test_the_each_item_mode_gives_every_item_its_own_allowance(wired):
    """The mode that already worked, pinned now that the others do too."""
    wired.place("importbus", 2, 0)
    wired.fill(3, 0, "stone", 100)
    wired.fill(1, 0, "ironbar", 15)
    wired.do("ruleglobal", 2, 0, "each", 20)

    wired.do("transfer", 2, 0, 10)

    assert wired.query("item", 0, 0, "stone")["count"] == 20, "iron bars are counted separately"


def test_the_stacks_mode_measures_in_stacks(wired):
    """An iron pickaxe does not stack, so three stacks of it is three items and needs no assumption about
    any item's stack size."""
    wired.place("importbus", 2, 0)
    wired.fill(3, 0, "ironpickaxe", 10)
    wired.do("ruleglobal", 2, 0, "eachstacks", 3)

    wired.do("transfer", 2, 0, 10)

    assert wired.query("item", 0, 0, "ironpickaxe")["count"] == 3


def test_the_tightest_limit_wins(wired):
    """A per-item limit under a looser panel limit, folded the way vanilla folds its counters."""
    wired.place("importbus", 2, 0)
    wired.fill(3, 0, "stone", 200)
    wired.do("ruleglobal", 2, 0, "each", 100)
    wired.do("rule", 2, 0, "stone", 30)

    wired.do("transfer", 2, 0, 10)

    assert wired.query("item", 0, 0, "stone")["count"] == 30


def test_a_category_limit_applies_to_everything_in_it(wired):
    """A limit on a category, named by an item in it. The buses ignored these entirely before now."""
    wired.place("importbus", 2, 0)
    wired.fill(3, 0, "stone", 100)
    wired.do("rulecategory", 2, 0, "stone", 25)

    wired.do("transfer", 2, 0, 10)

    assert wired.query("item", 0, 0, "stone")["count"] == 25


def test_no_limit_still_means_move_everything(wired):
    """The default has to stay unchanged: a bus with no limit set is not suddenly capped at zero."""
    wired.place("importbus", 2, 0)
    wired.fill(3, 0, "stone", 200)

    wired.do("transfer", 2, 0, 10)

    assert wired.query("item", 0, 0, "stone")["count"] == 200
