"""What a network costs, and whether that cost belongs to the network or to the devices watching it.

The distinction is the whole point of the index. Before it, every bus counted privately: a network with two
buses paid for everything twice, and one with ten would pay ten times, for identical answers. These tests
assert the shape of the cost rather than an absolute number, because the absolute number is a scheduling
question and belongs to step 4 of the resolver plan.
"""

import pytest


def test_two_buses_on_one_network_share_one_count(two_buses):
    """The property that makes a network worth building out: cost scales with the network, not with watchers.

    Both buses want to know what the network holds. Rebuilding once and sharing the answer is not an
    optimisation of the counting -- the counting was never the fault. Asking the same question twice was.
    """
    two_buses.fill(3, 0, "stone", 40)
    two_buses.settle(60)

    two_buses.do("busstatsreset")
    two_buses.settle(60)

    stats = two_buses.query("busstats")
    print(f"\ntwo buses over 60 ticks: {stats}")
    assert stats["indexes"] == 1, "two buses on one network are one network"
    assert stats["rebuilds"] == 0, f"a settled network is not recounted at all, saw {stats['rebuilds']}"


def test_an_idle_network_is_not_walked_at_all(two_buses):
    """Walks are the expensive half: a flood fill over units and conduits, per bus, per second.

    Zero now rather than one per network per second. A build is only invalidated by the layout changing, and an
    idle network's layout is not changing.

    The network is established before the counters are reset, which the test used to get away with not doing.
    Discovering a freshly placed network is itself a walk -- two of them here, one per bus -- and under the old
    execution model those happened during the fixture's own setup, because every placement command cost a tick
    of real time. Nothing ticks between commands now, so the discovery would land inside the measured window
    and the test would be asserting that a *new* network is not walked, which is not the claim.
    """
    two_buses.settle(20)
    two_buses.do("busstatsreset")
    two_buses.settle(60)

    stats = two_buses.query("busstats")
    print(f"\nidle for 60 ticks with two buses: {stats}")
    assert stats["walks"] == 0, f"an idle network is not rediscovered at all, saw {stats['walks']}"


def test_a_mixed_chest_moves_every_kind_at_once(storage):
    """The quadratic term is gone, and so is the one-kind-per-second limit.

    The transfer loop used to ask, for every slot it looked at, how many of that item each side held -- and the
    network side of that question scanned every unit's every slot. It also moved one kind per wake-up, so eight
    kinds took eight seconds. Now every disturbed kind is considered under one per-network budget.

    What is left is a burst proportional to the number of kinds that changed times the size of the container:
    the index knows how many of a thing the network holds but not which container holds them, so the source side
    is scanned to find it. That is work in response to an event rather than work on a timer, and a location
    index -- a record per stack, which is what vanilla's settlement storage keeps -- would turn each of those
    scans into a lookup. Recorded rather than tuned, because nothing has yet shown it matters.
    """
    storage.place("terminal", 0, 0)
    storage.place("unit", 1, 0)
    storage.place("importbus", 2, 0)
    storage.place("storagebox", 3, 0)
    storage.settle(20)

    # Counters zeroed before the items appear, or this measures the aftermath: the chest empties within a tick
    # or two of being filled, so anything counted afterwards is the scheduler finding nothing left to do.
    storage.do("busstatsreset")
    kinds = ("stone", "ironbar", "oaklog", "clay", "copperbar", "goldbar", "quartz", "leather")
    for item in kinds:
        storage.fill(3, 0, item, 10)

    storage.settle(20)

    stats = storage.query("busstats")
    print(f"\none second with eight kinds in the chest: {stats}")
    assert stats["moves"] == len(kinds), f"every kind moved, not one per second, saw {stats['moves']}"
    # At most the one discovery that finds the network in the first place, which lands inside this window when
    # the preceding test cleared the indexes. What must not appear is a walk per second or a walk per kind.
    assert stats["walks"] <= 1, f"at most the initial discovery, saw {stats['walks']}"
    assert stats["rebuilds"] <= 1, f"at most the initial count, saw {stats['rebuilds']}"
    assert storage.query("container", 3, 0, "stone")["count"] == 0, "the chest is empty"


def test_the_index_agrees_with_what_the_network_holds(two_buses):
    """A cache of state we do not own is only useful while it is right.

    Asserted through the terminal's own aggregate, which reads the units directly rather than the index, so
    the two can disagree and be caught. Step 3 makes this hold under foreign changes as well.
    """
    two_buses.do("rule", 2, 0, "stone", 25)
    two_buses.fill(3, 0, "stone", 40)
    two_buses.settle(80)

    truth = two_buses.query("total", "stone")["count"]
    drift = two_buses.query("indexdrift")
    print(f"\nunits hold {truth} stone, index drift {drift}")
    assert truth == 25, f"the rule says 25, the units hold {truth}"
    assert drift["drift"] == 0, drift
