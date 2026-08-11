"""Reproduces, headlessly, the panel edit that failed in a real client.

Kept as its own file because it is a regression test for a specific mistake in how the buses were tested,
not for a behaviour of the buses. The first bus panel lost every edit in game while every headless test
passed, and the reason is that the tests drove the server's container action with a *freshly constructed*
filter. A client never does that: it builds its filter by reading the server's, edits that copy, and writes
it back. The read-before-edit was the untested step.
"""

from __future__ import annotations

import pytest


@pytest.fixture
def bus(storage):
    """An export bus on a network, so nothing is ticked to begin with and a tick is unambiguous."""
    storage.place("terminal", 0, 0)
    storage.place("unit", 1, 0)
    storage.place("exportbus", 2, 0)
    storage.place("storagebox", 3, 0)
    return storage


def test_an_edit_made_on_a_copy_of_the_servers_filter_survives(bus):
    """The exact cycle the panel performs: open, copy, tick, send, apply."""
    assert bus.query("busfilter", 2, 0, "ironbar")["allowed"] is False

    bus.do("busroundtrip", 2, 0, "ironbar")

    assert bus.query("busfilter", 2, 0, "ironbar")["allowed"] is True, \
        "a tick made on the client's copy reached the bus"


def test_an_amount_set_on_a_copy_survives_too(bus):
    """The half Elias saw fail first."""
    bus.do("busroundtrip", 2, 0, "ironbar", 40)

    assert bus.query("busfilter", 2, 0, "ironbar") == {"allowed": True, "target": 40}


def test_an_edit_survives_when_the_bus_already_allows_everything(storage):
    """The import bus's default is the other starting state, and it writes a different packet shape: the
    filter is all-allowed, so the tree's "all items allowed" flags are set rather than clear."""
    storage.place("terminal", 0, 0)
    storage.place("unit", 1, 0)
    storage.place("importbus", 2, 0)
    storage.place("storagebox", 3, 0)
    assert storage.query("busfilter", 2, 0, "ironbar")["allowed"] is True, "everything allowed to begin with"

    storage.do("busroundtrip", 2, 0, "ironbar", 25)

    assert storage.query("busfilter", 2, 0, "ironbar") == {"allowed": True, "target": 25}
