package arcanestorage.network;

/**
 * A placeable object membership passes through.
 *
 * <p>Storage Units conduct, so a solid block of them is one network; conduits conduct and hold
 * nothing, so reach can be routed around a base without paying for storage the player does not want.
 * Terminals conduct too, so two conduit runs meeting at a terminal are one network rather than two that happen to
 * share a window. Another mod's pipe joins the network by implementing this and nothing else.
 *
 * <p>Whether a given tile conducts is asked in exactly one place, {@link UnitNetwork#conductorsOn}. Add an
 * implementation rather than a special case at a call site: a walk that answers this question differently from the
 * others sees a different network, and says nothing about it.
 *
 * <p>Reach is bounded rather than free: the walk stops after
 * {@code StorageTerminalObjectEntity.MAX_CONDUITS} conducting tiles, because a long run of cheap
 * conductors would otherwise make every aggregation arbitrarily expensive while holding nothing.
 */
public interface NetworkConductor extends NetworkNode {
}
