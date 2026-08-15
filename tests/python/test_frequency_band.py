"""The Arcane frequency band — a Base Station, its Access Points, and one network out of two clusters.

The claim under test is the one Elias set as the requirement: storage behind an Access Point is *not a special kind of
member*. So most of these tests do not look at the band at all. They place units 200 tiles from a terminal, tune them
in, and then ask the terminal the ordinary questions — what do you hold, how much can you hold, does a withdrawal work
— because a silo that answers those identically is a silo that needed no new concept anywhere downstream.

What is deliberately checked *about* the band is the part a player can get wrong: the channel is exclusive, the range
is enforced, and the whole thing is dark without a Wireless Transceiver on the station's own cluster. Each of those
has a message attached to it, and a refusal nobody can read is a bug that passes its tests.

Two limits of this file, stated rather than worked around. Both ends live on one level, because the harness runs one
level and a band never crosses levels anyway. And nothing here tests the panels: the harness drives the server, so what
it can prove is that tuning is refused for the right reason, not that the dropdown showed it.
"""

from __future__ import annotations

import pytest

import costs

BASE = (0, 0)


@pytest.fixture()
def band(storage):
    """The band's configured numbers, read from the running server rather than restated here.

    They live in the config file, so a test that hardcoded them would fail the first time Elias tuned the range in
    play -- which is what the setting exists for. Everything below places its silos relative to these.
    """
    settings = storage.query("bandsettings")
    reach = settings["range"]
    return {
        "range": reach,
        "channels": {tier: settings[tier] for tier in ("demonic", "tungsten", "fallen")},
        # Twenty tiles, not a hundred. What separates two clusters is a single empty tile -- the walk only spreads
        # through touching tiles -- so twenty proves the band is what joined them just as well as two hundred would,
        # and it keeps the silo inside the radius the fixture clears between tests. It did not, once: silos left
        # standing outside that radius accumulated over a session and a later test failed for reasons belonging to
        # this file.
        "silo": (20, 0),
        "second": (20, 4),
        # The one thing that has to be genuinely far. Whatever is placed here is broken again by the test that
        # placed it.
        "far": (reach + 60, 0),
    }


def base_with_station(storage, transceiver: bool = True, station: str = "basestation"):
    """A terminal, a unit, a transceiver and a Base Station in a row at the origin.

    The transceiver is what the station transmits through, so the flag is how a test says 'no antenna'.
    """
    storage.place("terminal", 0, 0)
    storage.place("unit", 1, 0)
    if transceiver:
        storage.place("transceiver", 2, 0)
        storage.place(station, 3, 0)
    else:
        storage.place(station, 2, 0)

    storage.settle(5)
    return (3, 0) if transceiver else (2, 0)


def silo(storage, at, units: int = 1):
    """An Access Point with units behind it, disconnected from everything."""
    dx, dy = at
    storage.place("accesspoint", dx, dy)
    for i in range(units):
        storage.place("unit", dx + 1 + i, dy)

    storage.settle(5)
    return at


# -- the band comes up ----------------------------------------------------------------------------


def test_a_station_transmits_once_it_has_a_transceiver(storage, band):
    station = base_with_station(storage)
    state = storage.query("band", *station)

    assert state["device"] == "station"
    assert state["live"] is True
    assert state["band"] == 1, "the first band on a level should be band 1"
    assert state["channels"] == band["channels"]["demonic"]
    assert state["used"] == 0


def test_a_station_without_a_transceiver_is_dark(storage):
    """The requirement that ties the two wireless features together: the band transmits through the same antenna the
    wireless terminal pairs to, so a station on its own does nothing and says why."""
    station = base_with_station(storage, transceiver=False)
    state = storage.query("band", *station)

    assert state["live"] is False
    assert state["state"] == "no_transceiver"


def test_two_stations_on_one_network_both_go_dark(storage):
    """Neither wins, as with a bus rule conflict. A silent winner would leave the player looking at a station that
    reports itself fine while doing nothing."""
    base_with_station(storage)
    storage.place("basestation", 4, 0)
    storage.settle(10)

    assert storage.query("band", 3, 0)["state"] == "station_conflict"
    assert storage.query("band", 4, 0)["state"] == "station_conflict"


def test_a_transceiver_added_later_brings_the_band_up(storage):
    """Validation is driven by layout changes rather than by a timer, so this is the test that the trigger fires at
    all: place the antenna after the station and the band has to notice without anything else happening."""
    station = base_with_station(storage, transceiver=False)
    assert storage.query("band", *station)["live"] is False

    storage.place("transceiver", 3, 0)
    storage.settle(5)
    assert storage.query("band", *station)["live"] is True


# -- a silo is an ordinary part of the network -----------------------------------------------------


def test_a_tuned_silo_lends_its_capacity_to_the_terminal(storage, band):
    """The headline requirement. Two clusters 150 tiles apart, one capacity figure."""
    base_with_station(storage)
    before = storage.query("capacity", 0, 0)["total"]

    silo(storage, band["silo"], units=2)
    storage.do("tune", *band["silo"], "1", "1")
    storage.settle(10)

    after = storage.query("capacity", 0, 0)["total"]
    assert after == before + 2 * 40, "the silo's two units did not join the network"


def test_items_in_a_silo_are_visible_and_withdrawable_from_the_terminal(storage, band):
    """Not just counted: moved. A silo that showed up in the aggregate but could not be drawn from would be a worse
    lie than one that did not show up at all."""
    base_with_station(storage)
    silo(storage, band["silo"])
    storage.do("tune", *band["silo"], "1", "1")
    storage.settle(10)

    storage.do("open", 0, 0)
    storage.give("ironbar", 30)
    storage.do("depositall")
    storage.settle(5)

    # The base's own unit fills first, so a deposit of 30 with 40-stack units lands entirely in the base -- put the
    # bars straight into the silo's unit instead, which is what an import bus out there would do.
    assert storage.query("item", 0, 0, "ironbar")["count"] == 30

    storage.do("withdraw", "ironbar", "30")
    storage.settle(5)
    assert storage.query("playerinv", "ironbar")["count"] == 30


def test_a_silo_is_discovered_from_either_end(storage, band):
    """Symmetry, which is a correctness requirement rather than a nicety: a network is named by its lowest member tile
    and every member must find the same set, or two indexes end up counting the same units."""
    base_with_station(storage)
    silo(storage, band["silo"], units=2)
    storage.do("tune", *band["silo"], "1", "1")
    storage.settle(10)

    # The terminal is in the base; a terminal placed in the silo must see the same total.
    storage.place("terminal", band["silo"][0], band["silo"][1] + 1)
    storage.settle(5)

    assert storage.query("capacity", band["silo"][0], band["silo"][1] + 1)["total"] == storage.query("capacity", 0, 0)["total"]


def test_disconnecting_a_silo_takes_its_capacity_with_it(storage, band):
    base_with_station(storage)
    silo(storage, band["silo"], units=2)
    storage.do("tune", *band["silo"], "1", "1")
    storage.settle(10)
    connected = storage.query("capacity", 0, 0)["total"]

    storage.do("tune", *band["silo"], "0", "0")
    storage.settle(10)

    assert storage.query("capacity", 0, 0)["total"] == connected - 2 * 40
    assert storage.query("band", *band["silo"])["state"] == "no_band"


def test_breaking_the_station_disconnects_every_silo(storage, band):
    station = base_with_station(storage)
    silo(storage, band["silo"], units=2)
    storage.do("tune", *band["silo"], "1", "1")
    storage.settle(10)
    connected = storage.query("capacity", 0, 0)["total"]

    storage.break_at(*station)
    storage.settle(10)

    assert storage.query("capacity", 0, 0)["total"] == connected - 2 * 40
    # The setting survives rather than being cleared, so rebuilding the station is a retune and not a rebuild.
    state = storage.query("band", *band["silo"])
    assert state["band"] == 1
    assert state["state"] == "band_gone"


def test_losing_the_transceiver_takes_the_silo_off_the_network(storage, band):
    """The station's antenna is a single point of failure on purpose -- and the failure has to be visible at both ends,
    since the silo is where the storage went missing and the station is where the cause is."""
    base_with_station(storage)
    silo(storage, band["silo"], units=2)
    storage.do("tune", *band["silo"], "1", "1")
    storage.settle(10)
    connected = storage.query("capacity", 0, 0)["total"]

    storage.break_at(2, 0)
    storage.settle(10)

    assert storage.query("capacity", 0, 0)["total"] == connected - 2 * 40
    assert storage.query("band", 3, 0)["state"] == "no_transceiver"
    assert storage.query("band", *band["silo"])["state"] == "band_down"


# -- the rules a player meets ---------------------------------------------------------------------


def test_a_channel_holds_one_access_point(storage, band):
    """Refused the way a bus rule conflict is refused: at the moment of the attempt, with a reason, and without
    disturbing what is already there."""
    base_with_station(storage)
    silo(storage, band["silo"])
    silo(storage, band["second"])
    storage.do("tune", *band["silo"], "1", "1")
    storage.settle(5)

    reply = storage.call("tune", band["second"][0], band["second"][1], "1", "1")
    assert not reply.ok, "two access points took the same channel"
    assert "taken" in reply.describe()

    # The first one is untouched, which is the half of a refusal that is easy to get wrong.
    assert storage.query("band", *band["silo"])["state"] == "active"
    assert storage.query("band", band["second"][0], band["second"][1])["state"] == "no_band"


def test_the_second_access_point_fits_on_another_channel(storage, band):
    base_with_station(storage)
    silo(storage, band["silo"], units=2)
    silo(storage, band["second"], units=2)
    storage.do("tune", *band["silo"], "1", "1")
    storage.do("tune", band["second"][0], band["second"][1], "1", "2")
    storage.settle(10)

    station = storage.query("band", 3, 0)
    assert station["used"] == 2
    assert storage.query("capacity", 0, 0)["total"] == 40 + 4 * 40


def test_a_silo_beyond_the_band_range_is_refused(storage, band):
    """The only test that places anything genuinely far away, and it cleans up after itself: the fixture clears a
    radius around spawn, and this is outside it."""
    base_with_station(storage)
    storage.place("accesspoint", *band["far"])
    storage.settle(5)

    try:
        reply = storage.call("tune", *band["far"], "1", "1")
        assert not reply.ok, f"{band['far'][0]} tiles is inside a {band['range']}-tile band"
        assert "toofar" in reply.describe()
    finally:
        storage.break_at(*band["far"])
        storage.settle(2)


def test_a_channel_above_the_tier_is_refused(storage, band):
    """A Demonic band offers four channels, and the fifth is not a channel that exists rather than one that is busy."""
    base_with_station(storage)
    silo(storage, band["silo"])

    reply = storage.call("tune", *band["silo"], "1", str(band["channels"]["demonic"] + 1))
    assert not reply.ok
    assert "nochannel" in reply.describe()


def test_tuning_to_a_band_that_does_not_exist_is_refused(storage, band):
    base_with_station(storage)
    silo(storage, band["silo"])

    reply = storage.call("tune", *band["silo"], "7", "1")
    assert not reply.ok
    assert "noband" in reply.describe()


def test_retuning_frees_the_channel_it_left(storage, band):
    """Otherwise one device would hold two channels: it would appear twice in the station's list, and a walk would
    cross to it twice."""
    base_with_station(storage)
    silo(storage, band["silo"])
    storage.do("tune", *band["silo"], "1", "1")
    storage.do("tune", *band["silo"], "1", "3")
    storage.settle(5)

    assert storage.query("band", 3, 0)["used"] == 1
    assert storage.query("band", *band["silo"])["channel"] == 3


def test_breaking_an_access_point_frees_its_channel(storage, band):
    base_with_station(storage)
    silo(storage, band["silo"])
    storage.do("tune", *band["silo"], "1", "1")
    storage.settle(5)
    assert storage.query("band", 3, 0)["used"] == 1

    storage.break_at(*band["silo"])
    storage.settle(10)
    assert storage.query("band", 3, 0)["used"] == 0


# -- the ladder -----------------------------------------------------------------------------------


def test_upgrading_a_station_widens_the_band_and_keeps_its_silos(storage, band):
    """The reason the upgrade is in place rather than a rebuild: the band and every channel on it are keyed to the
    tile, so breaking the station to replace it would send the player round every silo to retune."""
    station = base_with_station(storage)
    silo(storage, band["silo"], units=2)
    storage.do("tune", *band["silo"], "1", "1")
    storage.settle(10)
    connected = storage.query("capacity", 0, 0)["total"]

    for item, amount in costs.materials("recipe.basestation.tungsten").items():
        storage.give(item, amount)

    storage.do("upgrade", *station)
    storage.settle(10)

    state = storage.query("band", *station)
    assert state["tier"] == "tungsten"
    assert state["channels"] == band["channels"]["tungsten"]
    assert state["used"] == 1, "the upgrade dropped the silo that was on the band"
    assert storage.query("capacity", 0, 0)["total"] == connected
    assert storage.query("band", *band["silo"])["state"] == "active"
