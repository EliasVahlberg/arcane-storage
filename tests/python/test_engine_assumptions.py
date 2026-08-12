"""Assumptions about the engine that a design would rest on, measured rather than read.

Kept apart from the mod's own tests: these assert what *Necesse* does, so a failure here means the game
changed or a reading of it was wrong, not that the mod broke.
"""

from __future__ import annotations


def test_how_many_inventory_listeners_the_engine_notifies(storage):
    """`Inventory`'s notify site uses `if (updateIterator.hasNext())`, not `while`.

    If that reading is right, only the first listener is ever told, and an event-driven design cannot
    subscribe to a vanilla inventory alongside anything else -- including the game's own open-container
    forms, which are its main users.
    """
    storage.place("storagebox", 3, 0)

    result = storage.query("listenercheck", 3, 0)

    print(f"\ntwo listeners attached, one slot changed: {result}")
    assert result["first"] > 0, "the first listener is notified"
    assert result["second"] == 0, (
        "measured: the second listener is never notified. Pinned as an assumption rather than reported as a "
        "bug, because the consequence is ours to live with: we must not attach a listener to an inventory we "
        "do not own -- we would either get nothing, or take the only notification away from the game's own "
        "container forms, which are this mechanism's main users. If this ever fails, Necesse has fixed it and "
        "our change-detection workaround can go."
    )
