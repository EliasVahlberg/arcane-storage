"""Which way a bus faces.

The bus sprite points at the container it serves, and it picks that sprite from
`BusObjectEntity.attachedDirection()` -- an index into `UnitNetwork.NEIGHBOURS`. The sprite itself is beyond
anything here, since the harness has no client and never draws, but the number behind it is ordinary server
state and is exactly where a mistake would hide: an off-by-one or a reordering would point every bus the
wrong way while every transfer kept working perfectly.

The direction is deliberately the same scan, in the same order, that chooses the inventory items move
through. A bus whose sprite disagreed with where its items went would be worse than one with no sprite at
all, so these tests assert the two agree rather than testing the direction alone.
"""

from __future__ import annotations

import pytest

from conftest import Terminal


# UnitNetwork.NEIGHBOURS order: north, east, south, west.
NORTH, EAST, SOUTH, WEST = 0, 1, 2, 3


@pytest.fixture
def lone_bus(storage):
    """A bus on the network with all four of its sides free.

        y=0:   T  U  c
        y=1:         I

    The conduit above the bus keeps it on the network without occupying a side a chest could take: a
    conduit is not an inventory, so it is never mistaken for the container.
    """
    storage.place("terminal", 0, 0)
    storage.place("unit", 1, 0)
    storage.place("conduit", 2, 0)
    storage.place("importbus", 2, 1)
    storage.settle(25)
    return Terminal(storage, 0, 0)


def direction(harness) -> int:
    return int(harness.query("busstate", 2, 1)["direction"])


def test_a_bus_with_nothing_beside_it_faces_nowhere(lone_bus):
    assert direction(lone_bus.harness) == -1
    assert not lone_bus.harness.query("busstate", 2, 1)["container"]


@pytest.mark.parametrize(
    "dx,dy,expected,name",
    [
        (2, 0, NORTH, "north"),
        (3, 1, EAST, "east"),
        (2, 2, SOUTH, "south"),
        (1, 1, WEST, "west"),
    ],
)
def test_a_bus_faces_the_container_it_serves(storage, dx, dy, expected, name):
    """Each side in turn, on a fresh world, because a chest left in place would win the scan for the next.

    The north case wants a chest where a conduit would otherwise sit, so it reaches the network through a
    unit to the bus's west instead. Either way the bus is on the network, which is what makes the reading
    meaningful.
    """
    storage.place("terminal", 0, 0)
    storage.place("unit", 1, 0)
    if (dx, dy) == (2, 0):
        storage.place("unit", 1, 1)
    else:
        storage.place("conduit", 2, 0)

    storage.place("importbus", 2, 1)
    storage.place("storagebox", dx, dy)
    storage.settle(25)

    state = storage.query("busstate", 2, 1)
    assert state["container"], f"the bus should serve the chest to its {name}"
    assert int(state["direction"]) == expected, f"the bus should face {name}"


def test_the_direction_agrees_with_where_items_actually_go(storage):
    """The sprite must not point at one chest while items move through another.

    Two chests, one on each of two free sides. Whichever the scan picks, the reported direction has to be
    the side of the chest that actually empties.
    """
    storage.place("terminal", 0, 0)
    storage.place("unit", 1, 0)
    storage.place("conduit", 2, 0)
    storage.place("importbus", 2, 1)
    storage.place("storagebox", 3, 1)
    storage.place("storagebox", 2, 2)
    storage.settle(25)

    facing = int(storage.query("busstate", 2, 1)["direction"])
    assert facing in (EAST, SOUTH)

    east, south = (3, 1), (2, 2)
    storage.fill(*east, "ironbar", 10)
    storage.fill(*south, "stone", 10)
    storage.do("transfer", 2, 1)
    storage.do("transfer", 2, 1)
    storage.settle(10)

    terminal = Terminal(storage, 0, 0)
    served, ignored = (east, south) if facing == EAST else (south, east)
    served_item = "ironbar" if facing == EAST else "stone"
    ignored_item = "stone" if facing == EAST else "ironbar"

    assert terminal.count(served_item) == 10, (
        f"the bus reports facing {'east' if facing == EAST else 'south'}, so that chest is the one it drains"
    )
    storage.expect("container", *ignored, ignored_item, 10)
    storage.expect("container", *served, served_item, 0)
