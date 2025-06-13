package org.deepsymmetry.beatlink;

import org.apiguardian.api.API;

/**
 * Announces that a player has switched between two-deck and four-deck modes.
 */
@API(status = API.Status.EXPERIMENTAL)
public class PlayerModeUpdate {
    /** The player whose mode has changed. */
    @API(status = API.Status.EXPERIMENTAL)
    public final DeviceReference device;

    /** The number of peers this player now reports. */
    @API(status = API.Status.EXPERIMENTAL)
    public final int peerCount;

    PlayerModeUpdate(DeviceReference device, int peerCount) {
        this.device = device;
        this.peerCount = peerCount;
    }

    /**
     * Convenience accessor that returns {@code true} when in four-deck mode.
     */
    @API(status = API.Status.EXPERIMENTAL)
    public boolean isFourDeckMode() {
        return peerCount >= 4;
    }

    @Override
    public String toString() {
        return "PlayerModeUpdate[device:" + device + ", peers:" + peerCount + "]";
    }
}
