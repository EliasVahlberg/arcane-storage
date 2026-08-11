package arcanestorage.network;

/**
 * A placeable object membership passes through.
 *
 * <p>Storage Units conduct, so a solid block of them is one network; conduits conduct and hold
 * nothing, so reach can be routed around a base without paying for storage the player does not want.
 * Another mod's pipe joins the network by implementing this and nothing else.
 *
 * <p>Reach is bounded rather than free: the walk stops after
 * {@code StorageTerminalObjectEntity.MAX_CONDUITS} conducting tiles, because a long run of cheap
 * conductors would otherwise make every aggregation arbitrarily expensive while holding nothing.
 */
public interface NetworkConductor extends NetworkNode {
}
