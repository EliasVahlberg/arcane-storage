package arcanestorage.network;

/**
 * A placeable object the network visibly meets.
 *
 * <p>Two things read this, and they must agree or the picture lies: a conduit's drawn shape joins to
 * its {@code NetworkNode} neighbours, and the network's walk spreads through the ones that also
 * {@linkplain NetworkConductor conduct}. A terminal is a node and not a conductor — the network meets
 * it, and does not pass through it — which is what stops one terminal bridging two separate groups of
 * units.
 *
 * <p>Implemented on the <i>object</i> rather than the object entity because tiles resolve to objects
 * cheaply ({@code Level.getObject(x, y)}) and a conductor need not have an entity at all: this mod's
 * conduit has none, which is why it has nothing to persist or keep in sync.
 */
public interface NetworkNode {
}
