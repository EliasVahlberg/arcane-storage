"""Import and export buses: ordinary containers, in and out of the network.

The buses are the answer to "can chests join the network", and the answer is deliberately no: a chest
never joins. A bus stands between the chest and the network and carries items across, so settlers keep
using a container they understand and reach, and nothing in the network is ever exposed to settler
access.

Reading a plain chest needs `expect container`, not `expect item`: this mod replaces the harness's
generic `item` with a network-wide reading, which is right for a terminal and blind to a chest.

A bus's rules are vanilla's `ItemCategoriesFilter`, the same object behind "configure storage" on a
settlement chest, so the vocabulary here is the game's: an item is allowed or not, and a number is how
much of it *the network* should hold. An import bus fills up to that number; an export bus drains down to
it. `harness rule` writes the filter directly, which is what the panel does with a click.

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


def test_an_unconfigured_import_bus_moves_everything(bussed):
    """An import bus starts with every item allowed, because importing only adds to the network. That is
    vanilla's own `allowAll` constructor flag rather than a rule of ours."""
    bussed.harness.fill(3, 0, "ironbar", 30)

    bussed.harness.do("transfer", 2, 0)

    assert bussed.count("ironbar") == 30
    assert bussed.harness.query("container", 3, 0, "ironbar")["count"] == 0, "the chest is emptied"


def test_a_rule_makes_the_import_bus_selective(bussed):
    """An unticked item stays where it is.

    The rule goes on before the chest is filled, because a bus now acts as soon as something changes: an
    unconfigured bus given thirty stone will have taken it before the rule arrives, which is correct behaviour
    and not what this test is about.
    """
    # 'only' is the panel's "Clear all" button followed by ticking one item, which is what a player does.
    bussed.harness.do("rule", 2, 0, "only", "ironbar")
    bussed.harness.fill(3, 0, "ironbar", 30)
    bussed.harness.fill(3, 0, "stone", 30)

    bussed.harness.settle(20)

    assert bussed.count("ironbar") == 30
    assert bussed.count("stone") == 0, "stone is not ticked, so it does not move"


def test_a_number_tops_the_network_up_and_stops(bussed):
    """"Accept X only while the network holds fewer than N" — the number read in the import direction."""
    bussed.harness.fill(3, 0, "ironbar", 100)
    bussed.harness.do("rule", 2, 0, "ironbar", 60)

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


def test_an_unconfigured_export_bus_moves_nothing(storage):
    """The asymmetry, and it is a safety property rather than a setting: an export bus starts with nothing
    ticked, so placing one cannot empty a network by surprise."""
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

    The floor and the ceiling are the same number seen from opposite sides, which is why one control
    serves both: "the network should hold 100" means fill to 100 on an import bus and drain to 100 here.

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
    storage.do("rule", 2, 0, "ironbar", 100)

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


def test_the_panel_opens_and_its_edits_reach_the_bus(storage):
    """As far as automation can reach into the interface, and it is the half that can be wrong quietly.

    The panel is `ItemCategoriesFilterForm` — the game's own, the one behind "configure storage" on a
    settlement chest — so it is client-side and a headless server never builds it. What this covers is
    everything underneath: the container is registered and opens, and a whole filter written by the client
    survives `writePacket` -> our container action -> `readPacket` and lands on the bus.

    Drawing is item 13 in QA_BACKLOG and only Elias can sign that off.
    """
    storage.place("terminal", 0, 0)
    storage.place("unit", 1, 0)
    storage.place("exportbus", 2, 0)
    storage.place("storagebox", 3, 0)
    storage.fill(1, 0, "ironbar", 60)
    assert storage.query("busfilter", 2, 0, "ironbar")["allowed"] is False, "nothing ticked to begin with"

    storage.do("open", 2, 0)
    storage.do("busedit", "ironbar", 40)

    state = storage.query("busfilter", 2, 0, "ironbar")
    assert (state["allowed"], state["target"]) == (True, 40)

    storage.do("transfer", 2, 0, 3)

    assert Terminal(storage, 0, 0).count("ironbar") == 40, "and the edit changed what the bus does"
    assert storage.query("container", 3, 0, "ironbar")["count"] == 20


@pytest.mark.slow  # restarts the server: one JVM boot, by far the most expensive thing here
def test_rules_survive_a_restart(storage):
    """A bus is configured once and expected to keep working, so its filter has to be saved. Vanilla's
    own save format does that work; this checks we nest and reload it correctly."""
    storage.place("terminal", 0, 0)
    storage.place("unit", 1, 0)
    storage.place("exportbus", 2, 0)
    storage.place("storagebox", 3, 0)
    storage.fill(1, 0, "ironbar", 60)
    storage.do("rule", 2, 0, "ironbar", 50)
    state = storage.query("busfilter", 2, 0, "ironbar")
    assert (state["allowed"], state["target"]) == (True, 50)

    storage.restart()

    state = storage.query("busfilter", 2, 0, "ironbar")
    assert (state["allowed"], state["target"]) == (True, 50), \
        "the rule came back from disk"

    storage.do("transfer", 2, 0, 2)

    assert Terminal(storage, 0, 0).count("ironbar") == 50, "and it still means what it said"
