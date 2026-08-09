#!/usr/bin/env bash
# Runs a scenario file against a headless Necesse server and reports pass/fail.
#
# A scenario is a plain list of server console commands, one per line. Blank lines and
# lines starting with # are ignored. Because every line is just a console command, any
# prefix of a scenario can be pasted into an interactive server to debug a failure.
#
# Usage: tools/run_scenario.sh <scenario-file> [world-name]
#
# Exit status is 0 only when the server started, every line was sent, and no line
# reported a failure. Assertion failures are recognised by the "FAIL" marker that the
# mod's own expect commands print.
set -uo pipefail

SCENARIO="${1:-}"
WORLD="${2:-arcane_harness}"

if [[ -z "$SCENARIO" || ! -f "$SCENARIO" ]]; then
   echo "usage: $0 <scenario-file> [world-name]" >&2
   exit 2
fi

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
NAME="$(basename "$SCENARIO" .txt)"
LOG="$OUT_DIR/$NAME.log"
: > "$LOG"

# Scenarios must start from a known world or they are not repeatable: objects placed by an
# earlier run would still be there. Only ever deletes a world whose name marks it as
# harness-owned, so a world used for manual testing can never be destroyed by a typo.
WORLD_FILE="$HOME/.config/Necesse/saves/worlds/$WORLD.zip"
if [[ "$WORLD" == arcane_harness* ]]; then
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

MARK_START="$(wc -l < "$LOG")"

while IFS= read -r line || [[ -n "$line" ]]; do
   line="${line%%$'\r'}"
   [[ -z "${line// }" ]] && continue
   [[ "${line#\#}" != "$line" ]] && continue
   printf '%s\n' "$line" >&3
   # ServerScanThread reads and handles lines sequentially, so ordering needs no delay.
   # This only spaces the log out enough to stay readable.
   sleep 0.05
done < "$SCENARIO"

printf 'stop\n' >&3
wait "$SERVER_PID" 2>/dev/null
SERVER_PID=

RESULTS="$(tail -n "+$MARK_START" "$LOG")"
FAILURES="$(printf '%s\n' "$RESULTS" | grep -cE "\bFAIL\b" || true)"
PASSES="$(printf '%s\n' "$RESULTS" | grep -cE "\bPASS\b" || true)"

printf '%s\n' "$RESULTS" | grep -E "\b(PASS|FAIL)\b" || echo "(no assertions reported)"
echo "--- $NAME: $PASSES passed, $FAILURES failed  (full log: $LOG)"
[[ "$FAILURES" -eq 0 ]] || exit 1
