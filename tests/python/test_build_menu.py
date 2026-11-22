"""Build Menu compat: the network is offered to Build Menu's material list when connected.

The jar under test is the Workshop copy, copied into this checkout's own game-data directory by the
conftest. The whole module skips when it is absent, because the soft dependency is exactly that:
nothing here is required to run without it, and every test below is about what happens when it is
present.

The "connected" rule: the player carries a wireless terminal paired to a transceiver. The server
decides this from the player's real inventory and the server's own world; a client has no input to
it, which is why there is no client-side test -- there is no client-side path.
"""

from __future__ import annotations

import pytest

import buildmenu


@pytest.fixture(autouse=True)
def _require_build_menu():
    if not buildmenu.AVAILABLE:
        pytest.skip("Build Menu Workshop jar not present")


def test_the_advice_is_woven(storage):
    state = storage.query("buildmenu")
    print(f"\nbuildmenu: {state}")
    assert state["enabled"], "the config toggle is on by default"
    assert state["active"], "the advice should be woven into Build Menu's method by name"


def test_the_network_is_counted_and_consumed(storage):
    """Two units, material only in the network, and a paired terminal: the network pays.

    After one placement the remaining count is read back and equals exactly nine of ten, which is the
    index-correctness assertion rather than merely "no crash".
    """
    storage.place("transceiver", 0, 0)
    storage.place("unit", 1, 0)
    storage.place("unit", 1, 1)
    storage.fill(1, 0, "stone", 5)
    storage.fill(1, 1, "stone", 5)
    storage.do("pair", 0, 0)

    before = storage.query("buildmenu")
    storage.do("buildmenucraft", "stone")
    after = storage.query("buildmenu")

    assert storage.query("item", 0, 0, "stone")["count"] == 9
    assert storage.held("stone") == 0, "a placement spends material, it does not hand it back"
    assert after["appended"] > before["appended"], "the advice should have appended the network's ranges"


def test_no_terminal_means_no_network(storage):
    """The rule is carrying a paired terminal; without one the network is not on the list."""
    storage.place("transceiver", 0, 0)
    storage.place("unit", 1, 0)
    storage.fill(1, 0, "stone", 5)

    reply = storage.call("buildmenucraft", "stone")

    assert reply.ok is False, "without a paired terminal the network must not pay"
    assert storage.query("item", 0, 0, "stone")["count"] == 5


def test_a_terminal_paired_elsewhere_counts_only_that_network(storage):
    """Two separate networks; the pairing picks one, and the other is left alone."""
    storage.place("transceiver", 0, 0)
    storage.place("unit", 1, 0)
    storage.place("transceiver", 5, 0)
    storage.place("unit", 6, 0)
    storage.fill(1, 0, "stone", 5)
    storage.fill(6, 0, "stone", 5)
    storage.do("pair", 0, 0)

    storage.do("buildmenucraft", "stone")

    assert storage.query("item", 0, 0, "stone")["count"] == 4
    assert storage.query("item", 5, 0, "stone")["count"] == 5


def test_the_players_inventory_drains_before_the_network(storage):
    """Order matters: the player's own pockets go first, the network is the last resort."""
    storage.place("transceiver", 0, 0)
    storage.place("unit", 1, 0)
    storage.fill(1, 0, "stone", 5)
    storage.give("stone", 5)
    storage.do("pair", 0, 0)

    storage.do("buildmenucraft", "stone")

    assert storage.held("stone") == 4, "the inventory should pay before the network"
    assert storage.query("item", 0, 0, "stone")["count"] == 5


def test_a_cost_split_across_both_drains_each_once(storage):
    """One of the two needed is in the bag and one in the network; each pays exactly one."""
    storage.place("transceiver", 0, 0)
    storage.place("unit", 1, 0)
    storage.fill(1, 0, "stone", 5)
    storage.give("stone", 1)
    storage.do("pair", 0, 0)

    storage.do("buildmenucraft", "stone")
    storage.do("buildmenucraft", "stone")

    assert storage.held("stone") == 0
    assert storage.query("item", 0, 0, "stone")["count"] == 4


def test_consumption_keeps_the_index_exact(storage):
    """The removal must go through ``Inventory.updateSlot`` so the index learns it, not recount.

    A bus holds the index, so the network has someone watching; after Build Menu spends one stone the
    index should agree with the world with no rebuild -- the same guarantee a settler's fill gets.
    """
    storage.place("transceiver", 0, 0)
    storage.place("unit", 1, 0)
    storage.place("importbus", 2, 0)
    storage.place("storagebox", 3, 0)
    storage.settle(40)
    storage.fill(1, 0, "stone", 10)
    storage.settle(2)
    storage.do("busstatsreset")
    storage.do("pair", 0, 0)

    storage.do("buildmenucraft", "stone")
    storage.settle(2)

    drift = storage.query("indexdrift")
    stats = storage.query("busstats")
    print(f"\nindex after Build Menu consumption: drift={drift} stats={stats}")
    assert drift["drift"] == 0, "the index should already know about Build Menu's removal"
    assert stats["rebuilds"] == 0, "and it should have learnt it without recounting the network"
    assert storage.query("item", 0, 0, "stone")["count"] == 9


def test_placement_cost_stays_inside_a_frame(storage):
    """The Build Menu author's stated concern -- summing two inventory systems -- measured.

    The cost is a range list plus one engine count over it, and both grow with the number of units
    rather than with anything this mod adds. The assertion is loose on purpose: it guards against a
    pathological regression, not against machine noise.
    """
    for size in (1, 4, 16):
        storage.do("reset")
        storage.settle(2)
        storage.place("transceiver", 0, 0)
        for i in range(size):
            storage.place("unit", 1 + i, 0)
        storage.do("pair", 0, 0)
        storage.settle(10)

        sample = storage.query("buildmenucost", 0, 0, "stone")
        print(f"\n{size} units: ranges={sample['ranges']} build={sample['buildNs'] / 1000:.1f}us "
              f"canCraft={sample['cancraftNs'] / 1000:.1f}us")
        assert sample["ranges"] == 2 + size, "main + cloud + one range per unit"
        # The engine's count over the whole network must stay well inside a fiftieth of a second.
        assert sample["cancraftNs"] < 1_000_000, f"{size} units took {sample['cancraftNs'] / 1_000_000:.1f}ms to count"


def test_the_network_summary_aggregates_and_round_trips(storage):
    """Equal items summed (including a count above one stack), unequal kept apart, wire exact."""
    storage.place("transceiver", 0, 0)
    storage.place("unit", 1, 0)
    storage.place("unit", 2, 0)
    storage.fill(1, 0, "stone", 5000)  # above one stack (250)
    storage.fill(2, 0, "stone", 7)
    storage.fill(1, 0, "oaklog", 3)
    storage.do("pair", 0, 0)

    summary = storage.query("buildmenusummary", "stone")
    print(f"\nsummary: {summary}")
    assert summary["distinct"] == 2, "stone and oaklog, each once"
    assert summary["roundtrip"] is True, "decode(encode(summary)) must preserve every count exactly"
    assert summary["amount"] == 5007, "5000 + 7 in one stack, above the stack size, not clamped"
    assert summary["bytes"] > 0


def test_variants_with_different_saved_data_stay_apart(storage):
    """Same item, different saved data: two stacks, not one merged count."""
    storage.place("transceiver", 0, 0)
    storage.place("unit", 1, 0)
    storage.place("unit", 2, 0)
    storage.fill(1, 0, "coinpouch", 1)
    storage.do("stuffpouch", "1", "0", "coinpouch", "coin", "500")
    storage.fill(2, 0, "coinpouch", 1)
    storage.do("stuffpouch", "2", "0", "coinpouch", "coin", "200")
    storage.do("pair", 0, 0)

    summary = storage.query("buildmenusummary")
    assert summary["distinct"] == 2, "two coin pouches with different coins stay apart"


def test_same_variants_merge(storage):
    """Same item and same saved data: one stack with the summed count."""
    storage.place("transceiver", 0, 0)
    storage.place("unit", 1, 0)
    storage.place("unit", 2, 0)
    storage.fill(1, 0, "coinpouch", 1)
    storage.do("stuffpouch", "1", "0", "coinpouch", "coin", "500")
    storage.fill(2, 0, "coinpouch", 1)
    storage.do("stuffpouch", "2", "0", "coinpouch", "coin", "500")
    storage.do("pair", 0, 0)

    summary = storage.query("buildmenusummary", "coinpouch")
    assert summary["distinct"] == 1
    assert summary["amount"] == 2


def test_the_packet_size_is_bounded(storage):
    for n in (1, 50, 600):
        size = storage.query("buildmenupacketsize", str(n))
        print(f"\n{n} distinct items: {size['bytes']} bytes")
        assert size["bytes"] < 200_000, "600 items must stay a small packet"


def test_the_summary_send_is_deduplicated(storage):
    """An unchanged network is not resent; a burst of changes sends one update, not one per item."""
    storage.place("transceiver", 0, 0)
    storage.place("unit", 1, 0)
    storage.do("pair", 0, 0)

    storage.do("buildmenusweep")
    first = storage.query("buildmenu")["summaryPackets"]
    assert first == 1, "a connected player gets one summary"

    storage.do("buildmenusweep")
    second = storage.query("buildmenu")["summaryPackets"]
    assert second == first, "an unchanged network is not resent"

    for _ in range(10):
        storage.fill(1, 0, "stone", 1)
    storage.do("buildmenusweep")
    third = storage.query("buildmenu")["summaryPackets"]
    print(f"\npackets sent: {first} -> {second} -> {third}")
    assert third == first + 1, "a burst of changes sends one update, not one per change"
