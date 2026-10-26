package de.danoeh.antennapod.playback.sonos;

import android.content.Context;
import android.os.Bundle;
import androidx.annotation.Nullable;
import androidx.media3.common.MediaItem;
import de.danoeh.antennapod.playback.base.MediaItemAdapter;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

final class SonosPlayable {
    enum Reason {
        NO_URL,
        VIDEO,
        FORMAT
    }

    private static final Set<String> SUPPORTED_MIME_TYPES = new HashSet<>(Arrays.asList(
            "audio/mpeg",
            "audio/mp4",
            "audio/x-m4a",
            "audio/aac",
            "audio/flac",
            "audio/ogg",
            "audio/vorbis",
            "audio/x-ms-wma"
    ));

    private SonosPlayable() {
    }

    @Nullable
    static Reason check(@Nullable MediaItem item) {
        if (item == null || item.mediaMetadata.extras == null) {
            return Reason.NO_URL;
        }
        Bundle extras = item.mediaMetadata.extras;
        String streamUrl = extras.getString(MediaItemAdapter.KEY_STREAM_URL);
        if (streamUrl == null || (!streamUrl.startsWith("http://") && !streamUrl.startsWith("https://"))) {
            return Reason.NO_URL;
        }
        String mimeType = extras.getString(MediaItemAdapter.KEY_MIME_TYPE);
        if (mimeType == null) {
            return Reason.FORMAT;
        }
        String normalized = mimeType.toLowerCase(Locale.ROOT).split(";")[0].trim();
        if (normalized.startsWith("video/")) {
            return Reason.VIDEO;
        }
        if (!SUPPORTED_MIME_TYPES.contains(normalized)) {
            return Reason.FORMAT;
        }
        return null;
    }

    static String getErrorMessage(Context context, Reason reason) {
        int reasonRes;
        if (reason == Reason.NO_URL) {
            reasonRes = R.string.sonos_reason_no_url;
        } else if (reason == Reason.VIDEO) {
            reasonRes = R.string.sonos_reason_video;
        } else {
            reasonRes = R.string.sonos_reason_format;
        }
        return context.getString(R.string.sonos_cannot_play, context.getString(reasonRes));
    }
}
