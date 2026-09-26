"""Pouches and bags — items that carry an inventory of their own inside their GND data.

Reported by Seramicx on the Workshop (18 Sep): a lunchbox, a coin pouch and a bag put into
the network could not be taken back out. All three are `misc/pouches` items, and all three
either extend `PouchItem` (which implements `InternalInventoryItemInterface`) or, like
`CoinPouch`, keep per-instance state in GND data and compare it in `isSameGNDData`.

Two independent things could produce "can't get them out", so both are asserted separately:
the network has to *see* the item (aggregation), and the withdrawal has to *match* it.
"""

from __future__ import annotations

import pytest

POUCHES = ["lunchbox", "coinpouch", "voidbag"]


@pytest.mark.parametrize("pouch", POUCHES)
def test_the_round_trip_a_player_actually_performs(terminal, pouch):
    """Give the player the pouch, deposit it through the terminal, take it back.

    The reported path. `fill` writes straight into a unit's slot, which is not how the pouch
    got there in the report -- it went in through Deposit All, and a pouch is an item whose
    own class takes part in `Inventory.addItem`: `PouchItem.inventoryAddItem` can divert an
    incoming item *into* a pouch that is already sitting in the target inventory. If that
    happens during a deposit, the pouch is inside another pouch in the network, which no grid
    cell and no withdrawal can reach.
    """
    terminal.harness.give(pouch, 1)
    terminal.open()
    terminal.deposit_all()

    assert terminal.count(pouch) == 1, f"Deposit All should have put the {pouch} in the network"

    terminal.withdraw(pouch, 1)

    assert terminal.count(pouch) == 0, f"the {pouch} should have come back out"
    assert terminal.harness.query("playerinv", pouch)["count"] == 1, (
        f"and be in the player's inventory again"
    )


@pytest.mark.parametrize("pouch", POUCHES)
@pytest.mark.parametrize("stuffed", [False, True])
def test_a_plain_click_picks_a_pouch_up_onto_the_cursor(terminal, pouch, stuffed):
    """The default click: `withdraw ... cursor`, which goes through `combineSlots`, not a transfer.

    Every other withdrawal here is a shift-click. A plain left click is what a player does first,
    and it takes a different path: `ContainerSlot.combineSlots(..., AGGREGATE_PURPOSE)` onto the
    empty cursor, where the pouch's own `canCombineItem`/`onCombine` get a say.
    """
    terminal.harness.fill(1, 0, pouch, 1)
    if stuffed:
        terminal.harness.do("stuffpouch", "1", "0", pouch)
    terminal.open()

    terminal.withdraw(pouch, 1, to_cursor=True)

    assert terminal.count(pouch) == 0, f"the {pouch} should have left the network"
    assert terminal.harness.held(pouch) == 1, f"and be on the cursor"


def test_two_bags_deposited_together_do_not_swallow_each_other(terminal):
    """Three pouches at once, which is what the report describes.

    A pouch already in the network is asked by `Inventory.addItem` whether it can take the
    incoming item, and `PouchItem.inventoryCanAddItem` answers from its *internal* inventory
    without checking the purpose at all. Only `inventoryAddItem` checks. If the two ever
    disagree here, items go somewhere the network cannot see.
    """
    for pouch in ("voidbag", "lunchbox", "coinpouch"):
        terminal.harness.give(pouch, 1)

    terminal.open()
    terminal.deposit_all()

    for pouch in ("voidbag", "lunchbox", "coinpouch"):
        assert terminal.count(pouch) == 1, f"the {pouch} should be one visible network entry"
        terminal.withdraw(pouch, 1)
        assert terminal.count(pouch) == 0, f"the {pouch} should come back out"


def test_an_export_bus_does_not_raid_a_stored_bag(terminal):
    """The same question asked of a bus, which is the path that really removes from the network.

    `BusObjectEntity.move` finishes with `from.removeItems(level, null, item.item, accepted,
    PURPOSE)`, and `Inventory.removeItems` offers every slot's item a chance to supply the
    request. `PouchItem.removeInventoryAmount` accepts for any purpose outside its
    `requestPurposes` blacklist -- which holds only `quickstackto` -- so `arcanestoragebus`
    qualifies on its own terms.

    It passes for an indirect reason worth writing down, because it is load-bearing and nothing
    declares it: the scheduler plans from `NetworkIndex`, and the index counts slot items only.
    The bread inside the lunchbox is therefore not in the plan, so no removal is ever attempted
    and the donation path is never reached. Anything that later plans a removal from a count it
    did *not* get from the index -- a settler job, a crafting path, a future "empty this unit"
    that walks inventories directly -- loses that protection and can raid stored bags.
    """
    terminal.harness.place("exportbus", 2, 0)
    terminal.harness.place("storagebox", 3, 0)

    terminal.harness.fill(1, 0, "lunchbox", 1)
    terminal.harness.do("stuffpouch", "1", "0", "lunchbox", "bread", "5")
    terminal.harness.do("rule", 2, 0, "only", "bread")

    before = terminal.count("lunchbox")
    terminal.harness.settle(20)

    assert terminal.harness.query("container", 3, 0, "bread")["count"] == 0, (
        "the export bus pulled bread out of a lunchbox stored in the network"
    )
    # Relative, not absolute: the session shares one level, and an earlier test's lunchbox can
    # still be in the unit. What matters is that the bus moved none of them.
    assert terminal.count("lunchbox") == before, "and no lunchbox itself was exported"
    terminal.harness.place("exportbus", 2, 0)
    terminal.harness.place("storagebox", 3, 0)

    terminal.harness.fill(1, 0, "lunchbox", 1)
    terminal.harness.do("stuffpouch", "1", "0", "lunchbox", "bread", "5")
    terminal.harness.do("rule", 2, 0, "only", "bread")

    before = terminal.count("lunchbox")
    terminal.harness.settle(20)

    assert terminal.harness.query("container", 3, 0, "bread")["count"] == 0, (
        "the export bus pulled bread out of a lunchbox stored in the network"
    )
    # Relative, not absolute: the session shares one level, and an earlier test's lunchbox can
    # still be in the unit. What matters is that the bus moved none of them.
    assert terminal.count("lunchbox") == before, "and no lunchbox itself was exported"


def test_a_stored_pouch_is_not_a_supply_of_its_own_contents(terminal):
    """A stored bag's contents must not be reachable by anything that removes from the network.

    `PouchItem.removeInventoryAmount` hands out what is inside the pouch for any purpose that is
    not in its `requestPurposes` blacklist -- which by default holds only `quickstackto`, so every
    purpose this mod invented qualifies. That makes a stored bag a hidden supply: the index counts
    the bag, never its contents, so anything drawing on the network can take items the terminal
    never showed. For a Void Bag that is someone's whole loot stash.

    Asserted through withdrawal, which is the shortest path that removes from a unit.
    """
    terminal.harness.fill(1, 0, "lunchbox", 1)
    terminal.harness.do("stuffpouch", "1", "0", "lunchbox", "bread", "5")
    terminal.open()

    assert terminal.count("bread") == 0, (
        "bread inside a stored lunchbox is not network stock, and the terminal does not show it"
    )

    # Ask for something the network does not visibly have. If the pouch donates, this succeeds
    # and five bread appear from inside it.
    terminal.withdraw("bread", 5)

    assert terminal.harness.query("playerinv", "bread")["count"] == 0, (
        "the withdrawal took bread out of the stored lunchbox -- a stored bag was raided"
    )


@pytest.mark.parametrize("pouch", POUCHES)
def test_an_empty_pouch_can_be_withdrawn(terminal, pouch):
    """The baseline: nothing in the pouch, so no GND data to disagree about."""
    terminal.harness.fill(1, 0, pouch, 1)
    terminal.open()

    assert terminal.count(pouch) == 1, f"the network should hold the {pouch}"

    terminal.withdraw(pouch, 1)

    assert terminal.count(pouch) == 0, f"the {pouch} should have left the network"


@pytest.mark.parametrize("pouch", POUCHES)
def test_a_pouch_with_something_inside_can_be_withdrawn(terminal, pouch):
    """The reported case: the pouch holds items, so its GND data is non-empty.

    This is the half that a fresh `new InventoryItem(id, 1)` comparison cannot represent —
    if the withdrawal matches on GND data, a pouch whose contents the request does not know
    about can never be found.
    """
    terminal.harness.fill(1, 0, pouch, 1)
    terminal.harness.do("stuffpouch", "1", "0", pouch)
    terminal.open()

    assert terminal.count(pouch) == 1, f"the network should hold the stuffed {pouch}"

    terminal.withdraw(pouch, 1)

    assert terminal.count(pouch) == 0, f"the stuffed {pouch} should have left the network"


@pytest.mark.parametrize("pouch", POUCHES)
def test_a_stuffed_pouch_is_visible_in_the_grid(terminal, pouch):
    """Aggregation must list it, whatever its contents.

    Separate from the withdrawal because an item the grid never draws has no cell to click,
    which is the other way a player sees "stuck in there". `expect item` is the aggregating
    path -- `query item` counts slots by string ID and would pass even if aggregation dropped
    the entry entirely.
    """
    terminal.harness.fill(1, 0, pouch, 1)
    terminal.harness.do("stuffpouch", "1", "0", pouch)
    terminal.open()

    assert terminal.harness.expect("item", 0, 0, pouch, 1).ok, (
        f"the {pouch} should appear as an aggregated entry"
    )


# --- A request that no longer matches what is stored -------------------------------------------
#
# The leading theory for the report, since the reporter's bag is modded. A withdrawal is matched
# with `equals(level, wanted, ignoreMeta=true, ignoreGNDData=false, ...)`, so the item's own
# `isSameGNDData` decides. The request has been through `InventoryItem.addPacketContent`, which does
# not carry `isNew`; `GNDItemInventory.equals` compares the items *inside* a bag including `isNew`.
# A modded bag that compares its whole GND map -- CoinPouch's pattern, with no keys -- therefore
# never matches its own round-tripped request, and every click does nothing.
#
# Vanilla bags inherit `isSameGNDData -> true`, which is why nothing here reproduced. CoinPouch does
# compare a key, so a coin pouch with a deliberately wrong `coins` value in the request stands in
# for the modded bag without needing the mod.


def test_a_request_whose_gnd_data_drifted_still_withdraws_the_only_variant(terminal):
    """One coin pouch in the network, requested with the wrong coin count: it must still come out."""
    terminal.harness.fill(1, 0, "coinpouch", 1)
    terminal.harness.do("stuffpouch", "1", "0", "coinpouch", "coin", "500")
    terminal.open()

    terminal.harness.do("withdraw", "coinpouch", "1", "gnd:coins=1")

    assert terminal.count("coinpouch") == 0, (
        "a request that no longer matches exactly should fall back to the network's only variant"
    )
    assert terminal.harness.query("playerinv", "coinpouch")["count"] == 1


def test_a_drifted_request_never_swaps_one_variant_for_another(terminal):
    """Two different coin pouches, and a request matching neither: nothing moves.

    The fallback's other half, and the one that protects the player. With two variants there is no
    way to know which was clicked, and handing out the wrong one -- a different enchantment, a
    different bag's contents -- is worse than a click that does nothing.
    """
    terminal.harness.fill(1, 0, "coinpouch", 1)
    terminal.harness.do("stuffpouch", "1", "0", "coinpouch", "coin", "500")
    terminal.harness.fill(1, 0, "coinpouch", 1)

    terminal.open()
    before = terminal.count("coinpouch")
    assert before >= 2, "the setup needs two distinct coin pouches"

    terminal.harness.do("withdraw", "coinpouch", "1", "gnd:coins=7")

    assert terminal.count("coinpouch") == before, "an ambiguous drifted request must not withdraw"
