#!/usr/bin/env bash
# Runs this mod's scenarios through Necesse Headless Harness.
#
# The harness owns the runner: the watchdog, the per-line liveness check, the bounded stop and the
# crash-log surfacing are general problems, and a copy per mod is a copy per mod to keep correct.
# This only supplies what is specific to Arcane Storage -- where its jar is, where its scenarios
# are, and which world to use.
#
# Usage: tools/run_scenario.sh [--keep] <scenario-file> [more...]
#        HARNESS_DIR=/path/to/necesse-headless-harness tools/run_scenario.sh ...
#
# The harness must also be installed into ~/.config/Necesse/mods ('make install' in its repo),
# because the game accepts exactly one dev mod and this mod holds that slot. The harness's runner
# checks that for itself and says so.
set -uo pipefail

MOD_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

# A sibling checkout by default, which is how this workspace is laid out. Overridable because
# nothing should require a particular directory name two levels up.
HARNESS_DIR="${HARNESS_DIR:-$MOD_DIR/../necesse-headless-harness}"
RUNNER="$HARNESS_DIR/tools/run_scenario.sh"

if [[ ! -x "$RUNNER" ]]; then
   echo "Cannot find the harness runner at $RUNNER" >&2
   echo "Clone https://github.com/EliasVahlberg/necesse-headless-harness as a sibling of this repo," >&2
   echo "or point HARNESS_DIR at it." >&2
   exit 2
fi

# 'arcane_harness' rather than the harness's own default, so this mod's worlds are distinct from
# the harness's, and so the name still contains 'harness' -- which the runner requires before it
# will delete a world.
export HARNESS_WORLD="${HARNESS_WORLD:-arcane_harness}"
export MOD_UNDER_TEST="${MOD_UNDER_TEST:-$MOD_DIR/build/jar}"
export SCENARIO_DIR="${SCENARIO_DIR:-$MOD_DIR/tests/scenarios}"

exec "$RUNNER" "$@"
