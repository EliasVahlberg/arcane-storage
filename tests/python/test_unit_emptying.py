"""Emptying one storage unit into the rest of its network, so it can be picked up and moved.

Breaking a full unit does not destroy anything — ``StorageUnitObjectEntity`` inherits
``InventoryObjectEntity.getDroppedItems``, which returns every stack — so this is convenience rather than
rescue. What it replaces is up to forty item entities on the floor, in a game where anyone standing nearby
can pick them up and where a full bag means walking away from the remainder.

Because nothing is at risk of destruction, the assertions worth making are about **conservation and
refusal** rather than about survival: the network holds exactly what it held, a unit is never its own
destination, and every case where nothing can move says which case it was instead of failing silently.

One path is not covered here and is deliberately left to in-game testing: a destination network that is
*partially* full, giving ``partial``. Filling a 40-slot unit to capacity needs forty distinct item kinds,
and a test that hardcodes forty string IDs is a test that breaks when any one of them is renamed. The
outcome is separated from ``no_room`` in the code and reported to the player; that split is what needs eyes
on it, not arithmetic.
"""

from __future__ import annotations


def test_a_unit_empties_into_the_other_unit_on_the_network(storage):
    """The plain case: everything moves, the source is empty, the network still holds it all."""
    storage.place("terminal", 0, 0)
    storage.place("unit", 1, 0)
    storage.settle(5)

    # Deposited while only one unit exists, so the items are known to be in that unit rather than
    # wherever the network happened to put them.
    storage.give("oaklog", 200)
    storage.give("ironbar", 30)
    storage.do("open", 0, 0)
    storage.do("depositall")
    storage.do("close")
    storage.settle(5)

    assert storage.query("empty", 1, 0)["usedslots"] == 2, "the deposit did not land in the unit"

    storage.place("unit", 0, 1)
    storage.settle(5)

    storage.do("empty", 1, 0)
    storage.settle(5)

    state = storage.query("empty", 1, 0)
    assert state["outcome"] == "emptied"
    assert state["moved"] == 230
    assert state["remaining"] == 0
    assert state["usedslots"] == 0, "the unit still holds something after reporting itself emptied"

    # The whole point: the items are elsewhere on the network, not gone.
    assert storage.query("item", 0, 0, "oaklog")["count"] == 200
    assert storage.query("item", 0, 0, "ironbar")["count"] == 30
    assert storage.query("empty", 0, 1)["usedslots"] == 2


def test_every_stack_moves_not_just_the_first(storage):
    """A unit occupying many slots empties completely, which a per-slot loop can easily get wrong."""
    kinds = ["oaklog", "ironbar", "stone", "clay", "copperbar"]

    storage.place("terminal", 0, 0)
    storage.place("unit", 1, 0)
    storage.settle(5)

    for kind in kinds:
        storage.give(kind, 17)

    storage.do("open", 0, 0)
    storage.do("depositall")
    storage.do("close")
    storage.settle(5)
    assert storage.query("empty", 1, 0)["usedslots"] == len(kinds)

    storage.place("unit", 0, 1)
    storage.settle(5)

    storage.do("empty", 1, 0)
    storage.settle(5)

    state = storage.query("empty", 1, 0)
    assert state["outcome"] == "emptied"
    assert state["moved"] == 17 * len(kinds)
    assert state["usedslots"] == 0

    for kind in kinds:
        assert storage.query("item", 0, 0, kind)["count"] == 17, f"{kind} did not survive the move"


def test_a_unit_is_never_its_own_destination(storage):
    """The only-unit case must refuse, not report success while everything stays put.

    This is the assertion that catches a missing self-exclusion. With the unit in its own target list the
    operation would insert each stack back into the inventory it came from and report ``emptied``, which
    looks like success and changes nothing.
    """
    storage.place("terminal", 0, 0)
    storage.place("unit", 1, 0)
    storage.settle(5)

    storage.give("oaklog", 150)
    storage.do("open", 0, 0)
    storage.do("depositall")
    storage.do("close")
    storage.settle(5)

    storage.do("empty", 1, 0)
    storage.settle(5)

    state = storage.query("empty", 1, 0)
    assert state["outcome"] == "no_other_units"
    assert state["moved"] == 0
    assert state["remaining"] == 150
    assert state["usedslots"] == 1
    assert storage.query("item", 0, 0, "oaklog")["count"] == 150


def test_an_empty_unit_says_so(storage):
    """Nothing to move is its own outcome, so the button can stay quiet rather than lying."""
    storage.place("terminal", 0, 0)
    storage.place("unit", 1, 0)
    storage.place("unit", 0, 1)
    storage.settle(5)

    storage.do("empty", 1, 0)
    storage.settle(5)

    state = storage.query("empty", 1, 0)
    assert state["outcome"] == "nothing_to_move"
    assert state["moved"] == 0


def test_only_storage_units_can_be_emptied(storage):
    """A tile that is not a storage unit refuses, and reports itself as not one.

    The terminal is the case that matters: it is an object entity with an inventory of its own, so a check
    written against ``InventoryObjectEntity`` rather than against the unit would try to empty it.
    """
    storage.place("terminal", 0, 0)
    storage.place("unit", 1, 0)
    storage.settle(5)

    assert storage.query("empty", 0, 0)["storageunit"] is False
    assert storage.query("empty", 1, 0)["storageunit"] is True

    storage.do("empty", 0, 0)
    storage.settle(5)
    assert storage.query("empty", 0, 0)["outcome"] == "not_a_unit"


def test_emptying_works_across_conduits(storage):
    """The destination need not be adjacent: the network is whatever the conduits reach.

    Worth its own test because the target set comes from ``UnitUpgrade.pool``, which walks conduits — a
    version that only looked at neighbouring tiles would pass every test above and fail in any real base.
    """
    storage.place("terminal", 0, 0)
    storage.place("unit", 1, 0)
    storage.settle(5)

    storage.give("stone", 64)
    storage.do("open", 0, 0)
    storage.do("depositall")
    storage.do("close")
    storage.settle(5)

    storage.place("conduit", 0, 1)
    storage.place("conduit", 0, 2)
    storage.place("unit", 0, 3)
    storage.settle(5)

    storage.do("empty", 1, 0)
    storage.settle(5)

    assert storage.query("empty", 1, 0)["outcome"] == "emptied"
    assert storage.query("empty", 0, 3)["usedslots"] == 1
    assert storage.query("item", 0, 0, "stone")["count"] == 64
