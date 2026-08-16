"""What the terminal's transfer buttons may take from, and put into, the player.

"Locked" means two unrelated things in Necesse, and the distinction is the whole point of this file:

* A **pinned slot** -- `Inventory.setItemLocked`, the per-slot toggle bound to the `invlock` control. Vanilla's
  own sort and quick-stack skip these, so anything here that moves items must too.
* A **locked hotbar** -- `PlayerMob.hotbarLocked`, one flag covering slots 0-9. Vanilla enforces it only on the
  way *in*: `PlayerInventoryManager.addItem` will top up a stack already in the hotbar but will not start a new
  one there, and `ui/hotbarlockedtip` describes it purely as preventing pickups and item selection.

That asymmetry is why the terminal has to decide something vanilla does not. Vanilla's chest quick-stack empties
a locked hotbar quite happily, because it only ever consults the per-slot pins. For a button called "deposit
all" that is the wrong answer: a player who locked their hotbar has said which items should stay on their
person, and a single click emptying it is exactly the accident the flag exists to prevent.

So the terminal treats a locked hotbar as off limits to anything taking items *out* -- deposit-all and
quick-stack -- while leaving restock alone, since restock can only refill a stack the player put there
themselves. These tests pin down both halves of that, including the half that was deliberately not changed.
"""

from __future__ import annotations

import pytest

from conftest import Terminal


HOTBAR = 0
# The first slot past the hotbar: PlayerInventoryManager treats 0-9 as the hotbar.
FIRST_BACKPACK = 10


@pytest.fixture
def terminal(storage):
    """A terminal with two units behind it, and a player who leaves nothing behind.

    The teardown matters more than usual here. These tests exist to leave items sitting in the player's hotbar,
    which is exactly the state every later test assumes it does not have to think about -- and the first attempt
    at this leaked a stocked inventory two files away, where tests that read a network's contents found a stock
    nobody had put there.

    It cleans up by unlocking and depositing rather than by emptying the inventory directly. Clearing all fifty
    slots on every `reset` was tried first and made things considerably worse: nine tests across five unrelated
    files began failing, most of them reading -1 for objects that should have been standing, so whatever that
    disturbs is not understood and is not worth finding out for a tidier teardown. Depositing uses the path the
    mod already ships, and `reset` removes the units the items land in anyway.
    """
    storage.place("terminal", 0, 0)
    storage.place("unit", 1, 0)
    storage.place("unit", 0, 1)
    storage.settle(20)
    handle = Terminal(storage, 0, 0)

    yield handle

    storage.do("lock", "hotbar", 0)
    for index in range(FIRST_BACKPACK):
        storage.do("lock", "slot", index, 0)

    handle.open()
    storage.do("depositall")
    storage.settle(5)


def slot(harness, index: int) -> dict:
    return harness.query("playerslot", index)


def test_a_pinned_slot_survives_deposit_all(terminal):
    """The per-slot pin, which vanilla's own sort and quick-stack already honour."""
    terminal.harness.do("give", "ironbar", 20)
    assert int(slot(terminal.harness, HOTBAR)["amount"]) == 20, "give should land in the first free slot"
    terminal.harness.do("lock", "slot", HOTBAR, 1)

    terminal.open()
    terminal.harness.do("depositall")
    terminal.harness.settle(5)

    assert int(slot(terminal.harness, HOTBAR)["amount"]) == 20, "a pinned slot must not be deposited"
    assert terminal.count("ironbar") == 0


def test_a_locked_hotbar_survives_deposit_all(terminal):
    """The flag Elias actually had set. Before the fix this moved the lot into the network."""
    terminal.harness.do("give", "ironbar", 20)
    terminal.harness.do("lock", "hotbar", 1)
    assert slot(terminal.harness, HOTBAR)["hotbarlocked"]

    terminal.open()
    terminal.harness.do("depositall")
    terminal.harness.settle(5)

    assert int(slot(terminal.harness, HOTBAR)["amount"]) == 20, "a locked hotbar must not be emptied"
    assert terminal.count("ironbar") == 0


def test_a_locked_hotbar_survives_quick_stack(terminal):
    """Quick-stack is the same act by another name, so the same rule has to hold.

    The network already holds the item, which is the condition quick-stack requires before it will move
    anything -- without it this test would pass for the wrong reason.
    """
    terminal.harness.do("give", "ironbar", 20)
    terminal.open()
    terminal.harness.do("depositall")
    terminal.harness.settle(5)
    assert terminal.count("ironbar") == 20

    terminal.harness.do("give", "ironbar", 15)
    terminal.harness.do("lock", "hotbar", 1)
    terminal.harness.do("quickstack")
    terminal.harness.settle(5)

    assert int(slot(terminal.harness, HOTBAR)["amount"]) == 15, "quick-stack must leave a locked hotbar alone"
    assert terminal.count("ironbar") == 20


def test_restock_may_still_top_up_a_locked_hotbar(terminal):
    """The rule is deliberately not applied to restock, and this is the test that says so.

    `Inventory.restockFrom` only combines with items already present, so restock cannot start a new stack in an
    empty hotbar slot however it is called -- the most it can do is refill one the player put there. Vanilla
    permits exactly that much: `PlayerInventoryManager.addItem` falls back to `addItemOnlyCombine` over slots 0-9
    rather than refusing. Refilling arrows and potions is the whole reason the button exists.

    So this fails if the hotbar rule is ever over-applied to restock by symmetry with deposit-all.
    """
    terminal.harness.fill(1, 0, "ironbar", 40)
    terminal.harness.do("give", "ironbar", 5)
    terminal.harness.do("lock", "hotbar", 1)
    assert int(slot(terminal.harness, HOTBAR)["amount"]) == 5

    terminal.open()
    terminal.harness.do("restock")
    terminal.harness.settle(5)

    assert int(slot(terminal.harness, HOTBAR)["amount"]) > 5, (
        "restock should still refill a stack the player chose to keep in a locked hotbar"
    )


def test_an_unlocked_hotbar_still_deposits(terminal):
    """The control. Without this, every test above would pass on a deposit-all that did nothing at all."""
    terminal.harness.do("give", "ironbar", 20)
    terminal.open()
    terminal.harness.do("depositall")
    terminal.harness.settle(5)

    assert int(slot(terminal.harness, HOTBAR)["amount"]) == 0
    assert terminal.count("ironbar") == 20


def test_the_backpack_still_deposits_when_the_hotbar_is_locked(terminal):
    """A locked hotbar must not turn deposit-all off entirely -- only slots 0-9 are protected.

    Ten *distinct* items are needed to occupy ten slots. An earlier version of this test gave `stone 1` nine
    times and got a single stack of nine in one slot, so the item meant to land in the backpack was still inside
    the hotbar and the test failed against correct code.
    """
    hotbar_fillers = ["ironbar", "goldbar", "copperbar", "stone", "torch",
                      "oaklog", "birchlog", "pinelog", "quartz", "glass"]
    for item in hotbar_fillers:
        terminal.harness.do("give", item, 3)

    terminal.harness.do("give", "ironore", 7)
    assert int(slot(terminal.harness, FIRST_BACKPACK)["amount"]) == 7, "the 11th distinct item should be in slot 10"

    terminal.harness.do("lock", "hotbar", 1)
    terminal.open()
    terminal.harness.do("depositall")
    terminal.harness.settle(5)

    assert terminal.count("ironore") == 7, "items outside the hotbar should still be deposited"
    assert terminal.count("ironbar") == 0, "the locked hotbar is untouched"
