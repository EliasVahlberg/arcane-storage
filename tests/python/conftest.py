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

from pathlib import Path

import pytest

from necesse_harness import Harness, ServerConfig

MOD_DIR = Path(__file__).resolve().parents[2]


@pytest.fixture(scope="session")
def harness_config() -> ServerConfig:
    """Point the harness at this mod.

    build/testjar rather than build/jar: the released jar deliberately excludes the harness-facing
    classes, because the mod loader defines every class in a jar eagerly and a reference to an
    absent optional mod is fatal rather than catchable. ``make testjar`` builds this one.
    """
    config = ServerConfig()
    config.mod_under_test = MOD_DIR / "build/testjar"
    config.world = "arcane_harness_py"
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

    def deposit_all(self) -> None:
        self.harness.do("depositall")

    def withdraw(self, item: str, amount: int, to_cursor: bool = False) -> None:
        self.harness.do("withdraw", item, str(amount), *(["cursor"] if to_cursor else []))


@pytest.fixture
def storage(harness: Harness) -> Harness:
    """The harness with every storage object removed, wherever it is on the level.

    ``clear`` covers a radius around spawn, which is not the same as covering the level: a unit left
    outside that radius by an earlier test would still be counted by ``total``. ``reset`` walks the
    object entities instead, so it finds them wherever they are.
    """
    harness.do("reset")
    return harness


@pytest.fixture
def terminal(storage: Harness) -> Terminal:
    """A terminal at spawn with one unit beside it: the smallest useful network."""
    storage.place("terminal", 0, 0)
    storage.place("unit", 1, 0)
    return Terminal(storage, 0, 0)
