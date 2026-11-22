"""Where the Build Menu jar comes from for the integration test, and whether it is there.

The jar is not shipped, not committed, and not on the compile classpath. The integration test runs
against the Workshop copy, and the whole module skips when it is absent. Copying the jar into this
checkout's own game-data directory is how a headless server -- which does not load Steam Workshop
subscriptions -- sees it as an ordinary file mod, without touching the player's real mod list.

The path is the Steam Workshop content folder for the game (app 1169040), item 3788439464. The
Debian/Ubuntu Steam layout is the default because that is where the game installs its workshop
content on this machine; override it with ``BUILD_MENU_JAR`` when it lives elsewhere.
"""

from __future__ import annotations

import os
import shutil
from pathlib import Path

#: Whether the jar was available when the session started. Read by the test module to skip.
AVAILABLE = False


def workshop_jar() -> Path:
    override = os.environ.get("BUILD_MENU_JAR")
    if override:
        return Path(override)
    return (
        Path.home()
        / ".steam/debian-installation/steamapps/workshop/content/1169040/3788439464/Build Menu.jar"
    )


def install(mods_dir: Path) -> bool:
    """Copies the Workshop jar into ``mods_dir`` if it exists, and reports whether it did.

    A stale copy from an earlier run is removed first, so "absent" really means the server boots
    without Build Menu rather than with a leftover jar. Build Menu also writes its own config to a
    ``BuildMenu`` directory beside the jar when it runs; that is cleared too, or the next boot
    without the jar would warn about an invalid mod jar it can no longer match.
    """
    global AVAILABLE
    mods_dir.mkdir(parents=True, exist_ok=True)
    target = mods_dir / "Build Menu.jar"
    if target.exists():
        target.unlink()
    config_dir = mods_dir / "BuildMenu"
    if config_dir.is_dir():
        shutil.rmtree(config_dir)
    source = workshop_jar()
    if source.exists():
        shutil.copy2(source, target)
        AVAILABLE = True
        return True
    return False
