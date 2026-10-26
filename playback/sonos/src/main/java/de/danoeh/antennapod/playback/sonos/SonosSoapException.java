package de.danoeh.antennapod.playback.sonos;

import java.io.IOException;

class SonosSoapException extends IOException {
    private final int errorCode;

    SonosSoapException(int errorCode) {
        super("UPnP error " + errorCode);
        this.errorCode = errorCode;
    }

    int getErrorCode() {
        return errorCode;
    }
}
