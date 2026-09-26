"""An export bus that keeps its chest stocked, rather than draining the network to a number.

Requested on the Workshop (Talion The Tark, Aug and Sep 2026): "if I select stone and sand and indicate
100, the exporter will always keep the adjacent box stocked with 100 stone and 100 sand."

The number and the panel are unchanged. What changes is which inventory the number is counted in: a
plain export bus drains the *network* down to N, a stocking one fills its *chest* up to N. So every test
here is a claim about the chest's count, and most of them pair it with a claim that the network kept the
rest -- a stocking bus that simply drained everything would pass a chest-only assertion.

    y=0:   T  U  E  C

T terminal, U unit, E export bus, C chest (a vanilla storagebox).
"""

from __future__ import annotations

import pytest

from conftest import Terminal


@pytest.fixture
def stocked(storage):
    storage.place("terminal", 0, 0)
    storage.place("unit", 1, 0)
    storage.place("exportbus", 2, 0)
    storage.place("storagebox", 3, 0)
    return Terminal(storage, 0, 0)


def chest(t: Terminal, item: str) -> int:
    return t.harness.query("container", 3, 0, item)["count"]


def test_the_chest_is_filled_to_the_number_and_the_network_keeps_the_rest(stocked):
    stocked.harness.fill(1, 0, "stone", 300)
    stocked.harness.do("rule", 2, 0, "stone", 100)
    stocked.harness.do("busstock", 2, 0, 1)

    stocked.harness.settle(40)

    assert chest(stocked, "stone") == 100
    assert stocked.count("stone") == 200, "the network keeps whatever the chest does not need"


def test_without_stocking_the_same_number_means_what_it_always_did(stocked):
    """The control: the flag is the only difference, and the plain reading is untouched."""
    stocked.harness.fill(1, 0, "stone", 300)
    stocked.harness.do("rule", 2, 0, "stone", 100)

    stocked.harness.settle(40)

    assert stocked.count("stone") == 100, "a plain export bus drains the network down to its number"
    assert chest(stocked, "stone") == 200


def test_taking_from_the_chest_is_topped_up_again(stocked):
    """The request's whole point: "always" keep it stocked. Nothing polls, so this is the watch doing it."""
    stocked.harness.fill(1, 0, "stone", 300)
    stocked.harness.do("rule", 2, 0, "stone", 100)
    stocked.harness.do("busstock", 2, 0, 1)
    stocked.harness.settle(40)

    stocked.harness.do("take", 3, 0, "stone", 60)
    assert chest(stocked, "stone") == 40, "the setup took 60 out of the chest"

    stocked.harness.settle(40)

    assert chest(stocked, "stone") == 100
    assert stocked.count("stone") == 140


def test_two_items_are_each_stocked_to_their_own_number(stocked):
    stocked.harness.fill(1, 0, "stone", 300)
    stocked.harness.fill(1, 0, "ironbar", 300)
    stocked.harness.do("rule", 2, 0, "stone", 100)
    stocked.harness.do("rule", 2, 0, "ironbar", 50)
    stocked.harness.do("busstock", 2, 0, 1)

    stocked.harness.settle(60)

    assert chest(stocked, "stone") == 100
    assert chest(stocked, "ironbar") == 50


def test_a_chest_already_over_its_number_is_left_alone(stocked):
    """Never pulls back. Taking the excess into the network is an import bus's job, not this one's."""
    stocked.harness.fill(3, 0, "stone", 150)
    stocked.harness.fill(1, 0, "stone", 100)
    stocked.harness.do("rule", 2, 0, "stone", 100)
    stocked.harness.do("busstock", 2, 0, 1)

    stocked.harness.settle(40)

    assert chest(stocked, "stone") == 150
    assert stocked.count("stone") == 100


def test_a_network_that_runs_short_gives_what_it_has(stocked):
    stocked.harness.fill(1, 0, "stone", 30)
    stocked.harness.do("rule", 2, 0, "stone", 100)
    stocked.harness.do("busstock", 2, 0, 1)

    stocked.harness.settle(40)

    assert chest(stocked, "stone") == 30
    assert stocked.count("stone") == 0


def test_items_are_conserved_while_stocking(stocked):
    """Network plus chest, before and after: `total` here counts storage units only, so the chest is added."""
    stocked.harness.fill(1, 0, "stone", 300)
    before = stocked.count("stone") + chest(stocked, "stone")
    stocked.harness.do("rule", 2, 0, "stone", 100)
    stocked.harness.do("busstock", 2, 0, 1)

    stocked.harness.settle(40)
    stocked.harness.do("take", 3, 0, "stone", 60)
    stocked.harness.settle(40)

    after = stocked.count("stone") + chest(stocked, "stone")
    assert after == before - 60, "only the 60 taken away may be missing"


def test_stocking_is_refused_against_an_import_bus_on_the_same_chest(two_buses):
    """The loop this mode would otherwise create: fill the chest, the importer empties it, fill it again.

    Any positive stock of an item both buses allow has no resting state, whatever the importer's number,
    so it is refused at Apply rather than discovered later as churn. `two_buses` shares one chest between
    an import bus at (2,0) and an export bus at (3,1).
    """
    # Fill the network to 100 and keep 100 in it: C <= F, so the plain rules rest and are accepted.
    two_buses.do("rule", 2, 0, "only", "stone")
    two_buses.do("rule", 2, 0, "stone", 100)
    two_buses.do("rule", 3, 1, "only", "stone")
    two_buses.do("busapply", 3, 1, "stone", 100, "accepted")

    # The same numbers, read as "keep 100 in the chest", no longer rest. Stocking alone is the difference.
    two_buses.do("busstock", 3, 1, 1, "refused")


def test_stocking_survives_a_save_and_load(stocked):
    stocked.harness.do("rule", 2, 0, "stone", 100)
    stocked.harness.do("busstock", 2, 0, 1)

    stocked.harness.do("bussave", 2, 0)

    stocked.harness.fill(1, 0, "stone", 300)
    stocked.harness.settle(40)
    assert chest(stocked, "stone") == 100, "the reloaded bus should still be stocking"


def test_the_panel_is_told_the_bus_is_stocking(stocked):
    """The open packet carries the flag, or the checkbox would open unticked and Apply would switch it off.

    That failure is silent and destructive in the same way the filter hand-off bug was: the player opens the
    panel to change one number, presses Apply, and a setting they never touched is gone.
    """
    stocked.harness.do("busstock", 2, 0, 1)

    packet = stocked.harness.query("busopenpacket", 2, 0)

    assert packet["serverstocking"] is True
    assert packet["clientstocking"] is True, "the client-side panel decoded the flag as off"
