"""Import and export buses: ordinary containers, in and out of the network.

The buses are the answer to "can chests join the network", and the answer is deliberately no: a chest
never joins. A bus stands between the chest and the network and carries items across, so settlers keep
using a container they understand and reach, and nothing in the network is ever exposed to settler
access.

Reading a plain chest needs `expect container`, not `expect item`: this mod replaces the harness's
generic `item` with a network-wide reading, which is right for a terminal and blind to a chest.

`transfer` runs a bus's move immediately instead of waiting out its one-second interval — it calls
exactly what the tick calls. One call moves one item type, which is why some tests call it repeatedly.
"""

from __future__ import annotations

import pytest

from conftest import Terminal


@pytest.fixture
def bussed(storage):
    """A terminal, one unit, a chest, and an import bus between the chest and the unit.

        y=0:   T  U  I  C

    T terminal, U unit, I import bus, C chest. The bus touches the unit, so it is on the network; it
    touches the chest, so it has something to move. The chest is a vanilla `storagebox`, which is what a
    player would actually have.
    """
    storage.place("terminal", 0, 0)
    storage.place("unit", 1, 0)
    storage.place("importbus", 2, 0)
    storage.place("storagebox", 3, 0)
    return Terminal(storage, 0, 0)


def test_a_bus_is_on_the_network_it_touches(bussed):
    """A bus finds its network by walking from its own tile, exactly as a terminal does."""
    assert bussed.units() == 1


def test_an_import_bus_with_no_rules_moves_everything(bussed):
    """Empty rules are permissive in this direction, because importing only adds to the network."""
    bussed.harness.fill(3, 0, "ironbar", 30)

    bussed.harness.do("transfer", 2, 0)

    assert bussed.count("ironbar") == 30
    assert bussed.harness.query("container", 3, 0, "ironbar")["count"] == 0, "the chest is emptied"


def test_a_rule_makes_the_import_bus_selective(bussed):
    """Once any rule exists the list is a whitelist, so the unlisted item stays where it is."""
    bussed.harness.fill(3, 0, "ironbar", 30)
    bussed.harness.fill(3, 0, "stone", 30)
    bussed.harness.do("rule", 2, 0, "ironbar")

    bussed.harness.do("transfer", 2, 0, 4)

    assert bussed.count("ironbar") == 30
    assert bussed.count("stone") == 0, "no rule matches stone, so it does not move"


def test_a_ceiling_tops_the_network_up_and_stops(bussed):
    """`limit` is the "accept X only when fewer than N" reading of the primitive."""
    bussed.harness.fill(3, 0, "ironbar", 100)
    bussed.harness.do("rule", 2, 0, "ironbar", "limit", 60)

    bussed.harness.do("transfer", 2, 0, 6)

    assert bussed.count("ironbar") == 60
    assert bussed.harness.query("container", 3, 0, "ironbar")["count"] == 40, "the rest stays in the chest"


def test_an_import_bus_conserves_items(bussed):
    """The property that matters most: a transfer adds first and removes only what was accepted, so no
    path through it can duplicate or destroy a stack."""
    bussed.harness.fill(3, 0, "ironbar", 55)

    bussed.harness.do("transfer", 2, 0, 3)

    in_network = bussed.count("ironbar")
    in_chest = bussed.harness.query("container", 3, 0, "ironbar")["count"]
    assert in_network + in_chest == 55, f"{in_network} in the network, {in_chest} in the chest"


def test_a_full_network_leaves_the_chest_alone(storage):
    """A destination with no room must be a no-op rather than a hole."""
    storage.place("terminal", 0, 0)
    storage.place("unit", 1, 0)
    storage.place("importbus", 2, 0)
    storage.place("storagebox", 3, 0)
    terminal = Terminal(storage, 0, 0)
    # 40 unstackable pickaxes fill all 40 slots of one unit: stone would occupy a single slot.
    storage.fill(1, 0, "ironpickaxe", 40)
    storage.fill(3, 0, "ironbar", 30)

    storage.do("transfer", 2, 0, 3)

    assert terminal.count("ironbar") == 0
    assert storage.query("container", 3, 0, "ironbar")["count"] == 30, "nothing was consumed"


def test_an_export_bus_with_no_rules_moves_nothing(storage):
    """The asymmetry, and it is a safety property: an export bus is inert until told what to send, so
    placing one cannot empty a network by surprise."""
    storage.place("terminal", 0, 0)
    storage.place("unit", 1, 0)
    storage.place("exportbus", 2, 0)
    storage.place("storagebox", 3, 0)
    storage.fill(1, 0, "ironbar", 50)

    storage.do("transfer", 2, 0, 3)

    assert Terminal(storage, 0, 0).count("ironbar") == 50, "still all in the network"
    assert storage.query("container", 3, 0, "ironbar")["count"] == 0


def test_an_export_bus_sends_the_surplus_and_respects_the_floor(storage):
    """Phase 5's acceptance criterion, minus the selling: overflow of a chosen item leaves the network
    and a reserve floor is respected.

    A Shipping Chest is the natural target in a real base — it already sells what it holds through
    trader missions above a stack, so "sell my surplus" needs no selling machinery here — but the
    transfer is the part this mod owns, so an ordinary chest is what is asserted.
    """
    storage.place("terminal", 0, 0)
    storage.place("unit", 1, 0)
    storage.place("unit", 1, 1)
    storage.place("exportbus", 2, 0)
    storage.place("storagebox", 3, 0)
    terminal = Terminal(storage, 0, 0)
    storage.fill(1, 0, "ironbar", 60)
    storage.fill(1, 1, "ironbar", 60)
    storage.do("rule", 2, 0, "ironbar", "keep", 100)

    storage.do("transfer", 2, 0, 4)

    assert terminal.count("ironbar") == 100, "the floor is held, across units rather than per unit"
    assert storage.query("container", 3, 0, "ironbar")["count"] == 20, "only the surplus left"


def test_a_bus_needs_a_container_and_says_so_by_doing_nothing(storage):
    """A bus with nothing beside it is a no-op rather than an error: forgetting the chest is the most
    likely mistake when placing one, and the interact message names it in game."""
    storage.place("terminal", 0, 0)
    storage.place("unit", 1, 0)
    storage.place("importbus", 2, 0)

    storage.do("transfer", 2, 0, 3)  # succeeds; there is simply nothing to do

    assert Terminal(storage, 0, 0).capacity() == (0, 40), "nothing arrived from nowhere"


def test_a_bus_conducts_and_does_not_move_items_inside_its_own_network(storage):
    """Two properties in one layout, `T U I U`.

    A unit is an `OEInventory` too, so "the container next to me" has to exclude network members, or a bus
    between two units would shuffle items pointlessly and report work done.

    And the bus conducts, so the unit beyond it is still on the terminal's network. The first version of
    the bus did not conduct, and this layout is what exposed it: 30 iron bars sat in a unit two tiles from
    the terminal that the terminal could not see, because the bus had quietly cut the run.
    """
    storage.place("terminal", 0, 0)
    storage.place("unit", 1, 0)
    storage.place("importbus", 2, 0)
    storage.place("unit", 3, 0)
    storage.fill(3, 0, "ironbar", 30)

    storage.do("transfer", 2, 0, 3)

    assert storage.query("container", 3, 0, "ironbar")["count"] == 30, "the items never left the unit"
    assert storage.query("container", 1, 0, "ironbar")["count"] == 0, "and never arrived in the other"
    assert Terminal(storage, 0, 0).count("ironbar") == 30, "the network is unchanged"


def test_rules_survive_a_restart(storage):
    """A bus is configured once and expected to keep working, so its rules have to be saved. Costs the
    boot the persistence test already pays for, in the same run."""
    storage.place("terminal", 0, 0)
    storage.place("unit", 1, 0)
    storage.place("exportbus", 2, 0)
    storage.place("storagebox", 3, 0)
    storage.fill(1, 0, "ironbar", 60)
    storage.do("rule", 2, 0, "ironbar", "keep", 50)
    assert storage.query("rules", 2, 0)["rules"] == 1

    storage.restart()

    assert storage.query("rules", 2, 0)["rules"] == 1, "the rule came back from disk"
    assert "keep 50" in storage.query("rules", 2, 0)["description"]

    storage.do("transfer", 2, 0, 2)

    assert Terminal(storage, 0, 0).count("ironbar") == 50, "and it still means what it said"
