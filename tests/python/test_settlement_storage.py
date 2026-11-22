"""Settlers must not be able to deposit into, or haul out of, an object's hidden slots.

`OEInventory.getSettlementStorage()` defaults to the entity's whole inventory, which is the right answer
for a chest: assign the chest to settlement storage and settlers fill it and empty it. It is the wrong
answer for anything whose inventory is not a chest's worth of loose goods, and four of this mod's objects
are exactly that:

- **The Storage Terminal** holds the crafting stations installed before sockets moved onto Station Units.
  Nothing player-facing reads that inventory any more, so a settler depositing into it would put a real,
  filter-accepted, invisible item somewhere no player action could reach.
- **The Station Unit** holds installed benches, and a hauler carrying one off would dismantle the
  network's crafting capability while tidying up.
- **The Wireless Transceiver** and the **Base Station** carry device and network state. The transceiver
  inherits the terminal's override; the base station holds nothing today and is guarded anyway, because
  its slot count is a constant somebody may one day change.

Each of those overrides to `null`. The **Storage Unit** deliberately does not: it has a real inventory of
haulable items, and a settler filling it is not the same class of problem. That exception is why this file
asserts *both directions* -- a test that only checked "refuses" would pass just as happily if every object
refused, which would be a regression for the unit and a silent loss of the feature.

`query settlementstorage` asks the object entity, which is what vanilla's own assignment check asks. A
unit test cannot do this: these entities need a level, and a level cannot be constructed outside a running
game, which is why this lives here rather than in `src/test/java`.
"""

from __future__ import annotations

import pytest

#: Where each object sits in the fixture, and why its inventory must stay out of settlement storage.
HIDDEN = {
    "terminal": ((0, 0), "its inventory is installed stations, which no player-facing slot can reach"),
    "stationunit": ((0, 1), "a hauler must not carry off an installed bench"),
    "basestation": ((3, 0), "holds nothing today, guarded so that changing that cannot open a hole"),
    "transceiver": ((2, 0), "inherits the terminal's answer"),
}

#: The intended exception: a real inventory of haulable items, kept settler-accessible on purpose.
PASSIVE = ("unit", (1, 0))


@pytest.fixture()
def settlement_network(storage):
    """One of everything, all touching, so each object is a real placed entity on one network.

        y=0:   T  U  R  B
        y=1:   S

    T terminal, U storage unit, R wireless transceiver, B base station, S station unit. One row because
    the question is about each object in isolation; sharing a network means the transceiver and the base
    station are in the state a player would actually have them in.
    """
    storage.place("terminal", 0, 0)
    storage.place("unit", 1, 0)
    storage.place("transceiver", 2, 0)
    storage.place("basestation", 3, 0)
    storage.place("stationunit", 0, 1)
    return storage


@pytest.mark.parametrize("object_id", sorted(HIDDEN))
def test_a_hidden_inventory_is_not_offered_to_settlers(settlement_network, object_id):
    """Every one of these answers null, so a settler is told there is nothing here to haul."""
    offset, reason = HIDDEN[object_id]

    asked = settlement_network.query("settlementstorage", *offset)

    assert asked["oeinventory"] is True, (
        f"no storage object entity at {offset}. The placement failed, so the assertion below would be vacuous")
    assert asked["storage"] is False, f"{object_id} offers settlers its inventory: {reason}"


def test_a_storage_unit_stays_settler_storage(settlement_network):
    """The intended exception, asserted so that refusing everywhere cannot pass for a fix.

    A Storage Unit holds 40 to 320 slots of ordinary items and is meant to be settler-accessible: a
    settler filling it is the behaviour, and the bug in that neighbourhood is a stale view of what a
    settler put there, not the deposit itself.
    """
    object_id, offset = PASSIVE

    asked = settlement_network.query("settlementstorage", *offset)

    assert asked["oeinventory"] is True, f"the fixture's {object_id} should be a storage object entity"
    assert asked["storage"] is True, "a Storage Unit is storage a settler may use"
