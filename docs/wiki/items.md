# Items

Everything Arcane Storage adds. All of it appears under an **Arcane Storage** category in your inventory and
in crafting lists, so you can find it without scrolling through furniture.

Costs shown are the defaults. A server can change them in its config file.

## The network

### Storage Terminal

<img src="images/arcanestorageterminal.png" width="96" alt="Storage Terminal">

The screen you open. Shows everything on the network as one searchable list and crafts from all of it. One is
enough, though there is nothing stopping you putting a second one at the other end of the base.

80 [Any Log](https://necessewiki.com/Any_Log) and 20 [Iron Bar](https://necessewiki.com/Iron_Bar) at a
[Workstation](https://necessewiki.com/Workstation).

![The Storage tab of a small network](screenshots/simple_storage_system_storage_tab.png)

See [The terminal](terminal.md).

### Arcane Storage Unit

<img src="images/arcanestorageunit.png" width="96" alt="Storage Unit">

Where things are actually kept. 40 slots at the base tier, doubling with each rung. Add as many as you like.

Spoiling also halves with each rung: Demonic at half rate (a Lunchbox's own number), Tungsten at quarter (a
fueled Cooling Box's), Fallen at eighth -- stronger than any single vanilla effect, since it is the top of a
four-rung climb rather than a match for one specific object. No fuel needed, unlike a Cooling Box.

40 Any Log and 10 Iron Bar at a Workstation.

![A storage unit panel, with its upgrade and empty buttons](screenshots/storage_unit_upgrade_empty_ui.png)

See [Tiers](tiers.md).

### Arcane Station Unit

<img src="images/arcanestoragestationunit.png" width="96" alt="Station Unit">

Holds crafting stations so the terminal can use them. One station at the base tier, doubling with each rung.

40 Any Log and 10 Iron Bar at a Workstation.

![A station unit holding one workstation](screenshots/station_unit_one_workbench_ui.png)

### Arcane Conduit

<img src="images/arcanestorageconduit.png" width="96" alt="Arcane Conduit">

Joins everything together. Lay it along the floor from the terminal to your units. Anything a continuous line
of conduit reaches is on the same network.

![A conduit run from a terminal to a distant storage unit](screenshots/storage_conduit_simple_example.png)

Four for one Iron Bar at a Workstation.

## Logistics

### Import Bus

<img src="images/arcanestorageimportbus.png" width="96" alt="Import Bus">

Moves items out of an adjacent container and into the network.

<img src="screenshots/storage_network_import_bus_connected.png" alt="An active import bus beside a chest" width="330"> <img src="screenshots/import_bus_ui_default.png" alt="The import bus panel" width="270">

20 Any Log and 10 Iron Bar at a Workstation.

### Export Bus

<img src="images/arcanestorageexportbus.png" width="96" alt="Export Bus">

Moves items out of the network and into an adjacent container.

<img src="screenshots/storage_network_export_bus_connected.png" alt="An active export bus beside a chest" width="270"> <img src="screenshots/export_bus_default_ui.png" alt="The export bus panel" width="270">

20 Any Log and 10 Iron Bar at a Workstation.

See [Buses](buses.md) for rules, names and the Logistics tab.

## Reaching further

### Wireless Terminal

<img src="images/arcanestoragewirelessterminal.png" width="96" alt="Wireless Terminal">

Carried, not placed. Opens your network from a distance once paired with a transceiver.

![The storage screen opened from a wireless terminal](screenshots/wireless_terminal_storage_ui.png)

Three rungs starting at Demonic. 10 [Demonic Bar](https://necessewiki.com/Demonic_Bar) and 5
[Sapphire](https://necessewiki.com/Sapphire) at a
[Demonic Workstation](https://necessewiki.com/Demonic_Workstation) for the first.

### Wireless Transceiver

<img src="images/arcanestoragewirelesstransceiver.png" width="96" alt="Wireless Transceiver">

Placed on the network. What a Wireless Terminal connects to. Its tier decides how far away you can be. A Base
Station needs one of these on its network too.

![A transceiver panel offering its upgrade](screenshots/transceiver_upgrade_ui.png)

15 Demonic Bar and 10 Sapphire at a Demonic Workstation for the first rung.

### Arcane Access Point

<img src="images/arcanestorageaccesspoint.png" width="96" alt="Access Point">

Placed next to distant storage and tuned to a Base Station channel. That storage then behaves exactly as if
conduit reached it.

![A tuned access point](screenshots/access_point_ui_filled_in_and_tuned.png)

Only one version, because there is nothing a tier could buy it. 40 Any Log, 10 Iron Bar and 1 Sapphire at a
Workstation.

### Arcane Base Station

<img src="images/arcanestoragebasestation.png" width="96" alt="Base Station">

Placed on your main network. Offers channels for Access Points to tune to, four at the first rung and doubling
after that. Needs a Wireless Transceiver on the same network, and only one Base Station may run per network.

![A base station transmitting, with access points tuned to its channels](screenshots/base_station_turned_on.png)

15 Demonic Bar and 10 Sapphire at a Demonic Workstation for the first rung.

See [Reaching further](wireless.md).

## All tiers at a glance

| Item | Base | Demonic | Tungsten | Fallen |
|---|---|---|---|---|
| Storage Unit | 40 slots, normal spoil | 80, half spoil | 160, quarter spoil | 320, eighth spoil |
| Station Unit | 1 station | 2 | 4 | 8 |
| Wireless Transceiver | none | 120 tiles | whole island | any island |
| Base Station | none | 4 channels | 8 | 16 |
| Terminal, Conduit, Buses, Access Point | one version only | | | |
