"""The client half of the Build Menu compat, driven without a window.

The compat sends one compact summary of the network to each connected client, and Build Menu's own
tools and form read the client's copy. Until this file, the pieces were tested apart: the packet's
serialisation, and the statics behind the accessor. What a player's game actually does -- a real
client, a real packet through the packet's own `processClient`, the client-side state, and the
per-tick re-apply -- was only ever reasoned about from the source.

It is reachable here because the client class ships in the same jar as the server, and its
singleplayer constructor touches no window and no graphics: settings, the DLC provider, loading, chat,
commands and the packet manager. The frame drain runs before the loading gate, so a client that never
connected still processes a packet. So `query buildmenuclient` builds one, hands it a packet, runs a
frame, ticks it a few times, reads the state and retires it -- all inside the harness server's own JVM.

What it still cannot see, and what stays a window check: Build Menu's greyed-out display, its own
refusal message, and anything drawn at all. What it does see -- which no test did before -- is that
the packet decodes on a real client, that the client-side state is what was sent, that an empty
summary clears it, and that the client-tick advice actually runs.

The module skips when Build Menu's jar is absent, like `test_build_menu.py`, because the compat is a
soft dependency and every assertion here is about what happens when it is installed.
"""

from __future__ import annotations

import pytest

import buildmenu


@pytest.fixture(autouse=True)
def _require_build_menu():
    if not buildmenu.AVAILABLE:
        pytest.skip("Build Menu Workshop jar not present")


#: A count above one stack, because the summary carries totals that do not fit in a stack and the
#: packet has to keep them rather than split or clamp them.
ABOVE_A_STACK = 600


@pytest.fixture()
def connected(storage):
    """A paired wireless terminal and one unit holding the materials.

        y=0:   R  U        R transceiver, U storage unit

    A transceiver is enough of a network for the summary: it answers as a network head, so the
    compat resolves the same units a terminal would.
    """
    storage.place("transceiver", 0, 0)
    storage.place("unit", 1, 0)
    storage.fill(1, 0, "stone", ABOVE_A_STACK)
    storage.do("pair", 0, 0)
    return storage


def test_the_summary_reaches_a_real_clients_state(connected):
    """The whole client path at once: packet, decode, client-side state, and the tick behind it."""
    answered = connected.query("buildmenuclient", "stone")

    assert answered["available"] is True, "a paired terminal should offer the network"
    assert answered["active"] is True, "the compat's own targets should be woven in"
    assert answered["sent"] == ABOVE_A_STACK, "the server-side summary is the network's total"
    assert answered["distinct"] == 1, "one distinct item, however many stacks it occupies"
    assert answered["amount"] == ABOVE_A_STACK, "an amount above a stack survives the packet whole"
    assert answered["ticks"] > 0, "the client-tick advice did not run: it is not woven into Client.tick"
    assert answered["failures"] == 0, "the client path caught a failure; the server log names it"
    assert answered["cleared"] is True, "an empty summary must clear the cached one"


def test_a_second_client_is_left_nothing_to_inherit(connected):
    """Two probes in a row, because the harness server is one JVM and the client's statics are global.

    The state has to be rebuilt from the packet every time rather than remembered, and a probe has to
    leave nothing behind that changes the next one's answer -- the constructor registers listeners on
    the game's own settings, and those must not start calling into a client that is finished with.
    """
    first = connected.query("buildmenuclient", "stone")
    second = connected.query("buildmenuclient", "stone")

    assert second == first, "a second client should see exactly what the first one did"
    assert second["cleared"] is True, "and it must still clear the state before it is retired"


def test_the_network_changing_is_what_the_client_sees(connected):
    """Not a frozen copy of the first summary: the state follows the network the packet describes."""
    before = connected.query("buildmenuclient", "stone")["amount"]
    connected.fill(1, 0, "stone", 200)

    after = connected.query("buildmenuclient", "stone")["amount"]

    assert after == before + 200, "the client's count should follow what the server summarised"
