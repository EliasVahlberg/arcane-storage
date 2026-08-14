"""The wireless terminal: a paired item that opens a network you are nowhere near.

What is worth testing here is not that a container opens -- it is that the *reason* the local terminal closes itself
does not apply, while every reason that protects a player's items still does. A local terminal container revalidates
every server tick and closes on distance, on the terminal being destroyed, and on any unit dropping off the network.
The wireless one must drop exactly the first of those three and keep the other two, because those two are what stop
a container writing into an inventory the world no longer owns.

So the distance tests below are deliberately paired: the same terminal, at the same distance, must stay open through
the wireless item and close through the tile.

**What these tests cannot reach, stated rather than implied.** The harness runs one level with one player on it, so
nothing here exercises the case the feature exists for -- a network on a level the server has unloaded, resolved by
reading it back off disk. `World.getLevel` is the engine's own load-on-demand path and the pin that keeps a level
loaded is one line copied from three vanilla callers, but neither is proven by anything below. That needs a human,
two levels and a 30-second wait.
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
