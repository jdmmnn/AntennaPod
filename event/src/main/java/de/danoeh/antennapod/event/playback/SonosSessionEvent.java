package de.danoeh.antennapod.event.playback;

import androidx.annotation.Nullable;

public class SonosSessionEvent {
    @Nullable
    private final String groupName;

    public SonosSessionEvent(@Nullable String groupName) {
        this.groupName = groupName;
    }

    @Nullable
    public String getGroupName() {
        return groupName;
    }
}
