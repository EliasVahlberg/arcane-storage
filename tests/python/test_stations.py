"""Installed crafting stations — what the terminal may build, and what it may not.

The design, settled and not to be reopened: a bench is *installed into* the terminal, one per slot,
and the terminal never reaches out to benches placed nearby. These tests cover the half of that which
is enforcement rather than interface.

The thing they really protect is a consequence of how recipe IDs work. A recipe ID is an index into
the container's own recipe list and `applyCraftingAction` resolves it by index on both sides, so
registering only the installed stations' recipes would move every index whenever a bench was
installed. The container therefore registers *every* recipe in the game and refuses the unavailable
ones server-side. That makes the refusal load-bearing: without it, the terminal would craft anything.
"""

from __future__ import annotations

import pytest


def test_a_station_recipe_is_refused_with_no_station_installed(terminal):
    """storagebox is 8 logs at a Workstation. The logs are there; the Workstation is not."""
    terminal.harness.fill(1, 0, "oaklog", 8)
    terminal.open()

    reply = terminal.harness.call("craft", "storagebox")

    assert reply.ok is False, "the terminal has no Workstation installed"
    assert terminal.count("oaklog") == 8, "a refused craft must consume nothing"
    assert terminal.harness.held("storagebox") == 0


def test_installing_the_station_makes_its_recipes_craftable(terminal):
    """The same craft, after committing a bench to the terminal."""
    terminal.harness.fill(1, 0, "oaklog", 8)
    terminal.open()
    terminal.harness.do("install", "workstation")

    terminal.harness.do("craft", "storagebox")

    assert terminal.harness.held("storagebox") == 1
    assert terminal.count("oaklog") == 0, "the logs come from the network"


def test_an_installed_station_is_not_stored_and_does_not_consume_capacity(terminal):
    """Installing is not depositing.

    The station slots live on the terminal, not on a unit, so an installed bench must not appear in
    the network's contents and must not eat capacity.

    A third property is deliberately *not* asserted here: the container also drops the terminal's own
    inventory from its craft pool, so an installed bench cannot be consumed as a material. No vanilla
    recipe takes a bench as an ingredient, so there is nothing to craft that would prove it -- it is
    a guard against modded recipes and against `Container.addSlot`'s default, which adds every slot's
    inventory to the pool. Stated rather than tested, so the gap is visible.
    """
    terminal.harness.fill(1, 0, "oaklog", 8)
    terminal.open()
    used_before, _ = terminal.capacity()

    terminal.harness.do("install", "workstation")

    assert terminal.count("workstation") == 0, "an installed bench is not network contents"
    assert terminal.capacity()[0] == used_before, "and does not consume network capacity"


@pytest.mark.parametrize("not_a_station", ["stone", "ironpickaxe", "oaklog"])
def test_only_crafting_stations_can_be_installed(terminal, not_a_station):
    """The slots ask the item what object it places and whether that object is a station.

    Worth testing across three kinds because the check has three ways to be wrong: a plain material
    is not an object item at all, a pickaxe is an item with no object, and a log is a material that
    *does* place something -- but not a bench.
    """
    terminal.open()

    reply = terminal.harness.call("install", not_a_station)

    assert reply.ok is False, f"{not_a_station} is not a crafting station"


def test_hand_recipes_need_no_station(terminal):
    """A recipe with no station requirement is always available, which is also what keeps the
    crafting tab from being empty before the player installs their first bench."""
    terminal.harness.fill(1, 0, "oaklog", 8)
    terminal.open()

    terminal.harness.do("craft", "woodboat")

    assert terminal.harness.held("woodboat") == 1


def test_forge_installs_as_instant_recipes(terminal):
    """Installing a Forge unlocks FORGE techs: smelt from network ores with no fuel / auto-smelt."""
    terminal.harness.fill(1, 0, "ironore", 10)
    terminal.open()

    terminal.harness.do("install", "forge")
    terminal.harness.do("craft", "ironbar")

    assert terminal.harness.held("ironbar") == 1
    assert terminal.count("ironore") == 9


def test_forge_does_not_auto_smelt_stored_ore(terminal):
    """Ore sitting in storage stays ore until the player crafts — install alone must not process it."""
    terminal.harness.fill(1, 0, "ironore", 10)
    terminal.open()

    terminal.harness.do("install", "forge")
    # Processing forge would smelt over world ticks; settle so a regression cannot hide behind "no time passed".
    terminal.harness.settle(40)
    assert terminal.count("ironore") == 10
    assert terminal.count("ironbar") == 0


# station, ingredient, amount consumed per craft, result item
FOOD_STATION_RECIPES = [
    # Cooking station unlocks cooking + pot + roasting techs.
    ("cookingstation", "rawpork", 1, "roastedpork"),
    ("cookingpot", "flour", 2, "bread"),
    ("roastingstation", "rawpork", 1, "roastedpork"),
]


@pytest.mark.parametrize("station,ingredient,consumed,result", FOOD_STATION_RECIPES)
def test_food_stations_install_as_instant_recipes(terminal, station, ingredient, consumed, result):
    """Cooking / pot / roasting unlock food techs: craft without fuel OE or auto-cook."""
    terminal.harness.fill(1, 0, ingredient, 10)
    terminal.open()

    terminal.harness.do("install", station)
    terminal.harness.do("craft", result)

    assert terminal.harness.held(result) == 1
    assert terminal.count(ingredient) == 10 - consumed


@pytest.mark.parametrize("station,ingredient,_,result", FOOD_STATION_RECIPES)
def test_food_stations_do_not_auto_cook_stored_ingredients(terminal, station, ingredient, _, result):
    """Food left in storage stays raw until the player crafts — install alone must not process it."""
    terminal.harness.fill(1, 0, ingredient, 10)
    terminal.open()

    terminal.harness.do("install", station)
    terminal.harness.settle(40)
    assert terminal.count(ingredient) == 10
    assert terminal.count(result) == 0


def test_grain_mill_installs_as_instant_recipes(terminal):
    """Installing a Grain Mill unlocks GRAIN_MILL: flour from wheat with no processing wait."""
    terminal.harness.fill(1, 0, "wheat", 10)
    terminal.open()

    terminal.harness.do("install", "grainmill")
    terminal.harness.do("craft", "flour")

    assert terminal.harness.held("flour") == 1
    assert terminal.count("wheat") == 9


def test_grain_mill_does_not_auto_mill_stored_wheat(terminal):
    """Wheat in storage stays wheat until the player crafts — install alone must not process it."""
    terminal.harness.fill(1, 0, "wheat", 10)
    terminal.open()

    terminal.harness.do("install", "grainmill")
    terminal.harness.settle(40)
    assert terminal.count("wheat") == 10
    assert terminal.count("flour") == 0


#: Every vanilla station whose techs an installed item can answer for: `CraftingStationObject`
#: subclasses, taken from `RecipeTechRegistry`'s own itemStringIDs so the list cannot drift from the
#: game's — plus recipe-only exceptions (forge / fueled food stations / grain mill).
INSTALLABLE_STATIONS = [
    "workstation", "demonicworkstation", "tungstenworkstation", "fallenworkstation",
    "ironanvil", "demonicanvil", "tungstenanvil", "fallenanvil",
    "carpentersbench", "tungstencarpentersbench", "fallencarpentersbench",
    "alchemytable", "voidalchemytable", "caveglowalchemytable", "fallenalchemytable",
    "landscapingstation", "tungstenlandscapingstation", "fallenlandscapingstation",
    "transmutationstation",
    "forge",
    "cookingstation", "cookingpot", "roastingstation",
    "grainmill",
]

#: Stations that still need their tile (processing OE / settler workstation). Recipe-only exceptions
#: are listed above — compost bin and cheese press stay refused.
PLACEMENT_DEPENDENT_STATIONS = [
    "compostbin", "cheesepress",
]


@pytest.mark.parametrize("station", INSTALLABLE_STATIONS)
def test_every_stateless_vanilla_station_installs(terminal, station):
    """The rule is behavioural -- "does this station need to be somewhere?" -- so the value of this
    test is that it runs the rule over the whole vanilla set rather than over the two cases that
    prompted it. A rule that refuses a legitimate bench is as much a bug as one that admits a Forge."""
    terminal.open()

    terminal.harness.do("install", station)  # raises unless the terminal accepted it


@pytest.mark.parametrize("station", PLACEMENT_DEPENDENT_STATIONS)
def test_a_station_that_needs_its_tile_is_refused(terminal, station):
    """Fuel and processing time are enforced by the *container*, not by the object, so an installed one
    would craft for free. Asserted over every such station still refused in this release."""
    terminal.open()

    reply = terminal.harness.call("install", station)

    assert not reply.ok, f"{station} was installed, and would then craft with no fuel or no processing"
