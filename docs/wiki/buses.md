# Buses

A bus sits next to an ordinary container and moves items between that container and the network. It does the
walking so you do not have to.

Both kinds cost 20 [Any Log](https://necessewiki.com/Any_Log) and 10
[Iron Bar](https://necessewiki.com/Iron_Bar) at a [Workstation](https://necessewiki.com/Workstation).

## Import Bus

<img src="images/arcanestorageimportbus.png" width="96" alt="Import Bus">

Takes items **out of** the container and puts them **into** the network.

![An import bus lit up beside a chest, joined to the terminal by conduit](screenshots/storage_network_import_bus_connected.png)

The usual first one goes next to the chest you empty your pockets into when you get home. Drop everything in
the chest, walk away, and it ends up sorted into your network.

## Export Bus

<img src="images/arcanestorageexportbus.png" width="96" alt="Export Bus">

Takes items **out of** the network and puts them **into** the container.

![An export bus lit up beside a chest](screenshots/storage_network_export_bus_connected.png)

Useful for keeping something topped up. An export bus set to wood, next to a chest by your building spot,
means that chest always has wood in it.

## Placing one

A bus needs two things. It must touch the network, through a conduit or by being next to a unit or the
terminal. And it must be directly next to the container it serves, in one of the four directions, not
diagonally.

A bus with nothing beside it goes dark, which is what a mistake looks like:

![The same bus dark and inactive because no container is next to it](screenshots/storage_network_import_bus_disabled.png)

The bus sprite shows which way it is facing once it has found a container. If it looks unattached, it has not
found one, and the usual reason is that the container is diagonal rather than beside it.

A container wider than one tile works from any of its sides.

## Rules

Click a bus to open it. Every bus starts with no rules, which means everything is allowed.

<img src="screenshots/import_bus_ui_default.png" alt="An import bus panel, freshly placed" width="330"> <img src="screenshots/export_bus_default_ui.png" alt="An export bus panel, freshly placed" width="330">

- **Item rules** let you list what may pass. An import bus with a rule for ore moves only ore and leaves the
  rest in the chest.
- **Category rules** work the same way but for a whole category at once.
- **A limit** on a rule is how much of that item the network keeps. An import bus with a limit of 200 on wood
  fills the network up to 200 and then stops. An export bus with a limit of 200 on wood sends out everything
  above 200, so the network is left holding 200 and the chest gets the rest.

### Counting in the chest instead

Under the number, **Count in** chooses where the number is counted. **Network** is the default and works as
described above. **Container** counts in the chest the bus is attached to instead:

- **Export bus, Container: keep the chest stocked.** With stone at 100 and sand at 100, the chest is filled
  to 100 of each and the network keeps the rest. Whenever you or a settler take some out, the bus tops it back
  up.
- **Import bus, Container: leave some behind.** With torches at 10, the bus takes every torch above 10 into
  the network and leaves 10 in the chest.

A few things worth knowing:

- Each bus still only moves one way. A stocked chest holding more than its number is left alone, and so is an
  import chest holding less.
- If the network runs short, a stocked chest gets what there is.
- An item ticked without a number is moved in full, in either mode.
- An import bus and an export bus on the same chest must agree. Both counting in the chest works when the
  export number is no higher than the import number. Mixing a chest-counting bus with a network-counting one
  on the same item does not settle, so the panel refuses it when you press Apply.
- Settlers who haul into or out of the same chest can work against a bus. The bus simply corrects the chest
  the next time it changes.

## Names

Every bus gets a number when you place it, so your first import bus is Import Bus 1. That number is yours for
good and does not change when you break another bus.

You can rename any bus to something you will recognise, like Farm or Smelter. The name shows up in the
Logistics tab, which is where a large base becomes readable instead of being a list of numbers.

## The Logistics tab

![The Logistics tab with a bus selected and its category rules showing](screenshots/logistics_tab_example.png)

Open the terminal and go to Logistics. Every bus is listed with its name, what it is attached to, and whether
it is working.

A bus that has a problem says so here. The common ones are having no container next to it, or having a rule
that no longer matches anything.
