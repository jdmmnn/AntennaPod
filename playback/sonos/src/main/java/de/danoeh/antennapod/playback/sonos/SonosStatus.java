package de.danoeh.antennapod.playback.sonos;

import androidx.annotation.Nullable;

class SonosStatus {
    static final int VOLUME_UNKNOWN = -1;

    enum TransportState {
        PLAYING,
        PAUSED_PLAYBACK,
        STOPPED,
        TRANSITIONING,
        NO_MEDIA_PRESENT
    }

    private final TransportState transportState;
    private final long positionMs;
    private final long durationMs;
    private final String trackUri;
    @Nullable
    private final Long mediaId;
    private final int groupVolume;
    private final long timestamp;

    SonosStatus(TransportState transportState, long positionMs, long durationMs, String trackUri,
                @Nullable Long mediaId, int groupVolume, long timestamp) {
        this.transportState = transportState;
        this.positionMs = positionMs;
        this.durationMs = durationMs;
        this.trackUri = trackUri;
        this.mediaId = mediaId;
        this.groupVolume = groupVolume;
        this.timestamp = timestamp;
    }

    TransportState getTransportState() {
        return transportState;
    }

    long getPositionMs() {
        return positionMs;
    }

    long getDurationMs() {
        return durationMs;
    }

    String getTrackUri() {
        return trackUri;
    }

    @Nullable
    Long getMediaId() {
        return mediaId;
    }

    int getGroupVolume() {
        return groupVolume;
    }

    long getTimestamp() {
        return timestamp;
    }
}
