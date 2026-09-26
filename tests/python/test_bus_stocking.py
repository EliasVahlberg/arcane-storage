"""Buses whose numbers are counted in their chest rather than in the network.

Requested on the Workshop (Talion The Tark, Aug and Sep 2026): "if I select stone and sand and indicate
100, the exporter will always keep the adjacent box stocked with 100 stone and 100 sand." Generalised to
both buses, because a bus moves one way only, so each place it can count in has exactly one meaning:

    import, network    fill the network up to N          (the default)
    import, container  leave N in the chest, take the rest
    export, network    leave N in the network, send the rest  (the default)
    export, container  stock the chest up to N

The number and the panel are unchanged. What changes is which inventory the number is counted in. So every
test here is a claim about the chest's count, and most of them pair it with a claim about the network -- a
bus that simply moved everything would pass a chest-only assertion.

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
    stocked.harness.do("buscountin", 2, 0, "container")

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
    stocked.harness.do("buscountin", 2, 0, "container")
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
    stocked.harness.do("buscountin", 2, 0, "container")

    stocked.harness.settle(60)

    assert chest(stocked, "stone") == 100
    assert chest(stocked, "ironbar") == 50


def test_a_chest_already_over_its_number_is_left_alone(stocked):
    """Never pulls back. Taking the excess into the network is an import bus's job, not this one's."""
    stocked.harness.fill(3, 0, "stone", 150)
    stocked.harness.fill(1, 0, "stone", 100)
    stocked.harness.do("rule", 2, 0, "stone", 100)
    stocked.harness.do("buscountin", 2, 0, "container")

    stocked.harness.settle(40)

    assert chest(stocked, "stone") == 150
    assert stocked.count("stone") == 100


def test_a_network_that_runs_short_gives_what_it_has(stocked):
    stocked.harness.fill(1, 0, "stone", 30)
    stocked.harness.do("rule", 2, 0, "stone", 100)
    stocked.harness.do("buscountin", 2, 0, "container")

    stocked.harness.settle(40)

    assert chest(stocked, "stone") == 30
    assert stocked.count("stone") == 0


def test_items_are_conserved_while_stocking(stocked):
    """Network plus chest, before and after: `total` here counts storage units only, so the chest is added."""
    stocked.harness.fill(1, 0, "stone", 300)
    before = stocked.count("stone") + chest(stocked, "stone")
    stocked.harness.do("rule", 2, 0, "stone", 100)
    stocked.harness.do("buscountin", 2, 0, "container")

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
    two_buses.do("buscountin", 3, 1, "container", "refused")


def test_stocking_survives_a_save_and_load(stocked):
    stocked.harness.do("rule", 2, 0, "stone", 100)
    stocked.harness.do("buscountin", 2, 0, "container")

    stocked.harness.do("bussave", 2, 0)

    stocked.harness.fill(1, 0, "stone", 300)
    stocked.harness.settle(40)
    assert chest(stocked, "stone") == 100, "the reloaded bus should still be stocking"


def test_the_panel_is_told_the_bus_is_stocking(stocked):
    """The open packet carries the flag, or the checkbox would open unticked and Apply would switch it off.

    That failure is silent and destructive in the same way the filter hand-off bug was: the player opens the
    panel to change one number, presses Apply, and a setting they never touched is gone.
    """
    stocked.harness.do("buscountin", 2, 0, "container")

    packet = stocked.harness.query("busopenpacket", 2, 0)

    assert packet["serverincontainer"] is True
    assert packet["clientincontainer"] is True, "the client-side panel decoded the flag as off"


# -- import bus, counting in its container: "leave N in the chest, take the rest" -----------------------------


@pytest.fixture
def leaving(storage):
    """The same row with an import bus: T U I C."""
    storage.place("terminal", 0, 0)
    storage.place("unit", 1, 0)
    storage.place("importbus", 2, 0)
    storage.place("storagebox", 3, 0)
    return Terminal(storage, 0, 0)


def test_an_import_bus_leaves_its_number_in_the_chest_and_takes_the_rest(leaving):
    leaving.harness.do("rule", 2, 0, "only", "torch")
    leaving.harness.do("rule", 2, 0, "torch", 10)
    leaving.harness.do("buscountin", 2, 0, "container")
    leaving.harness.fill(3, 0, "torch", 50)

    leaving.harness.settle(40)

    assert chest(leaving, "torch") == 10
    assert leaving.count("torch") == 40, "everything above the number goes into the network"


def test_the_same_import_number_counted_in_the_network_is_a_ceiling(leaving):
    """The control: the default reading fills the network to 10 and leaves the rest in the chest."""
    leaving.harness.do("rule", 2, 0, "only", "torch")
    leaving.harness.do("rule", 2, 0, "torch", 10)
    leaving.harness.fill(3, 0, "torch", 50)

    leaving.harness.settle(40)

    assert leaving.count("torch") == 10
    assert chest(leaving, "torch") == 40


def test_an_import_chest_below_its_number_is_left_alone(leaving):
    """Never tops up. Filling a chest is an export bus's job."""
    leaving.harness.do("rule", 2, 0, "only", "torch")
    leaving.harness.do("rule", 2, 0, "torch", 10)
    leaving.harness.do("buscountin", 2, 0, "container")
    leaving.harness.fill(3, 0, "torch", 4)
    leaving.harness.fill(1, 0, "torch", 100)

    leaving.harness.settle(40)

    assert chest(leaving, "torch") == 4
    assert leaving.count("torch") == 100


def test_items_added_to_an_import_chest_later_are_taken_above_the_number(leaving):
    leaving.harness.do("rule", 2, 0, "only", "torch")
    leaving.harness.do("rule", 2, 0, "torch", 10)
    leaving.harness.do("buscountin", 2, 0, "container")
    leaving.harness.fill(3, 0, "torch", 10)
    leaving.harness.settle(20)
    assert leaving.count("torch") == 0, "at its number, so nothing moved"

    leaving.harness.fill(3, 0, "torch", 25)
    leaving.harness.settle(40)

    assert chest(leaving, "torch") == 10
    assert leaving.count("torch") == 25


def test_an_unnumbered_item_is_taken_in_full_in_either_mode(leaving):
    leaving.harness.do("rule", 2, 0, "only", "torch")
    leaving.harness.do("buscountin", 2, 0, "container")
    leaving.harness.fill(3, 0, "torch", 30)

    leaving.harness.settle(40)

    assert chest(leaving, "torch") == 0
    assert leaving.count("torch") == 30


def test_the_import_mode_survives_a_save_and_load(leaving):
    leaving.harness.do("rule", 2, 0, "only", "torch")
    leaving.harness.do("rule", 2, 0, "torch", 10)
    leaving.harness.do("buscountin", 2, 0, "container")

    leaving.harness.do("bussave", 2, 0)

    leaving.harness.fill(3, 0, "torch", 50)
    leaving.harness.settle(40)
    assert chest(leaving, "torch") == 10, "the reloaded bus should still count in its chest"


# -- the conflict matrix on one shared chest ---------------------------------------------------------------
#
# `two_buses` shares a chest between an import bus at (2,0) and an export bus at (3,1). The importer is set
# unjudged with `rule`; the exporter's whole proposal -- item, number and mode, in one Apply as the panel sends
# it -- is then judged against it. One Apply matters: from two network-mode buses, switching either one alone
# gives a mixed pair, and every mixed pair with numbers is refused, so the second bus has to arrive at its
# final state in a single step.
#
#   importer   exporter    rests when
#   network    network     fill-to C <= keep F           (the existing rule, covered in test_bus_states.py)
#   container  container   stock S <= leave L
#   network    container   only at S == 0 (not tested: the verbs read 0 as "no number")
#   container  network     never


def _importer(two_buses, mode: str, n: int):
    two_buses.do("rule", 2, 0, "only", "stone", n)
    two_buses.do("buscountin", 3, 1, "network")  # a neutral exporter while the importer is set
    two_buses.do("rule", 3, 1, "deny", "stone")
    two_buses.do("buscountin", 2, 0, mode, "accepted")


@pytest.mark.parametrize("leave, stock, verdict", [
    (100, 50, "accepted"),
    (50, 50, "accepted"),
    (50, 100, "refused"),
])
def test_both_counting_in_the_chest(two_buses, leave, stock, verdict):
    """Import leaves L, export stocks S: any count between S and L is a resting state, if there is one."""
    _importer(two_buses, "container", leave)
    two_buses.do("busapply", 3, 1, "stone", stock, verdict, "container")


def test_a_container_exporter_against_a_network_importer_is_refused(two_buses):
    _importer(two_buses, "network", 500)
    two_buses.do("busapply", 3, 1, "stone", 20, "refused", "container")


def test_a_container_importer_against_a_network_exporter_is_refused(two_buses):
    """Leave N in the chest against send everything above F: whether it rests depends on what the player
    owns, not on the rules, so it is refused rather than discovered as churn."""
    _importer(two_buses, "container", 50)
    two_buses.do("busapply", 3, 1, "stone", 100, "refused", "network")


def test_the_importer_is_judged_too_when_it_changes_mode(two_buses):
    """From the other side: an exporter already stocks the chest, and the importer's own switch is refused."""
    _importer(two_buses, "container", 100)
    two_buses.do("busapply", 3, 1, "stone", 50, "accepted", "container")

    two_buses.do("buscountin", 2, 0, "network", "refused")


def test_both_counting_in_the_chest_settle_between_their_numbers(two_buses):
    """Behaviour, not only the verdict: an accepted pair actually comes to rest, without churn."""
    _importer(two_buses, "container", 80)
    two_buses.do("busapply", 3, 1, "stone", 30, "accepted", "container")
    two_buses.fill(1, 0, "stone", 200)
    two_buses.fill(3, 0, "stone", 150)

    two_buses.settle(80)

    in_chest = two_buses.query("container", 3, 0, "stone")["count"]
    assert 30 <= in_chest <= 80, in_chest
    assert two_buses.query("busstate", 2, 0)["state"] == "active"
    assert two_buses.query("busstate", 3, 1)["state"] == "active"
