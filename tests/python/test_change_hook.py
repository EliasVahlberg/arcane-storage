"""The change hook, and what it buys.

Everything here rests on a bytecode patch of ``Inventory.updateSlot(int)``, which is the funnel every mutation
inside that class routes through. The listener list next to it is not usable: it notifies only its first
listener, so attaching to a container the mod does not own would either be ignored or would steal the game's
own notification.

The reason to test it rather than trust it: a method patch binds to an exact signature, so a game update can
stop it applying, and the failure is silent -- the index would go on believing in items somebody carried away.
"""

import pytest


def test_the_hook_is_actually_woven_in(storage):
    """An assumption that costs nothing to check and everything to get wrong."""
    hook = storage.query("hook")
    print(f"\nhook: {hook}")
    assert hook["applied"], (
        "the Inventory.updateSlot patch did not apply; the index will fall back to recounting on a timer"
    )
    assert hook["notifications"] > 0, "the hook has never fired, which no working game should manage"


def test_a_foreign_change_reaches_the_index_without_a_walk(storage):
    """The point of the hook: somebody else's change is picked up exactly, not waited out.

    Filling a unit directly is what a settler, a hopper or another mod does. Before the hook the index would
    have carried on with the old number until its next rebuild.
    """
    storage.place("terminal", 0, 0)
    storage.place("unit", 1, 0)
    storage.place("importbus", 2, 0)
    storage.place("storagebox", 3, 0)
    storage.settle(40)

    storage.do("busstatsreset")
    storage.fill(1, 0, "ironbar", 30)
    storage.settle(2)

    drift = storage.query("indexdrift")
    stats = storage.query("busstats")
    print(f"\nafter a foreign fill: drift={drift} stats={stats}")
    assert drift["drift"] == 0, "the index should already know about a change made behind its back"
    assert stats["rebuilds"] == 0, "and it should have learnt it without recounting the network"


def test_a_withdrawal_reaches_the_index_too(storage):
    """Both directions, because a slot emptied is the case the shadow exists for.

    A player taking items out through the terminal is precisely the gap the previous step left open: the
    terminal moves items straight into a backpack and tells the index nothing. ``updateSlot`` reports which
    slot changed and not what it held, so the correction is only possible because the index remembers what it
    last saw there.
    """
    storage.place("terminal", 0, 0)
    storage.place("unit", 1, 0)
    storage.settle(20)
    storage.fill(1, 0, "stone", 50)
    storage.settle(20)

    storage.do("open", 0, 0)
    storage.do("withdraw", "stone", "20")
    storage.settle(2)

    drift = storage.query("indexdrift")
    print(f"\nafter withdrawing 20 of 50: total={storage.query('total', 'stone')['count']} drift={drift}")
    assert drift["drift"] == 0, drift
    assert storage.query("total", "stone")["count"] == 30


def test_induced_drift_is_caught_and_repaired(storage):
    """A safety net that has never been tripped is a claim, not a net.

    The index is a cache of state the mod does not own, and vanilla has shipped this bug class itself -- its
    version history records fixing crafting lists that did not update when a nearby inventory changed. So the
    counts are poisoned deliberately and the periodic check is watched noticing.
    """
    storage.place("terminal", 0, 0)
    storage.place("unit", 1, 0)
    storage.place("importbus", 2, 0)
    storage.place("storagebox", 3, 0)
    storage.fill(3, 0, "stone", 20)
    storage.settle(40)

    storage.do("busstatsreset")
    storage.do("indexpoison", "stone", 500)
    assert storage.query("indexdrift")["drift"] == 500, "the poison should be visible as drift"

    # The check runs every ten seconds against a network in use, so give it eleven -- and well short of the
    # thirty-second freshness window, or a rebuild would clear the drift and prove nothing.
    storage.settle(230)

    after = storage.query("indexdrift")
    hook = storage.query("hook")
    print(f"\nafter the periodic check: drift={after} resyncs={hook['resyncs']}")
    assert after["drift"] == 0, "the check should have rebuilt the counts"
    assert hook["resyncs"] >= 1, "and should have said so"


def test_opening_the_terminal_asks_every_network_to_check_itself(storage):
    """The moment a wrong number would be noticed is the moment to make sure it is not wrong.

    The bus is here because devices are what hold an index -- a network with nothing watching it has nobody to
    be wrong for, so there is nothing to check. That is also why the check is driven by use rather than by a
    timer of its own.
    """
    storage.place("terminal", 0, 0)
    storage.place("unit", 1, 0)
    storage.place("importbus", 2, 0)
    storage.place("storagebox", 3, 0)
    storage.fill(1, 0, "stone", 10)
    storage.settle(40)

    storage.do("busstatsreset")
    storage.do("indexpoison", "stone", 99)
    storage.do("open", 0, 0)
    storage.settle(40)

    after = storage.query("indexdrift")
    print(f"\nafter opening the terminal: {after}")
    assert after["drift"] == 0, "opening the terminal should have forced the check"


def test_the_games_own_forms_still_get_their_notification(storage):
    """The reason this is a patch and not a listener.

    ``Inventory`` notifies only the first listener in its list, so a mod that attached one to a chest it does
    not own would take the notification the game's own container form depends on. The patch adds no listener,
    and this asserts the list is left as vanilla leaves it.
    """
    storage.place("storagebox", 3, 0)
    storage.settle(10)

    check = storage.query("listenercheck", 3, 0)
    print(f"\nlistener check: {check}")
    assert check["first"] > 0, "a vanilla form must still be told when a slot changes"
    assert check["second"] == 0, (
        "and the engine still notifies only the first listener, which is why this is a patch"
    )


def test_an_idle_network_stops_walking(storage):
    """What the hook makes possible: nothing to watch for means nothing to do.

    A build used to expire after a second because a bus recounted that often and a shared copy must not be
    staler than the code it replaced. With changes reported as they happen, a build is only invalidated by the
    layout changing, so an idle network is walked once and then left alone.
    """
    storage.place("terminal", 0, 0)
    storage.place("unit", 1, 0)
    storage.place("importbus", 2, 0)
    storage.place("storagebox", 3, 0)
    storage.settle(40)

    storage.do("busstatsreset")
    storage.settle(100)

    stats = storage.query("busstats")
    print(f"\nidle for 100 ticks: {stats}")
    assert stats["walks"] == 0, f"a settled network should not be rediscovered, saw {stats['walks']} walks"
    assert stats["rebuilds"] == 0, f"nor recounted, saw {stats['rebuilds']}"
    # The periodic drift check is the one thing that still costs anything while idle, and it is bounded: one
    # pass over the network's slots every ten seconds. Counted separately so this claim stays honest.
    assert stats["driftslots"] <= 80, f"the safety net should cost one pass, saw {stats['driftslots']}"
