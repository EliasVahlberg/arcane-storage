#!/usr/bin/env bash
# Runs scenario files against a headless Necesse server and reports pass/fail per scenario.
#
# A scenario is a plain list of server console commands, one per line. Blank lines and
# lines starting with # are ignored. Because every line is just a console command, any
# prefix of a scenario can be pasted into an interactive server to debug a failure.
#
# Usage: tools/run_scenario.sh [--keep] <scenario-file> [more-scenario-files...]
#        HARNESS_WORLD=<name> tools/run_scenario.sh ...
#
# --keep reuses the existing world instead of starting fresh. That is what makes persistence
# testable: one boot writes state and saves on shutdown, the next boot reopens the same world
# and asserts the state survived. Only persistence scenarios should use it -- every other
# scenario wants a known starting world.
#
# All scenarios share ONE server boot, because booting is most of the wall clock: a
# scenario's own work is a fraction of a second, while JVM start, mod load, world load and
# the shutdown save cost several seconds each time. Scenarios are isolated by starting with
# 'arcanestorage reset', which removes every storage object on the level regardless of where
# a previous scenario put it -- 'clear' alone would not, since it only covers a radius while
# 'expect total' scans the whole level.
#
# Exit status is 0 only when the server started, every scenario ran, and none reported a
# failure. Assertion failures are recognised by the "FAIL" marker the mod's expect commands
# print.
set -uo pipefail

KEEP_WORLD=0
if [[ "${1:-}" == "--keep" ]]; then
   KEEP_WORLD=1
   shift
fi

if [[ $# -lt 1 ]]; then
   echo "usage: $0 [--keep] <scenario-file> [more-scenario-files...]" >&2
   exit 2
fi

for f in "$@"; do
   if [[ ! -f "$f" ]]; then
      echo "no such scenario file: $f" >&2
      exit 2
   fi
done

WORLD="${HARNESS_WORLD:-arcane_harness}"

MOD_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$MOD_DIR"

# The game directory is not autodetectable on this machine; gradle.properties holds it.
GAME_DIR="${NECESSE_GAME_DIR:-$(sed -n 's/^necesseGameDir=//p' gradle.properties | tail -1)}"
if [[ -z "$GAME_DIR" || ! -f "$GAME_DIR/Server.jar" ]]; then
   echo "Could not find Server.jar. Set NECESSE_GAME_DIR or necesseGameDir in gradle.properties." >&2
   exit 2
fi

# The bundled JRE, because the system JDK 17 here is headless-only and the crash reporter
# still builds a Swing window even when -nogui skips the server console.
JAVA="$GAME_DIR/jre/bin/java"
[[ -x "$JAVA" ]] || JAVA=java

OUT_DIR="$MOD_DIR/build/harness"
mkdir -p "$OUT_DIR"
if [[ $# -eq 1 ]]; then
   LOG="$OUT_DIR/$(basename "$1" .txt).log"
else
   LOG="$OUT_DIR/suite.log"
fi
: > "$LOG"

# Scenarios must start from a known world or they are not repeatable: objects placed by an
# earlier run would still be there. Only ever deletes a world whose name marks it as
# harness-owned, so a world used for manual testing can never be destroyed by a typo.
WORLD_FILE="$HOME/.config/Necesse/saves/worlds/$WORLD.zip"
if [[ "$KEEP_WORLD" -eq 1 ]]; then
   if [[ ! -f "$WORLD_FILE" ]]; then
      echo "FAIL  --keep needs an existing world at $WORLD_FILE; run the writing phase first" >&2
      exit 1
   fi
   echo "note: reusing world '$WORLD' as saved by the previous run."
elif [[ "$WORLD" == arcane_harness* ]]; then
   rm -f "$WORLD_FILE"
else
   echo "note: world '$WORLD' is not harness-owned (name must start with arcane_harness)," >&2
   echo "      so it is being reused as-is and results may not be repeatable." >&2
fi

# Commands must not be sent before the server exists. ServerScanThread starts during
# loading and a pipe delivers everything at once, so piping directly makes every command
# land while server is still null and silently do nothing. A fifo lets us wait for ready.
FIFO="$(mktemp -u "$OUT_DIR/stdin.XXXXXX")"
mkfifo "$FIFO"

cleanup() {
   [[ -n "${IN_FD_OPEN:-}" ]] && exec 3>&- 2>/dev/null
   [[ -n "${SERVER_PID:-}" ]] && kill "$SERVER_PID" 2>/dev/null
   rm -f "$FIFO"
}
trap cleanup EXIT

( cd "$GAME_DIR" && "$JAVA" -Darcanestorage.scenarios="$MOD_DIR/tests/scenarios" -jar Server.jar \
      -nogui -log_debug_prints -hiddencheats \
      -world "$WORLD" \
      -mod "$MOD_DIR/build/jar/" ) < "$FIFO" > "$LOG" 2>&1 &
SERVER_PID=$!

exec 3> "$FIFO"
IN_FD_OPEN=1

READY="Type help for list of commands."
for _ in $(seq 1 600); do
   grep -qF "$READY" "$LOG" && break
   if ! kill -0 "$SERVER_PID" 2>/dev/null; then
      echo "FAIL  server exited before becoming ready; see $LOG" >&2
      tail -20 "$LOG" >&2
      exit 1
   fi
   sleep 0.2
done

if ! grep -qF "$READY" "$LOG"; then
   echo "FAIL  server did not become ready within 120s; see $LOG" >&2
   exit 1
fi

RAN=()
UNRUN=()

for scenario in "$@"; do
   name="$(basename "$scenario" .txt)"

   # A crash or deadlock in one scenario leaves the rest unrun. Say which, rather than
   # reporting them as passing because no FAIL line was ever printed.
   if ! kill -0 "$SERVER_PID" 2>/dev/null; then
      UNRUN+=("$name")
      continue
   fi

   printf 'arcanestorage echo === BEGIN %s ===\n' "$name" >&3

   while IFS= read -r line || [[ -n "$line" ]]; do
      line="${line%%$'\r'}"
      [[ -z "${line// }" ]] && continue
      [[ "${line#\#}" != "$line" ]] && continue
      printf '%s\n' "$line" >&3
      # ServerScanThread reads and handles lines sequentially, so ordering needs no delay.
      # This only spaces the log out enough to stay readable.
      sleep 0.05
   done < "$scenario"

   printf 'arcanestorage echo === END %s ===\n' "$name" >&3
   sleep 0.15
   RAN+=("$name")
done

# Save explicitly, and wait for the game to say it finished, before stopping.
#
# Stopping alone is not enough. The shutdown save is best-effort: one run logged "Starting world
# save" and then "Server has stopped" with no completion line, and the next boot found an empty
# world -- which reads exactly like a persistence bug and is not one. That flake is worse than a
# plain failure, because it can also hide a real one.
#
# So: ask for a save while the server is definitely alive, confirm it completed, and only then
# stop. If the confirmation never arrives, say so loudly rather than letting the next boot
# report a mystery.
printf 'save\n' >&3
SAVED=0
for _ in $(seq 1 60); do
   if grep -aq "Completed world save" "$LOG"; then
      SAVED=1
      break
   fi
   sleep 0.5
done

printf 'stop\n' >&3
wait "$SERVER_PID" 2>/dev/null
SERVER_PID=

if [[ "$SAVED" -eq 0 ]]; then
   echo "FAIL  the server never confirmed a completed world save; a --keep run after this cannot be trusted" >&2
   exit 1
fi

PLAIN="$(sed 's/\x1b\[[0-9;]*m//g' "$LOG")"
TOTAL_FAIL=0

for name in "${RAN[@]}"; do
   section="$(printf '%s\n' "$PLAIN" | awk -v s="=== BEGIN $name ===" -v e="=== END $name ===" '
      index($0, s) { inside = 1; next } index($0, e) { inside = 0 } inside')"
   passes="$(printf '%s\n' "$section" | grep -cE "\bPASS\b" || true)"
   failures="$(printf '%s\n' "$section" | grep -cE "\bFAIL\b" || true)"
   TOTAL_FAIL=$((TOTAL_FAIL + failures))

   printf '%s\n' "$section" | grep -E "\b(PASS|FAIL)\b" || echo "  (no assertions reported)"
   echo "--- $name: $passes passed, $failures failed"
done

for name in "${UNRUN[@]:-}"; do
   [[ -z "$name" ]] && continue
   echo "--- $name: DID NOT RUN (the server was already gone)"
   TOTAL_FAIL=$((TOTAL_FAIL + 1))
done

echo "=== ${#RAN[@]} scenario(s) run, $TOTAL_FAIL failure(s)  (full log: $LOG)"
[[ "$TOTAL_FAIL" -eq 0 ]] || exit 1
