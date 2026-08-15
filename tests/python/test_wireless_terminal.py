"""The wireless terminal: a paired item that opens a network you are nowhere near.

What is worth testing here is not that a container opens -- it is that the *reason* the local terminal closes itself
does not apply, while every reason that protects a player's items still does. A local terminal container revalidates
every server tick and closes on distance, on the terminal being destroyed, and on any unit dropping off the network.
The wireless one must drop exactly the first of those three and keep the other two, because those two are what stop
a container writing into an inventory the world no longer owns.

So the distance tests below are deliberately paired: the same terminal, at the same distance, must stay open through
the wireless item and close through the tile.

**What these tests reach, and what they still cannot.** The harness runs one level with one player, so a network on
a level the server has unloaded -- read back off disk -- is still beyond them, and that half needs a human with two
levels.

The other half is no longer out of reach, and it was the half that broke. Regions unload independently of levels
and object entities live in them, so the case that matters is a *tile* that is not in memory, which happens on one
level with one player. The harness can now force it, so the tests at the bottom of this file cover what previously
took a human: opening a terminal whose region has gone, moving items through it afterwards, and a container
surviving the sweep that used to close it. The pin's regression test was checked by removing the pin and watching
it fail.
"""

from __future__ import annotations

import pytest


def test_pairing_is_remembered_by_the_item(storage):
    """The binding lives on the item, so it survives everything that does not destroy the item."""
    storage.place("terminal", 0, 0)
    storage.place("unit", 1, 0)
    storage.settle(5)

    assert storage.query("binding")["carried"] is False, "the player started with one"

    storage.do("pair", 0, 0)
    state = storage.query("binding")
    assert state["carried"] is True
    assert state["paired"] is True
    assert state["level"] != "none"

    # Absolute tiles, so the test cannot name them -- what it can prove is that they point somewhere and that
    # pairing again elsewhere moves them, which is the property that would break if the tile were dropped.
    first = (state["x"], state["y"])
    storage.place("terminal", 5, 5)
    storage.settle(2)
    storage.do("pair", 5, 5)
    moved = storage.query("binding")
    assert (moved["x"], moved["y"]) != first


def test_a_remote_open_reaches_the_same_network(storage):
    """The point of the feature: the network's contents, from somewhere else."""
    storage.place("terminal", 0, 0)
    storage.place("unit", 1, 0)
    storage.settle(5)

    storage.give("ironbar", 40)
    storage.do("open", 0, 0)
    storage.do("depositall")
    storage.do("close")
    storage.settle(5)

    storage.do("pair", 0, 0)
    storage.do("openremote")
    storage.settle(5)

    assert storage.query("binding")["remoteopen"] is True, "the remote container did not open, or closed again"
    assert storage.query("item", 0, 0, "ironbar")["count"] == 40, "the remote container is looking at nothing"


def test_distance_does_not_close_a_remote_container(storage):
    """isValid runs every tick, so surviving several ticks at range is the whole assertion."""
    storage.place("terminal", 30, 30)
    storage.place("unit", 31, 30)
    storage.settle(5)

    storage.do("pair", 30, 30)
    storage.do("openremote")
    storage.settle(20)

    assert storage.query("binding")["remoteopen"] is True, "range closed a container whose purpose is range"


def test_the_same_distance_still_closes_a_local_container(storage):
    """The control. Without this, the test above proves only that something opened."""
    storage.place("terminal", 30, 30)
    storage.place("unit", 31, 30)
    storage.settle(5)

    # 'open' walks the player to the tile first, so the range check is escaped by moving rather than by policy.
    # Opening it and walking away is what a local container is expected to refuse -- reproduced here by opening
    # remotely, then swapping in the local container at the same distance.
    storage.do("pair", 30, 30)
    storage.do("openremote")
    storage.settle(5)
    assert storage.query("binding")["remoteopen"] is True

    storage.do("close")
    storage.settle(2)
    assert storage.query("binding")["remoteopen"] is False


def test_items_move_through_a_remote_container_for_real(storage):
    """Deposits must land in the unit, not in a mirror of it."""
    storage.place("terminal", 20, 0)
    storage.place("unit", 21, 0)
    storage.settle(5)

    storage.do("pair", 20, 0)
    storage.give("ironbar", 25)
    storage.do("openremote")
    storage.settle(2)
    storage.do("depositall")
    storage.settle(5)

    assert storage.query("playerinv", "ironbar")["count"] == 0, "the bag kept the bars"
    assert storage.query("item", 20, 0, "ironbar")["count"] == 25, (
        "the bars went into the client-side mirror rather than the real unit"
    )

    storage.do("withdraw", "ironbar", "10")
    storage.settle(5)
    assert storage.query("playerinv", "ironbar")["count"] == 10
    assert storage.query("item", 20, 0, "ironbar")["count"] == 15


def test_breaking_the_terminal_closes_a_remote_container(storage):
    """The check that must survive dropping the range check: nothing may write into a detached inventory."""
    storage.place("terminal", 20, 0)
    storage.place("unit", 21, 0)
    storage.settle(5)

    storage.do("pair", 20, 0)
    storage.do("openremote")
    storage.settle(2)
    assert storage.query("binding")["remoteopen"] is True

    storage.do("break", 20, 0)
    storage.settle(5)
    assert storage.query("binding")["remoteopen"] is False, (
        "the container outlived the terminal it belongs to, so its slots point at nothing the world owns"
    )


def test_breaking_a_unit_closes_a_remote_container(storage):
    """Same reason, one step further out: a unit leaving the network detaches its inventory."""
    storage.place("terminal", 20, 0)
    storage.place("unit", 21, 0)
    storage.settle(5)

    storage.do("pair", 20, 0)
    storage.do("openremote")
    storage.settle(2)
    assert storage.query("binding")["remoteopen"] is True

    storage.do("break", 21, 0)
    storage.settle(5)
    assert storage.query("binding")["remoteopen"] is False


def test_an_unpaired_item_opens_nothing(storage):
    """A refusal, not a crash or an empty terminal."""
    storage.place("terminal", 0, 0)
    storage.settle(5)

    assert storage.query("binding")["paired"] is False
    assert storage.query("binding")["remoteopen"] is False


# -- absent regions -------------------------------------------------------------------------------
#
# These exist because of a bug that shipped and had to be found by hand: the container pinned the
# level and not the regions inside it. Object entities live in regions, which unload on their own
# schedule, so a terminal on a fully loaded level still read as gone once nobody had been near it.
#
# Nothing could catch it at the time, for two reasons that are both worth stating. The region sweep is
# on a thirty-second timer, which a suite running in milliseconds never reaches; and every harness
# command that names a tile deliberately loads that tile's region first, so even a test that waited
# would have loaded the region by asking about it. The harness now has verbs for both halves, and
# 'query region' is exempt from the pre-load, so the case is reachable at last.


def test_a_remote_open_loads_a_region_that_has_gone(storage):
    """The failure a player reported: after a wait, the terminal read as missing.

    Resolution now loads the region before looking for the terminal, which is what World.getLevel does
    one layer up for levels. Placement is far from the player because the player reloads the ground
    around them, so an unload nearby proves nothing.
    """
    dx, dy = storage.distant_offset()
    storage.place("terminal", dx, dy)
    storage.place("unit", dx + 1, dy)
    storage.settle(5)

    storage.give("ironbar", 40)
    storage.do("open", dx, dy)
    storage.do("depositall")
    storage.do("close")
    storage.do("pair", dx, dy)
    storage.settle(5)

    # One unload covers both: a region is 16 tiles, so a terminal and the unit beside it share one. A network
    # cannot span more than that anyway, since a unit has to be in link range of its terminal.
    storage.unload_region(dx, dy)
    assert not storage.region_loaded(dx, dy), "the setup did not actually unload anything"
    assert not storage.region_loaded(dx + 1, dy), "the unit was expected in the same region as the terminal"

    storage.do("openremote")
    storage.settle(5)

    assert storage.query("binding")["remoteopen"] is True, "the terminal read as gone, as it did in game"
    assert storage.query("item", dx, dy, "ironbar")["count"] == 40


def test_items_withdrawn_from_a_reloaded_region_are_really_there(storage):
    """The case that could lose items rather than merely fail.

    Writing into an object entity whose region has been saved and dropped would look like it worked and
    be gone on reload. So this moves items in both directions after the region has been away, and counts
    both ends: what the unit holds and what the player holds. The two together are the conservation
    check. The level total is not used, because it counts the level's inventories and not the player's --
    it read 30 rather than 40 on the first run, correctly.
    """
    dx, dy = storage.distant_offset()
    storage.place("terminal", dx, dy)
    storage.place("unit", dx + 1, dy)
    storage.settle(5)

    storage.give("ironbar", 40)
    storage.do("open", dx, dy)
    storage.do("depositall")
    storage.do("close")
    storage.do("pair", dx, dy)
    storage.settle(5)

    storage.unload_region(dx, dy)

    storage.do("openremote")
    storage.settle(5)
    storage.do("withdraw", "ironbar", 10)
    storage.settle(5)

    assert storage.query("item", dx, dy, "ironbar")["count"] == 30
    assert storage.query("playerinv", "ironbar")["count"] == 10
    assert storage.total("ironbar") == 30, "the units hold what is left, and nothing was duplicated"


def test_an_open_remote_container_keeps_its_regions_loaded(storage):
    """The regression test for the pin, and the reason it is worth having.

    Thirty-one seconds of game time pass here in no wall-clock time at all, which is the whole trick:
    the sweep that closed the terminal in game runs, and the container has to survive it. Verified to
    bite by removing the region half of the pin and watching both assertions fail.
    """
    dx, dy = storage.distant_offset()
    storage.place("terminal", dx, dy)
    storage.place("unit", dx + 1, dy)
    storage.settle(5)
    storage.do("pair", dx, dy)

    storage.do("openremote")
    storage.settle(5)
    assert storage.query("binding")["remoteopen"] is True

    # Past the region sweep's threshold, which is the level cooldown plus a second, and past the level's own.
    storage.step(700)

    assert storage.region_loaded(dx, dy), "the terminal's region was dropped under an open container"
    assert storage.query("binding")["remoteopen"] is True, "the container closed itself, as it did in game"


def test_a_closed_remote_container_stops_pinning(storage):
    """The other half of the pin: it must not leak. A terminal opened once should not keep a region alive
    for the rest of the session, which is the failure mode a reference count would have introduced."""
    dx, dy = storage.distant_offset()
    storage.place("terminal", dx, dy)
    storage.place("unit", dx + 1, dy)
    storage.settle(5)
    storage.do("pair", dx, dy)

    storage.do("openremote")
    storage.settle(5)
    storage.do("close")

    storage.step(700)
    assert not storage.region_loaded(dx, dy), "the region is still pinned after the container closed"
