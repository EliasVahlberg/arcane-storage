#!/usr/bin/env bash
# Starts a headless server with inbound packet logging, and pre-places a storage network at
# the world spawn so there is something to interact with immediately.
#
# Purpose: capture which packets a real client sends for each interaction, so those code
# paths can be invoked deliberately from a command instead of being clicked by hand.
#
# Usage:
#   tools/capture_server.sh            # fresh capture world, network pre-placed
#   tools/capture_server.sh --keep     # reuse the existing capture world as-is
#
# Runs in the foreground. Ctrl-C stops the server (it saves on shutdown).
# While it runs, commands can also be sent from another terminal:
#   echo "arcanestorage report 1 0" > build/harness/capture.fifo
set -uo pipefail

KEEP=0
[[ "${1:-}" == "--keep" ]] && KEEP=1

MOD_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$MOD_DIR"

WORLD="arcane_capture"
GAME_DIR="${NECESSE_GAME_DIR:-$(sed -n 's/^necesseGameDir=//p' gradle.properties | tail -1)}"
if [[ -z "$GAME_DIR" || ! -f "$GAME_DIR/Server.jar" ]]; then
   echo "Could not find Server.jar. Set NECESSE_GAME_DIR or necesseGameDir in gradle.properties." >&2
   exit 2
fi

JAVA="$GAME_DIR/jre/bin/java"
[[ -x "$JAVA" ]] || JAVA=java

OUT_DIR="$MOD_DIR/build/harness"
mkdir -p "$OUT_DIR"
LOG="$OUT_DIR/capture.log"
FIFO="$OUT_DIR/capture.fifo"

if [[ "$KEEP" -eq 0 ]]; then
   rm -f "$HOME/.config/Necesse/saves/worlds/$WORLD.zip"
fi

rm -f "$FIFO"
mkfifo "$FIFO"
: > "$LOG"

cleanup() {
   [[ -n "${SETUP_PID:-}" ]] && kill "$SETUP_PID" 2>/dev/null
   rm -f "$FIFO"
}
trap cleanup EXIT

# Waits for the server to be ready, then places the network and prints what to do. Holds the
# fifo open afterwards so commands can still be sent to the running server.
(
   exec 3> "$FIFO"
   for _ in $(seq 1 600); do
      grep -qF "Type help for list of commands." "$LOG" && break
      sleep 0.2
   done

   if [[ "$KEEP" -eq 0 ]]; then
      # A block, so one unit is reachable by two paths. Terminal is one tile east of spawn
      # so it does not sit on the spawn tile itself.
      printf '%s\n' \
         "arcanestorage place terminal 1 0" \
         "arcanestorage place unit 2 0" \
         "arcanestorage place unit 2 1" \
         "arcanestorage place unit 1 1" \
         "arcanestorage fill 2 0 ironbar 40" \
         "arcanestorage fill 2 1 ironbar 40" \
         "arcanestorage fill 1 1 stone 60" \
         "arcanestorage report 1 0" >&3
      sleep 1
   fi

   cat <<'BANNER'

================ CAPTURE SERVER READY ================
Join it:  Main menu -> Multiplayer -> Join -> localhost
          (port 14159, no password)

A terminal is placed ONE TILE EAST of the world spawn, with three storage
units against it, already holding iron bars and stone.

Use in-game CHAT as a marker before each step, so the packet log can be
split up. Type the number in chat, then do the action:

  1   open the terminal
  2   plain left click an item entry   (fills the cursor)
  3   click that item into your inventory
  4   shift-left click an item entry   (quick transfer)
  5   shift-click an item in your inventory back into the terminal
  6   close the terminal
  7   reopen it, then break one of the units WHILE IT IS OPEN

Leave a second or two between steps. Then Ctrl-C here and tell me.
Log: build/harness/capture.log
======================================================

BANNER

   sleep 86400
) &
SETUP_PID=$!

( cd "$GAME_DIR" && "$JAVA" -Darcanestorage.packetlog=1 -Dnecesseheadlessharness.scenarios="$MOD_DIR/tests/scenes" -jar Server.jar \
      -nogui -log_debug_prints -hiddencheats \
      -world "$WORLD" \
      -mod "$MOD_DIR/build/jar/" ) < "$FIFO" 2>&1 | tee "$LOG"
