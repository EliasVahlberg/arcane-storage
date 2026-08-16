package arcanestorage.network;

/**
 * A placeable object the network visibly meets.
 *
 * <p>Two things read this, and they must agree or the picture lies: a conduit's drawn shape joins to
 * its {@code NetworkNode} neighbours, and the network's walk spreads through the ones that also
 * {@linkplain NetworkConductor conduct}.
 *
 * <p>Implemented on the <i>object</i> rather than the object entity because tiles resolve to objects
 * cheaply ({@code Level.getObject(x, y)}) and a conductor need not have an entity at all: this mod's
 * conduit has none, which is why it has nothing to persist or keep in sync.
 *
 * <p><b>A terminal used to be a node and not a conductor</b>, on the reasoning that the network meets a terminal
 * without passing through it, so one terminal could not bridge two separate groups of units. That reasoning does not
 * survive contact with the terminal's own behaviour: a terminal's walk starts at its tile and expands outward, so it
 * already aggregated every group touching it. The groups were joined for the purpose of showing a combined inventory
 * and separate for every other purpose, which is not a rule so much as a disagreement -- and it was visible in play as
 * a base station on a conduit run to the left of a terminal being unable to see a transceiver on a run to the right.
 * Terminals conduct now, and this interface is what remains for anything that is met but not passed through.
 */
public interface NetworkNode {
}
