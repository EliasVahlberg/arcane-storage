"""Depositing what the player is holding — the server half of the storage panel's click conventions.

The clicks themselves cannot be tested here: a mouse button, a hit test and a cursor are all client
state, and this suite drives a dedicated server. What *can* be tested is the part where a mistake
would cost the player items, which is the arithmetic in `DepositCursorAction` — how much it moves,
what it leaves behind, and whether the total survives the round trip.

That split is the point. `withdraw <item> <n> cursor` and `depositcursor [n]` reach the same
`executePacket` the form calls, so everything below the click is covered; only the wiring above it
needs eyes.
"""

from __future__ import annotations


def test_the_whole_held_stack_goes_back_into_the_network(terminal):
    """A left click while holding something: everything held is inserted."""
    terminal.harness.fill(1, 0, "stone", 40)
    terminal.open()
    terminal.withdraw("stone", 40, to_cursor=True)
    assert terminal.count("stone") == 0, "the withdrawal should have emptied the network"
    assert terminal.harness.held("stone") == 40, "and put it all on the cursor"

    terminal.harness.do("depositcursor")

    assert terminal.count("stone") == 40
    assert terminal.harness.held("stone") == 0


def test_depositing_one_leaves_the_rest_on_the_cursor(terminal):
    """A right click while holding something: exactly one item moves.

    The interesting failure is not "one item moved" but the remainder: an off-by-one in the cursor
    arithmetic either destroys the held stack or duplicates it, and both look like a working deposit
    from the network's side alone. Hence asserting both halves.
    """
    terminal.harness.fill(1, 0, "stone", 10)
    terminal.open()
    terminal.withdraw("stone", 10, to_cursor=True)

    terminal.harness.do("depositcursor", "1")

    assert terminal.count("stone") == 1
    assert terminal.harness.held("stone") == 9


def test_items_are_conserved_across_repeated_partial_deposits(terminal):
    """Ten single deposits must not create or destroy a stone.

    This is the invariant worth protecting: every path that moves items between the cursor and the
    network runs the same code, so a conservation bug here would apply to every click a player makes.
    """
    terminal.harness.fill(1, 0, "stone", 10)
    terminal.open()
    terminal.withdraw("stone", 10, to_cursor=True)

    for _ in range(10):
        terminal.harness.do("depositcursor", "1")
        assert terminal.count("stone") + terminal.harness.held("stone") == 10

    assert terminal.count("stone") == 10
    assert terminal.harness.held("stone") == 0


def test_a_full_network_refuses_and_leaves_the_cursor_untouched(terminal):
    """Refusing has to be non-destructive.

    `Inventory.addItem` decrements the item it is handed by however much it consumed, so the deposit
    reads the leftover to decide what to take off the cursor. If that were read backwards, a full
    network would silently eat the stack -- which is why this asserts the cursor, not the network.
    """
    # Get the stone onto the cursor first, while there is still room, because the cursor can only be
    # filled from the network.
    terminal.harness.fill(1, 0, "stone", 40)
    terminal.open()
    terminal.withdraw("stone", 40, to_cursor=True)
    assert terminal.harness.held("stone") == 40

    # Then fill the unit with something non-stackable. 40 stone occupies one slot, not forty -- an
    # earlier version of this test filled with stone and asserted a full unit, and was wrong about
    # its own premise. A pickaxe stacks to one, so forty of them is forty slots.
    terminal.harness.fill(1, 0, "ironpickaxe", 40)
    used, total = terminal.capacity()
    assert used == total, f"the unit should be full for this test, got {used}/{total}"

    terminal.harness.do("depositcursor")

    assert terminal.harness.held("stone") == 40, "a refused deposit must not consume items"
    assert terminal.count("stone") == 0
