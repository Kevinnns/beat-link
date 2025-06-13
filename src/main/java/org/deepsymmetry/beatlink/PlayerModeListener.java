package org.deepsymmetry.beatlink;

import org.apiguardian.api.API;

/**
 * Listener interface for notifications when a player changes between
 * two-deck and four-deck modes, as determined by the peer count in
 * {@link DeviceAnnouncement} packets.
 */
@API(status = API.Status.EXPERIMENTAL)
public interface PlayerModeListener {
    /**
     * Called when a player reports a different number of peers, indicating
     * a switch between two-deck and four-deck modes.
     *
     * @param update describes the player and its current mode
     */
    @API(status = API.Status.EXPERIMENTAL)
    void playerModeChanged(PlayerModeUpdate update);
}
