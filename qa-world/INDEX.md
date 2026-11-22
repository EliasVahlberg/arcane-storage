# Arcane Storage QA world

A small, hand-built world so the window checks in `docs/QA_BACKLOG.md` take minutes instead of a
hand-built network. Built with `make qa-world`, installed with `make qa-world-install`.

You spawn at the first area, facing east. Every area is a horizontal strip near spawn; each area's
objects sit on the row named below, with the spawn tile `(0,0)` left clear. Materials are already
inside the storage units; your bag is empty on purpose, so the player-coupled steps are written out
under each area. The world is creative with mobs off, so the items a step says to "spawn" come from
the creative menu.

| Area | Row | Name | QA_BACKLOG |
|---|---|---|---|
| 1 | y=0 | Build Menu from the network | 0j (steps 1–4) |
| 2 | y=5 | Unpaired control | 0j |
| 3 | y=10 | Scroll list | 0i |
| 4 | y=15 | Station refusal | (new) |

The settlement half of 0j (step 5, "inside a settlement with a settlement chest nearby") is not built
here: a settlement needs a flag, a formed settlement and a chest assigned to settlement storage, none
of which the harness can place. It stays a manual check for a live settlement.

## Area 1 — Build Menu from the network (row y=0)

Objects: a Wireless Transceiver at `(1,0)` and two Storage Units at `(2,0)` and `(3,0)`, holding
stone (300, above one stack), oak logs and oak saplings. Nothing of these is in your bag.

1. Spawn a Wireless Terminal (creative menu) and pair it to the transceiver at `(1,0)` (hold it and
   right-click the transceiver), then carry it.
2. Open Build Menu and pick a placeable whose material lives only in the network — a stone tile, or
   a torch (log + sapling). **It must read as buildable, with the owned count matching the network.**
3. Place it. **The placement must succeed, and the network's remaining count must drop by the cost.**
4. Put the terminal away. **The item must grey out again**, then return when you pick the terminal
   back up.

Expected: buildable, correct owned count, count drops exactly, greyed without the terminal.

## Area 2 — Unpaired control (row y=5)

The same materials in another network (transceiver at `(1,5)`, units at `(2,5)` and `(3,5)`).

1. Spawn a Wireless Terminal and carry it, but do not pair it.
2. Open Build Menu and pick the same item as in area 1. **It must not be buildable** — the network is
   not offered while the terminal is unpaired.

Expected: greyed, and the server's placement never runs.

## Area 3 — Scroll list (row y=10)

A terminal at `(1,10)` over a six-unit network already filled with variety, an import bus at `(5,10)`
on a chest at `(6,10)`, and your own backpack to Deposit all.

1. Deposit all from your backpack, then open the terminal on the Storage tab.
2. The list is long enough to scroll; scroll down and note the position.
3. Change the network's contents while the terminal is open — leave the import bus running, or
   deposit more — without touching search or filters. **The list must keep its place** rather than
   snap to the top.

Expected: the list keeps its position across a content change.

## Area 4 — Station refusal (row y=15)

A terminal at `(1,15)` and a Station Unit at `(2,15)` with a free socket.

1. Spawn a forge, a cooking station and a lump of stone (creative menu) into your bag.
2. Open the terminal and try to install each into the socket.
3. **Each must be refused**, with the line that only a crafting station that works from any tile can
   be installed.

Expected: forge, cooking station and stone all refused, with the refusal line shown.
