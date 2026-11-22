"""Arcane Storage's own fixtures on top of the harness's pytest plugin.

This mirrors the Java split exactly, which is a good sign the shape is right: the harness ships the
generic driver and knows nothing about storage networks, while this file supplies the vocabulary
that only means something here. In Java that is ``ArcaneStorageVerbs``; in Python it is this.

Note the two queries that deliberately shadow the harness's own. ``query item`` in the harness
counts one tile's inventory, which is correct for a chest and meaningless for a terminal, whose
number is whatever its network can see. Same for ``total``. The harness lets a registered
expectation replace a built-in for exactly this reason, and ``query`` follows ``expect`` in
honouring that.
"""

from __future__ import annotations

import os
import shutil
from pathlib import Path

import pytest

from necesse_harness import Harness, ServerConfig

import buildmenu

# CONTAMINATION -- why three tests carry @pytest.mark.realtime
#
# Those three pass on their own and fail in suite order under detached ticks. That combination means state
# surviving between tests, not a wrong expectation, so the marker is a holding position rather than a fix:
# it puts them back on the clock, where the contamination is hidden rather than absent.
#
# It is hidden because of what the old execution model did by accident. Every harness command was marshalled
# onto the server thread and waited for the next tick, so a command cost 50ms and a fixture's seven
# placements spanned seven ticks. Anything the engine defers to a tick -- entity removal in particular --
# was therefore always processed before the next test's first placement. Detaching game time removes that
# free settling, and whatever was relying on it surfaces.
#
# One instance of the class has already been found and fixed rather than marked, which is the template for
# the rest: ``BusObjectEntity.assignOrdinal`` counted removed devices when choosing a bus number, so a bus
# placed in the same tick as one was broken took the number after it and a lone import bus came out called
# "Import Bus #2". That was a real bug, reachable in play by breaking and rebuilding within a tick, and it
# was invisible for as long as the harness happened to leave a tick between every command.
#
# So each remaining mark is a lead, not a limitation. The suspicion is the same deferred-removal shape.

MOD_DIR = Path(__file__).resolve().parents[2]


def seed_appdata(appdata: Path) -> None:
    """Puts the harness (and, if present, Build Menu) into this checkout's own game-data directory.

    A headless server loads mods from ``<appdata>/mods`` and writes its mod list and saves under the
    same root, so pointing it at a per-checkout directory keeps a test run from reading or clobbering
    the player's real mods. The harness jar is copied from the installed one rather than shipped here;
    Build Menu's Workshop copy is picked up by :func:`buildmenu.install`.
    """
    mods = appdata / "mods"
    mods.mkdir(parents=True, exist_ok=True)
    installed = Path(os.environ.get("NECESSE_INSTALLED_APPDATA", Path.home() / ".config/Necesse")) / "mods"
    harness_jars = list(installed.glob("NecesseHeadlessHarness-*.jar"))
    if not harness_jars:
        raise RuntimeError(
            f"the harness is not installed in {installed}; run 'make install' in the harness repo")
    for jar in harness_jars:
        shutil.copy2(jar, mods / jar.name)
    buildmenu.install(mods)


@pytest.fixture(scope="session")
def harness_config() -> ServerConfig:
    """Point the harness at this mod.

    ``build/jar`` is the released jar, and deliberately so: the harness bridge ships inside it as
    ``harnessbridge/**.classdata`` resources, which the mod loader ignores and
    ``necesseheadlessharness.ModBridges`` defines at runtime. So these scenarios exercise the exact
    artifact players download, including the deferred-definition path itself.

    There used to be a second jar here because harness-facing classes could not ship; the cost was
    that nothing ever tested what shipped.
    """
    config = ServerConfig()
    config.mod_under_test = MOD_DIR / "build/jar"
    config.world = "arcane_harness_py"
    config.appdata = MOD_DIR / "build/necesse-appdata"
    config.work_dir = MOD_DIR / "build/harness-out"
    seed_appdata(config.appdata)
    return config


class Terminal:
    """A placed terminal, addressed by its offset from spawn.

    Exists so a test reads as a claim about the network rather than as a series of coordinates:
    ``terminal.capacity()`` instead of ``harness.query("capacity", 4, 0)``.
    """

    def __init__(self, harness: Harness, dx: int, dy: int) -> None:
        self.harness = harness
        self.dx = dx
        self.dy = dy

    def units(self) -> int:
        return self.harness.query("units", self.dx, self.dy)["units"]

    def capacity(self) -> tuple[int, int]:
        data = self.harness.query("capacity", self.dx, self.dy)
        return data["used"], data["total"]

    def count(self, item: str) -> int:
        """How much of an item the whole network holds, not one tile's worth."""
        return self.harness.query("item", self.dx, self.dy, item)["count"]

    def fits(self, item: str) -> bool:
        return self.harness.query("fits", self.dx, self.dy, item)["fits"]

    def open(self) -> None:
        self.harness.open(self.dx, self.dy)

    def users(self) -> int:
        """How many players the terminal believes are using it -- what other clients render from."""
        return self.harness.query("inuse", self.dx, self.dy)["users"]

    def deposit_all(self) -> None:
        self.harness.do("depositall")

    def withdraw(self, item: str, amount: int, to_cursor: bool = False) -> None:
        self.harness.do("withdraw", item, str(amount), *(["cursor"] if to_cursor else []))


@pytest.fixture
def automatic_unloading(harness: Harness):
    """Puts the engine's unload sweeps back for the duration of one test.

    The session suppresses them, and must: in manual mode the sweep's thirty-one seconds pass in no
    wall-clock time, so any test granting hundreds of ticks would have its world dismantled underneath it.
    But a test whose *subject* is the interaction between a region pin and that sweep needs the sweep to
    exist, and asking for it explicitly is the only way to say so.

    Before this fixture those tests passed by accident. The suppression was applied once at session start
    and a fresh JVM forgot it, so from the first restart onward the sweeps quietly came back -- and these
    tests, which run after the restarting ones, depended on that. Closing that gap in the harness broke the
    one test that needed a live sweep and made its sibling vacuous, which is how the accident surfaced.
    """
    harness.set_auto_unload(True)
    try:
        yield harness
    finally:
        harness.set_auto_unload(False)


@pytest.fixture
def storage(harness: Harness) -> Harness:
    """The harness with every storage object removed, wherever it is on the level.

    ``clear`` covers a radius around spawn, which is not the same as covering the level: a unit left
    outside that radius by an earlier test would still be counted by ``total``. ``reset`` walks the
    object entities instead, so it finds them wherever they are.
    """
    # Flush, then clear, then flush again -- and the order is the point rather than belt and braces.
    #
    # ``reset`` drops the network indexes and then removes the objects, but engine entity removal is
    # deferred to a tick. So ticking *after* reset lets the devices that have not gone yet rebuild the very
    # indexes reset just dropped, and the next test places its first unit into a world that still believes
    # the previous test's network exists. The leading settle drains whatever the previous test left pending
    # so that reset sees a quiet world; the trailing one lets the removals actually complete.
    #
    # None of this was needed while every command cost a tick, because the gaps between commands did it for
    # free. That is why it appears now rather than being a new fault.
    harness.settle(2)
    harness.do("reset")
    harness.settle(2)
    return harness


@pytest.fixture
def terminal(storage: Harness) -> Terminal:
    """A terminal at spawn, one storage unit, and one station socket: the smallest useful network.

        y=0:   T  U
        y=1:   S

    The Station Unit is part of the baseline because crafting is half of what a terminal does, and
    sockets stopped being free when they moved off the terminal onto their own block. A fixture without
    one can craft nothing that needs a bench, which is a real state a player reaches but a poor default
    for tests about anything else. It contributes no storage capacity, so counts of the network's units
    and slots are unaffected.
    """
    storage.place("terminal", 0, 0)
    storage.place("unit", 1, 0)
    storage.place("stationunit", 0, 1)
    return Terminal(storage, 0, 0)

@pytest.fixture
def two_buses(storage):
    """An import bus and an export bus on one network, both attached to the same chest.

        y=0:   T  U  I  C
        y=1:      U  c  E

    T terminal, U unit, I import bus, c conduit, E export bus, C chest. Both buses touch the chest, so both
    can move the same items, and both are on the network, so each sees what the other did. That shared
    container is what makes a cycle: whatever one bus does, the other can undo.
    """
    storage.place("terminal", 0, 0)
    storage.place("unit", 1, 0)
    storage.place("importbus", 2, 0)
    storage.place("storagebox", 3, 0)
    storage.place("unit", 1, 1)
    storage.place("conduit", 2, 1)
    storage.place("exportbus", 3, 1)
    return storage
