"""The three transfer buttons, and the performance budget behind them.

Ported from `tests/scenarios/container_transfers.txt` and `performance.txt`.

Quick-stack, deposit-all and restock are vanilla concepts — `Container` has slot indices for all
three — and the only thing this mod does is point them at the network instead of at nearby
containers. So what is being tested is the redirection, not the mechanic.
"""

from __future__ import annotations


def test_quick_stack_only_tops_up_what_the_network_already_holds(terminal):
    """The distinction that makes quick-stack useful: it does not dump everything.

    Iron goes in because the network already has iron; stone stays in the player's hands because
    nothing in the network is stone.
    """
    terminal.harness.fill(1, 0, "ironbar", 10)
    terminal.harness.give("ironbar", 20)
    terminal.harness.give("stone", 30)
    terminal.open()

    terminal.harness.quickstack()

    assert terminal.count("ironbar") == 30
    assert terminal.harness.held("ironbar") == 0
    assert terminal.harness.held("stone") == 30, "stone has no home in the network yet"


def test_deposit_all_takes_everything(terminal):
    terminal.harness.give("stone", 30)
    terminal.open()

    terminal.deposit_all()

    assert terminal.count("stone") == 30
    assert terminal.harness.held("stone") == 0


def test_restock_pulls_back_what_the_player_already_carries(terminal):
    """The mirror of quick-stack: it refills a stack in hand rather than emptying the network."""
    terminal.harness.fill(1, 0, "ironbar", 30)
    terminal.harness.give("ironbar", 5)
    terminal.open()

    terminal.harness.restock()

    assert terminal.count("ironbar") == 0
    assert terminal.harness.held("ironbar") == 35


def test_the_buttons_conserve_items_across_a_close(terminal):
    """Closing must not lose anything in flight, and nothing should linger in the player's hands that
    the network thinks it has."""
    terminal.harness.give("stone", 30)
    terminal.open()
    terminal.deposit_all()
    terminal.harness.close_container()

    assert terminal.harness.query("total", "stone")["count"] == 30
    assert terminal.harness.held("stone") == 0


def test_aggregation_stays_within_budget_on_the_largest_allowed_network(storage):
    """The cost the interface pays on every redraw, at the ceiling rather than at a sample.

    MAX_UNITS is 64, so 2560 slots is the largest network the mod permits. The `bench` verb runs the
    aggregation repeatedly and fails on its own budget, which is why there is no number asserted here:
    the budget lives with the measurement so a failure says what it cost and what it was allowed.
    """
    storage.place("terminal", 0, 0)

    storage.do("bench", 0, 0, 64, 200)

    assert storage.query("units", 0, 0)["units"] == 64
    used, total = storage.query("capacity", 0, 0)["used"], storage.query("capacity", 0, 0)["total"]
    assert (used, total) == (2560, 2560), "the bench fills every slot it creates"
