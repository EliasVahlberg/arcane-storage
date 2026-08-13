"""The scheduler: what triggers work, how much happens at once, and when it gives up.

Before this, each bus ran its own timer and moved one stack of one item per second. Two faults followed and
neither was a tuning problem: a chest of eight kinds took eight seconds to drain, and two buses with
contradictory rules moved the same items back and forth forever, because a device acting on its own rule cannot
see another device undoing it.

Now the network holds a set of items something has disturbed, and works out the difference between what it holds
and what the rules say it should. Nothing polls except a one-second heartbeat that re-checks device states, which
exists because a vanilla chest appearing or disappearing beside a bus is not something anything can notify us of.
"""


def test_work_starts_when_something_changes_not_when_a_timer_fires(storage):
    """Promptness is the point. A settler dropping something in a chest should not wait out a timer."""
    storage.place("terminal", 0, 0)
    storage.place("unit", 1, 0)
    storage.place("importbus", 2, 0)
    storage.place("storagebox", 3, 0)
    storage.settle(20)

    storage.fill(3, 0, "stone", 30)
    storage.settle(3)

    assert storage.query("container", 3, 0, "stone")["count"] == 0, "within three ticks, not within a second"
    assert storage.query("total", "stone")["count"] == 30


def test_a_large_transfer_is_spread_across_ticks(storage):
    """Rate is policy and lives in one place: a per-network budget per tick.

    The budget is what stops a thousand-item transfer happening in a single tick, which would be both a lurch
    on screen and a spike in one tick's work. It is deliberately not a correctness mechanism -- the rules decide
    what should move, this decides how fast.
    """
    storage.place("terminal", 0, 0)
    storage.place("unit", 1, 0)
    storage.place("unit", 1, 1)
    storage.place("importbus", 2, 0)
    storage.place("storagebox", 3, 0)
    storage.settle(20)

    storage.do("busstatsreset")
    for item in ("stone", "ironbar", "oaklog", "clay", "copperbar", "goldbar", "quartz", "leather"):
        storage.fill(3, 0, item, 40)

    storage.settle(1)
    after_one_tick = storage.query("busstats")
    print(f"\nafter a single tick: {after_one_tick}")
    assert after_one_tick["moves"] <= 8, f"the budget is eight moves a tick, saw {after_one_tick['moves']}"

    storage.settle(20)
    assert storage.query("container", 3, 0, "stone")["count"] == 0, "and it finishes promptly regardless"


def test_the_same_situation_produces_the_same_work_twice(storage):
    """Determinism, without which none of this is testable.

    The same layout, rules and contents twice over must produce the same number of moves. The dirty set is
    drained in the order items were disturbed and the devices are ordered by tile, so nothing depends on which
    device happened to walk the network first.
    """
    counts = []
    for _ in range(2):
        storage.do("reset")
        storage.place("terminal", 0, 0)
        storage.place("unit", 1, 0)
        storage.place("importbus", 2, 0)
        storage.place("storagebox", 3, 0)
        storage.do("rule", 2, 0, "stone", 50)
        storage.settle(20)

        storage.do("busstatsreset")
        storage.fill(3, 0, "stone", 200)
        storage.settle(30)
        counts.append(storage.query("busstats")["moves"])

    print(f"\nmoves on two identical runs: {counts}")
    assert counts[0] == counts[1], f"the same state produced different work: {counts}"


def test_a_loop_closed_outside_the_network_is_stopped(storage):
    """The churn backstop, and the only way to provoke it.

    A cycle built only from our own devices always has one container with both an import and an export bus on
    it, and the static check sees that before anything moves. The loops this is for are closed by somebody else:
    a settler hauling to a priority container, a hopper, another mod's pipe. So the harness plays the settler,
    moving items out of the network and back into the chest every tick while the import bus keeps taking them.

    Failing closed is the right answer for a storage mod. Moving items wrongly forever is worse than stopping,
    and a device that has stopped can say why.
    """
    storage.place("terminal", 0, 0)
    storage.place("unit", 1, 0)
    storage.place("importbus", 2, 0)
    storage.place("storagebox", 3, 0)
    storage.settle(20)

    storage.fill(3, 0, "stone", 40)
    storage.do("haul", 3, 0, "stone", 40, 200)
    storage.settle(200)

    state = storage.query("busstate", 2, 0)
    stats = storage.query("busstats")
    print(f"\nafter 200 ticks of being undone: state={state['state']} stats={stats}")
    assert state["state"] == "churn", f"the bus should have given up, and said so: {state}"
    assert state["reason"], "with a reason a player can read"
    assert stats["stalled"] >= 1, "and the item should be off the schedule"


def test_normal_work_is_not_mistaken_for_churn(storage):
    """The other half of a backstop: it must not fire on work that is getting somewhere.

    A big transfer makes many moves of one item over several seconds, which is exactly the shape the detector
    looks for -- so it measures progress as well as volume, and a transfer that is converging is left alone.
    """
    storage.place("terminal", 0, 0)
    storage.place("unit", 1, 0)
    storage.place("unit", 1, 1)
    storage.place("importbus", 2, 0)
    storage.place("storagebox", 3, 0)
    storage.settle(20)

    for _ in range(6):
        storage.fill(3, 0, "stone", 200)
        storage.settle(20)

    state = storage.query("busstate", 2, 0)
    print(f"\nafter 1200 stone in six batches: {state['state']}, {storage.query('busstats')}")
    assert state["state"] == "active", f"a busy bus is not a broken one: {state}"
    assert storage.query("busstats")["stalled"] == 0


def test_a_rule_change_takes_effect_without_anything_else_happening(storage):
    """Nothing polls, so a rule has to announce itself.

    This is the first thing that broke when the timers came out: a new rule sat unnoticed until some unrelated
    change disturbed the same item, and the system was correct while looking broken.
    """
    storage.place("terminal", 0, 0)
    storage.place("unit", 1, 0)
    storage.place("exportbus", 2, 0)
    storage.place("storagebox", 3, 0)
    storage.fill(1, 0, "stone", 100)
    storage.settle(20)

    assert storage.query("container", 3, 0, "stone")["count"] == 0, "an export bus ships nothing by default"

    storage.do("rule", 2, 0, "only", "stone")
    storage.settle(20)

    print(f"\nafter ticking stone: chest={storage.query('container', 3, 0, 'stone')['count']}")
    assert storage.query("container", 3, 0, "stone")["count"] == 100, "the rule alone should start the work"


def test_a_bus_with_nowhere_to_put_things_says_so_within_a_second(storage):
    """The honest exception to "nothing polls".

    A vanilla chest is not ours and tells us nothing when it is placed or broken, so device states are
    re-derived on a one-second heartbeat -- one per network rather than one per bus. A stale state can no longer
    stop a device working, but it should not be visible for long either.
    """
    storage.place("terminal", 0, 0)
    storage.place("unit", 1, 0)
    storage.place("importbus", 2, 0)
    storage.settle(25)

    assert storage.query("busstate", 2, 0)["state"] == "no_container"

    storage.place("storagebox", 3, 0)
    storage.settle(25)

    assert storage.query("busstate", 2, 0)["state"] == "active", "and it notices when the chest arrives"
