"""Build the Arcane Storage QA world: a handful of areas, placed and read back, saved as a zip.

`make qa-world`. Boots a headless server on a dedicated world, clears and floors a pad near spawn,
places each area from the table below, then READS EVERYTHING BACK — the network size at each
terminal, the items in each unit, and the paired terminal in the synthetic player's bag — and fails
loudly on any mismatch. A build that cannot read its own work back has not built anything.

**Offsets are spawn-relative.** Every verb — `place`, `fill`, `pair`, `query` — takes offsets from
the world's real spawn, which this script reads at boot (the harness's `TestContext.tileX(dx)` is
`spawn.x + dx`). The player spawns empty-handed on the spawn tile, which is left clear, so the first
area is the one nearest spawn.

**Player-coupled parts are manual.** The synthetic player the harness spawns is not the person who
later opens the world, so nothing this script puts in its bag survives for a visitor. The parts that
need a real character — pairing a terminal, holding a forge — are written as manual steps in
`qa-world/INDEX.md` rather than faked here. What the script *does* pair is its own synthetic player,
so the read-back proves the network is pairable, not that a visitor's bag is pre-filled.

**Settlement is absent on purpose.** A settlement needs a flag, a formed settlement and a chest
assigned to settlement storage, and the harness has no verb for any of the three; adding them is more
than a QA world's worth of harness. The settlement half of the Build Menu check is listed in the
index as a manual follow-up rather than built.

The world is saved (save_on_stop) so the engine writes `<appdata>/saves/worlds/<world>.zip`, which
`make qa-world-install` then copies.
"""

from __future__ import annotations

import os
import sys
import zipfile
from pathlib import Path

from necesse_harness import Harness, HarnessServer, ServerConfig

MOD_DIR = Path(__file__).resolve().parents[2]
#: The build world's name. Contains 'harness' because the harness refuses to delete a world whose
#: name does not; the installed copy is renamed to a clean name by `make qa-world-install`.
WORLD = "arcane_qa_harness_world"

#: Floor and border tiles for every area. Vanilla, so no mod content is needed just to stand on.
FLOOR = "stonefloor"
BORDER = "stonebrickfloor"

#: The placeables the Build Menu areas use, and why each is here (the "generic over named" table):
#:   stone      — a raw tile: one stone to place (the "plain stack").
#:   stonewall  — one ingredient, five of it, crafted at a Workstation.
#:   torch      — two ingredients (any log + any sapling), four to a craft.
#: The over-stack count rides on stone (300 of it, above its 250 stack), so the summary's
#: count-above-a-stack path is the one a visitor sees.
BUILD_MENU_MATERIALS = {
    "stone": 300,
    "oaklog": 20,
    "oaksapling": 20,
}

#: What a terminal/transceiver network holds, as (item, count) per area, keyed by area name.
#: Area 2 (the unpaired control) holds the same materials so the only difference is the pairing.
AREA_MATERIALS = {
    "build_menu": BUILD_MENU_MATERIALS,
    "unpaired": BUILD_MENU_MATERIALS,
}

#: The variety the scroll area holds, so its list is long enough to scroll without a hand-built
#: network. Vanilla items, one table on purpose: the harness has no verb to enumerate the item
#: registry, so the names are written here rather than derived. Chosen to be ordinary building
#: material — ore, wood, stone, seeds and food — that any world has.
SCROLL_ITEMS = [
    "stone", "sandtile", "dirttile", "clay", "ironore", "goldore", "copperore", "tungstenore",
    "glacialore", "myceliumore", "ancientfossilore", "oaklog", "birchlog", "pinelog", "sprucelog",
    "maplelog", "deadwoodlog", "dryadlog", "oaksapling", "birchsapling", "pinesapling", "sprucesapling",
    "maplesapling", "ironbar", "goldbar", "copperbar", "tungstenbar", "glacialbar", "myceliumbar",
    "sandstone", "wheat", "carrot", "potato", "tomato", "cabbage", "candle", "torch", "obsidian",
    "strawberry", "blueberry", "apple", "carp", "bone", "granite", "basalt",
]


def areas() -> list[dict]:
    """One entry per area: its name, the objects to place, and where, all spawn-relative.

    Each area is a horizontal strip a few tiles long, separated by a gap so no two networks touch.
    The spawn tile (0,0) is left clear; the first area's transceiver stands one tile east of it.
    """
    return [
        dict(name="build_menu", objects=[
            ("transceiver", 1, 0), ("unit", 2, 0), ("unit", 3, 0),
        ], pair=(1, 0)),
        dict(name="unpaired", objects=[
            ("transceiver", 1, 5), ("unit", 2, 5), ("unit", 3, 5),
        ], pair=None),
        dict(name="scroll", objects=[
            ("terminal", 1, 10),
            ("unit", 2, 10), ("unit", 3, 10), ("unit", 4, 10),
            ("unit", 2, 11), ("unit", 3, 11), ("unit", 4, 11),
            ("importbus", 5, 10), ("storagebox", 6, 10),
        ], pair=None),
        dict(name="station_refusal", objects=[
            ("terminal", 1, 15), ("stationunit", 2, 15),
        ], pair=None),
    ]


def place_all(harness: Harness) -> list[str]:
    """Places every area and fills its units. Returns a list of failure lines (empty on success)."""
    failures: list[str] = []

    for area in areas():
        for obj, dx, dy in area["objects"]:
            try:
                harness.place(obj, dx, dy)
            except Exception as e:  # place raises HarnessError on failure
                failures.append(f"{area['name']}: place {obj} at {dx},{dy}: {e}")

        if area["name"] in ("build_menu", "unpaired"):
            _fill_split(harness, area, AREA_MATERIALS[area["name"]], failures)
        elif area["name"] == "scroll":
            _fill_variety(harness, area, failures)

    return failures


def _fill_split(harness: Harness, area: dict, materials: dict, failures: list[str]) -> None:
    """Splits one material set across an area's units, so no unit overflows its slots."""
    units = [o for o in area["objects"] if o[0] == "unit"]
    for i, (_, dx, dy) in enumerate(units):
        for item, count in materials.items():
            share = count // len(units) + (1 if i < count % len(units) else 0)
            if share > 0:
                try:
                    harness.fill(dx, dy, item, share)
                except Exception as e:
                    failures.append(f"{area['name']}: fill {item} at {dx},{dy}: {e}")


def _fill_variety(harness: Harness, area: dict, failures: list[str]) -> None:
    """Spreads the variety list round-robin across the units, so the list scrolls."""
    units = [o for o in area["objects"] if o[0] == "unit"]
    if not units:
        failures.append(f"{area['name']}: no units to fill")
        return

    for i, item in enumerate(SCROLL_ITEMS):
        _, dx, dy = units[i % len(units)]
        try:
            harness.fill(dx, dy, item, 5)
        except Exception as e:
            failures.append(f"{area['name']}: fill {item} at {dx},{dy}: {e}")


def read_back(harness: Harness) -> list[str]:
    """Reads every area back and reports anything that does not match what was placed."""
    failures: list[str] = []

    # The build-menu network: two units, and every material summed across them.
    units = harness.query("units", 1, 0)["units"]
    if units != 2:
        failures.append(f"build_menu: expected 2 units at the transceiver, found {units}")

    for item, count in BUILD_MENU_MATERIALS.items():
        got = harness.query("item", 1, 0, item)["count"]
        if got != count:
            failures.append(f"build_menu: expected {count} {item}, found {got}")

    # The unpaired control holds the same materials, on its own separate network.
    units = harness.query("units", 1, 5)["units"]
    if units != 2:
        failures.append(f"unpaired: expected 2 units, found {units}")
    for item, count in BUILD_MENU_MATERIALS.items():
        got = harness.query("item", 1, 5, item)["count"]
        if got != count:
            failures.append(f"unpaired: expected {count} {item}, found {got}")

    # The two networks must not touch: the build-menu transceiver sees two units, never six.
    units = harness.query("units", 1, 0)["units"]
    if units != 2:
        failures.append(f"build_menu: networks bled together ({units} units, wanted 2)")

    # The scroll network: six units behind one terminal, filled with enough variety to scroll.
    units = harness.query("units", 1, 10)["units"]
    if units != 6:
        failures.append(f"scroll: expected 6 units, found {units}")
    scroll_capacity = harness.query("capacity", 1, 10)
    if scroll_capacity.get("used", 0) < len(SCROLL_ITEMS):
        failures.append(f"scroll: expected {len(SCROLL_ITEMS)} distinct items, found {scroll_capacity.get('used')}")

    # The station area: one terminal and one station unit.
    units = harness.query("units", 1, 15)["units"]
    if units != 0:
        failures.append(f"station_refusal: the terminal should see no storage units, found {units}")

    # Pair the synthetic player to the build-menu transceiver and prove the network resolves.
    try:
        harness.do("pair", 1, 0)
    except Exception as e:
        failures.append(f"build_menu: pairing failed: {e}")
        return failures
    binding = harness.query("binding")
    if not binding.get("paired"):
        failures.append("build_menu: the paired terminal did not resolve after pairing")

    return failures


#: What the finished world must be set to, so the checks run in peace. Creative lets the tester
#: spawn the player-coupled items (a terminal, a forge) that the build cannot place in a visitor's
#: bag; no mobs and no hunger keep the walk between areas uninterrupted.
WORLD_SETTINGS = {
    "creativeMode": "true",
    "disableMobSpawns": "true",
    "raidFrequency": "NEVER",
    "playerHunger": "false",
    "deathPenalty": "NONE",
}


def write_world_settings(zip_path: Path) -> dict[str, str]:
    """Patches the saved world's settings in place, then reads them back to prove it.

    Turning creative on through the game forces a backup copy that throws if one exists, so the
    file is the clean route. The read-back is the point: a setting only the writer believes in is
    not a setting.
    """
    with zipfile.ZipFile(zip_path) as source:
        entries = {name: source.read(name) for name in source.namelist()}

    settings_name = next((name for name in entries if name.endswith("worldSettings.cfg")), None)
    if settings_name is None:
        raise SystemExit(f"no worldSettings.cfg inside {zip_path}")

    config = entries[settings_name].decode("utf-8")
    wanted = dict(WORLD_SETTINGS)
    patched: list[str] = []
    for line in config.splitlines():
        key = line.split("=")[0].strip() if "=" in line else ""
        if key in wanted:
            line = f"{key} = {wanted.pop(key)}"
        patched.append(line)
    patched.extend(f"{key} = {value}" for key, value in wanted.items())
    entries[settings_name] = ("\n".join(patched) + "\n").encode("utf-8")

    with zipfile.ZipFile(zip_path, "w", zipfile.ZIP_DEFLATED) as target:
        for name, data in entries.items():
            target.writestr(name, data)

    with zipfile.ZipFile(zip_path) as written:
        text = written.read(settings_name).decode("utf-8")
    got = {}
    for line in text.splitlines():
        if "=" in line:
            key, _, value = line.partition("=")
            if key.strip() in WORLD_SETTINGS:
                got[key.strip()] = value.strip()
    return got


def world_zip() -> Path:
    """Where the harness keeps the world it just saved."""
    appdata = Path(os.environ.get("NECESSE_APPDATA", MOD_DIR / "build/necesse-appdata"))
    return appdata / "saves" / "worlds" / f"{WORLD}.zip"


def main() -> int:
    config = ServerConfig()
    config.world = WORLD
    config.mod_under_test = MOD_DIR / "build/jar"
    config.save_on_stop = True

    server = HarnessServer(config)
    server.start()
    harness = Harness(server)
    harness.handshake()
    harness.set_manual_ticks(True)
    harness.spawn_player()

    try:
        level = harness.query("level")
        print(f"qa-world: spawn is {level['spawnx']},{level['spawny']} — every offset is from there")

        harness.do("clear", "30", FLOOR)

        failures = place_all(harness)
        failures += read_back(harness)

        if failures:
            print("qa-world: FAILED")
            for line in failures:
                print(f"  - {line}")
            return 1

        harness.set_manual_ticks(False)
        server.stop(save=config.save_on_stop)

        zipped = world_zip()
        if not zipped.exists():
            print(f"qa-world: FAILED — no saved world at {zipped}")
            return 1

        settings = write_world_settings(zipped)
        if settings != WORLD_SETTINGS:
            print(f"qa-world: FAILED — settings did not read back: {settings}")
            return 1

        placed = sum(len(a["objects"]) for a in areas())
        print(f"qa-world: OK — {len(areas())} areas, {placed} objects, all read back")
        print(f"qa-world: settings: {settings}")
        print(f"qa-world: world at {zipped}")
        return 0
    except Exception as e:
        print(f"qa-world: FAILED — {e}")
        return 1
    finally:
        if server.process is not None and server.process.poll() is None:
            server.stop(save=config.save_on_stop)


if __name__ == "__main__":
    sys.exit(main())
