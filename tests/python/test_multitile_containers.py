"""Containers wider than one tile.

A bus finds the container it serves by looking at its orthogonal neighbours, and an object occupying
several tiles registers its entity on one of them. So a bus standing against the wrong half of a wide
container found nothing at all and reported that it served no container -- while the same bus against the
other half worked perfectly.

The Shipping Chest is the case reported from play, and it is the sharpest one available: it is not merely
two tiles, it is two *distinct objects*, `ShippingChestObject` plus a counterpart, with only the master
carrying the `ShippingChestObjectEntity`. Nothing about it is special beyond being wide, which is the point
of testing it -- the same fault applied to any object bigger than a tile.

It also looked like the order of placement mattered, which it never did: `attachedContainer` is recomputed
on every call and the inventory listener is re-registered every tick. What varies is which *physical* tile
ends up being the master, and that is decided by the rotation the chest was placed with.
"""

from __future__ import annotations

import pytest

from conftest import Terminal


# The chest is placed at its master tile; the counterpart lands one tile north of it. Both are asserted
# rather than assumed, because a test that quietly probed the master twice would pass while proving nothing.
CHEST_MASTER = (5, 3)
CHEST_COUNTERPART = (5, 2)


@pytest.fixture
def chest_network(storage):
    """A network reaching a bus that touches only the counterpart half of a Shipping Chest.

        y=0:   T  U  c  c  c  c
        y=1:                  I
        y=2:                  x     <- the chest's counterpart tile
        y=3:                  C     <- the chest's master tile

    The bus at (5,1) touches the conduit above it and the counterpart tile below it, and nothing else. If
    the counterpart does not resolve, this bus serves no container at all.
    """
    storage.place("terminal", 0, 0)
    storage.place("unit", 1, 0)
    for x in range(2, 6):
        storage.place("conduit", x, 0)

    storage.place("importbus", 5, 1)
    storage.place("shippingchest", *CHEST_MASTER)
    storage.settle(25)
    return Terminal(storage, 0, 0)


def test_the_chest_really_does_occupy_both_tiles(chest_network):
    """Guards the fixture rather than the mod: if a future game version stops writing the counterpart, every
    other test here would pass by testing the master twice."""
    assert chest_network.harness.query("busstate", 5, 1)["container"], (
        "the bus at 5,1 touches only the counterpart tile, so this failing means either the counterpart "
        "was never placed or it no longer resolves to the chest"
    )


def test_a_bus_finds_a_wide_container_from_either_half(chest_network):
    """Six sides, one container. Both halves are approached from every direction a player could build from."""
    sides = [
        (CHEST_MASTER[0] - 1, CHEST_MASTER[1]),
        (CHEST_MASTER[0] + 1, CHEST_MASTER[1]),
        (CHEST_MASTER[0], CHEST_MASTER[1] + 1),
        (CHEST_COUNTERPART[0] - 1, CHEST_COUNTERPART[1]),
        (CHEST_COUNTERPART[0] + 1, CHEST_COUNTERPART[1]),
    ]

    for (bx, by) in sides:
        chest_network.harness.place("importbus", bx, by)
        chest_network.harness.settle(10)
        found = chest_network.harness.query("busstate", bx, by)["container"]
        chest_network.harness.do("break", bx, by)
        chest_network.harness.settle(5)
        assert found, f"a bus at {bx},{by} does not see the chest it stands against"


def test_items_actually_cross_from_the_far_half(chest_network):
    """The flag is not the feature. A bus that reports a container but moves nothing is still broken, and
    only a transfer distinguishes the two."""
    chest_network.harness.fill(*CHEST_MASTER, "ironbar", 30)

    chest_network.harness.do("transfer", 5, 1)

    assert chest_network.count("ironbar") == 30
    chest_network.harness.expect("container", *CHEST_MASTER, "ironbar", 0)
