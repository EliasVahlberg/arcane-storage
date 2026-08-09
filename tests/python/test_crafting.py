"""Crafting from the network — which turned out to be vanilla behaviour, not a feature to build.

The finding these tests exist to protect: `Container.addSlot` adds every slot's inventory to
`craftInventories`, and the terminal already has a slot per network slot, so the network is a
crafting source the moment the container is built. `applyCraftingAction` is defined on `Container`
rather than on a crafting station, and it consumes from `getCraftInventories()`. Nothing in this mod
implements ingredient consumption, and nothing should — that is where duplication bugs live.

What remains for Phase 4 is the interface and station requirements, not the mechanic.
"""

from __future__ import annotations


def test_a_recipe_is_crafted_from_the_network_with_nothing_on_the_player(terminal):
    """The premise of Phase 4, as one test.

    woodboat is 8 anylog and needs no station, so it also proves global ingredients resolve against
    network contents: the recipe asks for "any log" and the network answers with oak.
    """
    terminal.harness.fill(1, 0, "oaklog", 8)
    assert terminal.harness.held("oaklog") == 0, "the ingredients must come from the network"

    terminal.open()
    terminal.harness.do("craft", "woodboat")

    assert terminal.harness.held("woodboat") == 1
    assert terminal.count("oaklog") == 0, "the logs should have been consumed from the network"


def test_crafting_without_the_ingredients_fails_and_consumes_nothing(terminal):
    """The failure that matters: a partial craft that ate ingredients would be a duplication bug in
    reverse, and worse than refusing."""
    terminal.harness.fill(1, 0, "oaklog", 3)
    terminal.open()

    reply = terminal.harness.call("craft", "woodboat")
    assert reply.ok is False
    assert terminal.count("oaklog") == 3
    assert terminal.harness.held("woodboat") == 0


def test_crafting_conserves_material(terminal):
    """Eight logs in, one boat out, and nothing anywhere else.

    Counted across the network and the player, since crafting moves items between exactly those two
    and the interesting failure is a log surviving somewhere it should not.
    """
    terminal.harness.fill(1, 0, "oaklog", 16)
    terminal.open()

    terminal.harness.do("craft", "woodboat", "2")

    assert terminal.harness.held("woodboat") == 2
    assert terminal.count("oaklog") + terminal.harness.held("oaklog") == 0


def test_a_conduit_run_makes_a_distant_unit_a_crafting_source(storage):
    """"At any distance" means through the network, not within a radius, and this pins that down.

    Vanilla's craft-from-containers works by proximity -- ±9 tiles at a Workstation, ±5 at a
    Carpenters Bench -- so a test that merely placed a unit far away would fail, and correctly: an
    unconnected unit is not in the network. What removes the distance limit is the conduit run, and
    that is what this builds.
    """
    storage.place("terminal", 0, 0)
    for x in range(1, 13):
        storage.place("conduit", x, 0)

    for y in range(1, 9):
        storage.place("conduit", 12, y)

    storage.place("unit", 12, 9)
    storage.fill(12, 9, "oaklog", 8)

    assert storage.query("units", 0, 0)["units"] == 1, "the conduit run should have linked the unit"

    storage.open(0, 0)
    storage.do("craft", "woodboat")

    assert storage.held("woodboat") == 1
