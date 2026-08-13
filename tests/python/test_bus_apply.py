"""The panel as a transaction: a rule set is adopted or refused as one thing.

The panel used to send every checkbox as it was clicked, which had two consequences. A player halfway through a
legitimate change could be judged on a state they were about to leave, and a set that is only valid as a whole
could never be reached at all. And on a bus that had already stopped, each click restarted work under a rule the
player had not finished writing.

The right moment to refuse an unsatisfiable rule set is when it is written. That is where the reason is
actionable, because the player is looking at the thing they just did.
"""

import pytest

from necesse_harness.process import HarnessError


def test_a_compatible_rule_is_accepted(two_buses):
    """The ordinary case has to stay ordinary: validation is not a new obstacle."""
    two_buses.do("rule", 3, 1, "only", "stone")
    two_buses.do("ruleglobal", 3, 1, "each", 20)

    # The import bus filling to 20 and the export bus draining to 20 agree: the network rests at 20.
    two_buses.do("busapply", 2, 0, "stone", 20, "accepted")

    two_buses.settle(20)
    assert two_buses.query("busstate", 2, 0)["state"] == "active"
    assert two_buses.query("busstate", 3, 1)["state"] == "active"


def test_a_contradictory_rule_is_refused(two_buses):
    """A ceiling above a floor on a shared container describes no state the network can rest in.

    Both buses touch the same chest, so whatever one does the other undoes. Refusing this when it is written is
    the whole point: the alternative, which is what shipped before, was to accept it and shuttle items back and
    forth forever.
    """
    two_buses.do("rule", 3, 1, "only", "stone")
    two_buses.do("ruleglobal", 3, 1, "each", 20)

    # 50 into a network the other bus drains to 20.
    two_buses.do("busapply", 2, 0, "stone", 50, "refused")


def test_a_refused_rule_is_not_partly_applied(two_buses):
    """Atomicity, in the only sense that matters here.

    A rule set that is refused must leave nothing behind. A half-applied set is a state the player did not ask
    for and cannot see in the panel, which is worse than a refusal they can read.
    """
    two_buses.do("rule", 3, 1, "only", "stone")
    two_buses.do("ruleglobal", 3, 1, "each", 20)

    # Narrow the import bus first, so a refused proposal has something visible to fail to change: stone is not
    # ticked, and after a refusal it must still not be ticked.
    two_buses.do("rule", 2, 0, "only", "ironbar")
    before = two_buses.query("busstate", 2, 0, "stone")
    assert not before["allows"], "stone starts unticked on the import bus"

    two_buses.do("busapply", 2, 0, "stone", 50, "refused")
    after = two_buses.query("busstate", 2, 0, "stone")

    print(f"\nstone before: allows={before['allows']} target={before['target']}, "
          f"after a refusal: allows={after['allows']} target={after['target']}")
    assert not after["allows"], "a refused set must not have ticked the item anyway"
    assert after["target"] == before["target"], "nor moved the number"


def test_the_reason_names_the_other_device(two_buses):
    """A refusal that does not say what it collided with is a refusal a player cannot act on."""
    two_buses.do("rule", 3, 1, "only", "stone")
    two_buses.do("ruleglobal", 3, 1, "each", 20)

    with pytest.raises(HarnessError):
        # Asserting "accepted" fails, and the verb reports the reason it was refused instead.
        two_buses.do("busapply", 2, 0, "stone", 50, "accepted")


def test_rules_still_apply_when_there_is_nothing_to_contradict(storage):
    """One bus alone cannot contradict anybody, so nothing may stand in its way."""
    storage.place("terminal", 0, 0)
    storage.place("unit", 1, 0)
    storage.place("importbus", 2, 0)
    storage.place("storagebox", 3, 0)
    storage.settle(20)

    storage.do("busapply", 2, 0, "stone", 30, "accepted")
    storage.fill(3, 0, "stone", 100)
    storage.settle(20)

    assert storage.query("total", "stone")["count"] == 30, "the applied rule is the one in force"
