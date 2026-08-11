"""The terminal showing itself as in use — the half of a multiplayer question one client can answer.

Elias could not verify that a terminal looks open to *other* players, because a Steam account cannot
join the same server twice ("Client "Tester" was already playing"). But the part that decides what
other clients draw is not visual: a terminal advertises itself through vanilla's `OEUsers`, which
`InventoryObjectEntity` writes into its content packet, so every other client renders from this
server-side count. Getting the count right is therefore necessary, and only the sprite is left over.

The failure mode these tests are shaped around: a user entry **expires after two seconds** unless
refreshed (`constructUsersObject(2000L)`). A container that asserted its user once at open would look
open briefly and then go dark while still open — which is exactly the bug that would show up for a
second player and never for yourself, since your own client also draws from local state.
"""

from __future__ import annotations

import time


def test_an_open_terminal_reports_one_user(terminal):
    terminal.harness.place("unit", 1, 0)
    assert terminal.users() == 0, "nobody is using it yet"

    terminal.open()

    assert terminal.users() == 1


def test_the_user_survives_longer_than_the_two_second_timeout(terminal):
    """The real test. Anything that merely opens and checks would pass without the per-tick refresh.

    Waits past the timeout in wall-clock time, because the entry expires against world time that only
    advances while the server ticks -- so this is also a check that the container keeps asserting the
    user while the player does nothing at all.
    """
    terminal.harness.place("unit", 1, 0)
    terminal.open()
    assert terminal.users() == 1

    time.sleep(3.0)

    assert terminal.users() == 1, "the container must re-assert its user; entries expire after 2s"


def test_closing_the_terminal_clears_the_user(terminal):
    """Otherwise a terminal would look permanently open to everyone else."""
    terminal.harness.place("unit", 1, 0)
    terminal.open()
    assert terminal.users() == 1

    terminal.harness.do("close")

    assert terminal.users() == 0
