"""What a network costs, and whether that cost belongs to the network or to the devices watching it.

The distinction is the whole point of the index. Before it, every bus counted privately: a network with two
buses paid for everything twice, and one with ten would pay ten times, for identical answers. These tests
assert the shape of the cost rather than an absolute number, because the absolute number is a scheduling
question and belongs to step 4 of the resolver plan.
"""


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
    # Three seconds pass, so at most one rebuild per second is the sharing working. Six -- one per bus per
    # second -- would mean each was still counting for itself.
    assert stats["rebuilds"] <= 3, f"one rebuild per second at most, saw {stats['rebuilds']}"


def test_an_idle_network_is_walked_once_per_second_not_once_per_bus(two_buses):
    """Walks are the expensive half: a flood fill over units and conduits, per bus, per second.

    Still on a timer until step 4 removes the timer altogether. What this fixes is the multiplier.
    """
    two_buses.do("busstatsreset")
    two_buses.settle(60)

    stats = two_buses.query("busstats")
    print(f"\nidle for 60 ticks with two buses: {stats}")
    assert stats["walks"] <= 4, f"about one walk per second, not per bus per second, saw {stats['walks']}"


def test_a_mixed_chest_costs_one_pass_not_one_per_item(storage):
    """The quadratic term, which was the real cost and is now gone.

    The transfer loop used to ask, for every slot it looked at, how many of that item each side held -- and
    the network side of that question scanned every unit's every slot. A chest of eight kinds therefore cost
    eight times a full network scan, per second, per bus. The arithmetic is unchanged; only who counts.
    """
    storage.place("terminal", 0, 0)
    storage.place("unit", 1, 0)
    storage.place("importbus", 2, 0)
    storage.place("storagebox", 3, 0)
    for item in ("stone", "ironbar", "oaklog", "clay", "copperbar", "goldbar", "quartz", "leather"):
        storage.fill(3, 0, item, 10)

    storage.do("busstatsreset")
    storage.settle(20)

    stats = storage.query("busstats")
    print(f"\none second with eight kinds in the chest: {stats}")
    # One pass over the chest to count it, one over the chest to find something to move, and the unit scan
    # from a rebuild. Well under a hundred; the old code's product of slots and kinds was several hundred.
    assert stats["slots"] < 200, f"a chest of eight kinds should not cost a scan per kind, saw {stats['slots']}"


def test_the_index_agrees_with_what_the_network_holds(two_buses):
    """A cache of state we do not own is only useful while it is right.

    Asserted through the terminal's own aggregate, which reads the units directly rather than the index, so
    the two can disagree and be caught. Step 3 makes this hold under foreign changes as well.
    """
    two_buses.fill(3, 0, "stone", 40)
    two_buses.do("rule", 2, 0, "stone", 25)
    two_buses.settle(80)

    truth = two_buses.query("total", "stone")["count"]
    drift = two_buses.query("indexdrift")
    print(f"\nunits hold {truth} stone, index drift {drift}")
    assert truth == 25, f"the rule says 25, the units hold {truth}"
    assert drift["drift"] == 0, drift
