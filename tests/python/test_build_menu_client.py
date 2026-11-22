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

import time

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


@pytest.fixture()
def paste_target(connected):
    """A real Build Menu paste tool, made live in the harness's own JVM.

    Its constructor takes three plain values and touches no window, so this is the genuine class, the
    same one the game creates when a player picks a blueprint up -- registered as its class's active
    instance by its own constructor, exactly as it is in play. All three snapshot targets share one
    implementation of the "does it already hold our range" check and one field name, so this proves
    the rule end to end for one of them.

    The form cannot be built headlessly (it needs the game's own form machinery), so what it cannot
    prove is the form's own rebuild -- the thing the tooltip hangs off. That stays a window check; its
    field is covered by the load-time shape check, which the unit tests exercise.
    """
    assert connected.call("buildmenuclienttarget", "paste").ok, "the paste tool should be buildable here"
    return connected


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


def test_an_unchanged_summary_is_not_handed_over_again(paste_target):
    """The item-tooltip flash, counted instead of seen.

    Build Menu answers a storage snapshot by rebuilding its item elements, and a hovered tooltip is
    destroyed and recreated with the element it belongs to. Re-applying an unchanged summary on the
    client's own tick therefore made a tooltip flash on and off at the re-apply cadence, twice a
    second, and only while the compat had something to hand over.

    The target here is a real one holding the summary, so this is the actual condition: spaced past
    the re-apply cadence, its ticks must hand nothing over. The spacing matters -- a tight loop sits
    inside one cadence window and sees nothing either way, which is how the old behaviour hid from a
    first attempt at this assertion.
    """
    primed = paste_target.query("buildmenuclient", "stone", "keep")
    assert primed["pasteHandovers"] == 1, "the packet should hand the summary over once"
    assert primed["pasteHolds"] is True, "and the target should be holding it"

    handed_over = 0
    for _ in range(2):
        time.sleep(0.55)
        answered = paste_target.query("buildmenuclient", "stone", "tickonly")
        handed_over += answered["pasteHandovers"]
        assert answered["ticks"] > 0, "the advice should still run on every tick"

    assert handed_over == 0, (
        "the target already held the summary and was handed it again, which rebuilds its grid and "
        "takes the hovered tooltip with it")


def test_a_target_that_lost_its_summary_is_handed_it_once(paste_target):
    """The other half, and the half a summary-only check would lose.

    Build Menu replaces its list with a fresh empty one when its menu is hidden, and outside a
    settlement no snapshot ever comes back to refill it -- the server unsubscribes and sends nothing.
    So after a close and reopen the network has to be offered by this mod's own re-apply, exactly once.

    `reset` does what Build Menu does: replaces the target's list with an empty one.
    """
    paste_target.query("buildmenuclient", "stone", "keep")
    assert paste_target.call("buildmenuclienttarget", "paste", "reset").ok, "the list should be emptied"

    time.sleep(0.55)
    first = paste_target.query("buildmenuclient", "stone", "tickonly")

    assert first["pasteHandovers"] == 1, "a target that lost the summary should be handed it again"
    assert first["pasteHolds"] is True, "and should be holding it afterwards"

    time.sleep(0.55)
    second = paste_target.query("buildmenuclient", "stone", "tickonly")

    assert second["pasteHandovers"] == 0, "and not handed it a second time"


def test_the_form_cannot_be_driven_headlessly(paste_target):
    """Stated rather than glossed: the target whose rebuild the tooltip hangs off is not reachable here.

    The form needs the game's own form machinery, so the harness sees no instance of it. Its field and
    accessor are checked at load with the other signatures, and its rebuild is what the window check
    looks at.
    """
    answered = paste_target.query("buildmenuclienttarget", "form")

    assert answered["instance"] is False, "if this ever becomes buildable, this test should say so loudly"


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
    """Not a frozen copy of the first summary: the state follows the network the packet describes.

    The other half of the fix above. A re-apply that never ran would pass that test and break the
    compat, so the pair is what actually pins it: nothing when nothing changed, and the new number
    when something did.
    """
    before = connected.query("buildmenuclient", "stone")["amount"]
    connected.fill(1, 0, "stone", 200)

    after = connected.query("buildmenuclient", "stone")["amount"]

    assert after == before + 200, "the client's count should follow what the server summarised"
