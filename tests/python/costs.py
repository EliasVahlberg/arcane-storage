"""The mod's cost data, read from the file the mod itself reads.

Java loads ``src/main/resources/recipes.properties`` at startup through ``CostTable``; these tests parse the same
file from the source tree. That is the point of the module. The unit materials used to be written in an enum and
copied into a dict at the top of ``test_unit_upgrade.py``, plus a table in the roadmap, and a test asserting a
number it also supplied proves only that someone typed it twice consistently -- until they do not, which is what
happened: the code and the roadmap disagreed for two commits with the suite green throughout.

So no cost is written here. A test that needs to hand over exactly enough demonic bars asks this module how many
that is, and the same file answers Java. Rebalancing is then a one-line edit to the data with the tests following
automatically -- which is the behaviour wanted, since a test that has to be edited to match a deliberate balance
change is a test nobody trusts.

The parser is deliberately small and strict. A malformed line raises rather than being skipped, matching
``CostTable``, because the failure being guarded against is a cost silently reading as nothing.
"""

from __future__ import annotations

from pathlib import Path

#: The same file the jar ships at ``resources/recipes.properties``.
DATA_FILE = Path(__file__).resolve().parents[2] / "src" / "main" / "resources" / "recipes.properties"


def _load() -> tuple[dict[str, dict[str, int]], dict[str, int]]:
    if not DATA_FILE.exists():
        raise FileNotFoundError(f"the cost data file is missing: {DATA_FILE}")

    materials: dict[str, dict[str, int]] = {}
    counts: dict[str, int] = {}

    for number, raw in enumerate(DATA_FILE.read_text().splitlines(), start=1):
        line = raw.strip()
        if not line or line.startswith("#"):
            continue

        if "=" not in line:
            raise ValueError(f"{DATA_FILE}:{number} is neither a comment nor key=value: {line!r}")

        key, value = (part.strip() for part in line.split("=", 1))

        if key.endswith(".materials"):
            entry: dict[str, int] = {}
            for part in value.split(","):
                fields = part.split()
                if len(fields) != 2:
                    raise ValueError(f"{DATA_FILE}:{number} is not 'item amount': {part!r}")
                entry[fields[0]] = int(fields[1])
            materials[key[: -len(".materials")]] = entry
        elif key.endswith(".count"):
            counts[key[: -len(".count")]] = int(value)
        else:
            raise ValueError(f"{DATA_FILE}:{number} has a key nothing reads: {key!r}")

    return materials, counts


_MATERIALS, _COUNTS = _load()


def materials(key: str) -> dict[str, int]:
    """``{item id: amount}`` for a key such as ``tier.demonic`` or ``recipe.terminal``."""
    if key not in _MATERIALS:
        raise KeyError(f"{key!r} is not in {DATA_FILE.name}; known: {sorted(_MATERIALS)}")
    return dict(_MATERIALS[key])


def count(key: str) -> int:
    """How many the recipe yields."""
    return _COUNTS.get(key, 1)


def tier_cost(tier: str) -> dict[str, int]:
    """The in-place upgrade cost of a rung, named as the tests name tiers: ``demonic``, ``tungsten``, ``fallen``."""
    return materials(f"tier.{tier}")
