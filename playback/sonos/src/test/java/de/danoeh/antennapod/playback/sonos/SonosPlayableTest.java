package de.danoeh.antennapod.playback.sonos;

import android.os.Bundle;
import androidx.annotation.Nullable;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MediaMetadata;
import de.danoeh.antennapod.playback.base.MediaItemAdapter;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

@RunWith(RobolectricTestRunner.class)
public class SonosPlayableTest {

    private static MediaItem item(@Nullable String streamUrl, @Nullable String mimeType) {
        Bundle extras = new Bundle();
        if (streamUrl != null) {
            extras.putString(MediaItemAdapter.KEY_STREAM_URL, streamUrl);
        }
        if (mimeType != null) {
            extras.putString(MediaItemAdapter.KEY_MIME_TYPE, mimeType);
        }
        return new MediaItem.Builder()
                .setMediaId("1")
                .setMediaMetadata(new MediaMetadata.Builder().setExtras(extras).build())
                .build();
    }

    @Test
    public void testRejectsMissingAndLocalUrls() {
        assertEquals(SonosPlayable.Reason.NO_URL, SonosPlayable.check(null));
        assertEquals(SonosPlayable.Reason.NO_URL, SonosPlayable.check(item(null, "audio/mpeg")));
        assertEquals(SonosPlayable.Reason.NO_URL, SonosPlayable.check(item("", "audio/mpeg")));
        assertEquals(SonosPlayable.Reason.NO_URL, SonosPlayable.check(item("content://media/external/audio/1", "audio/mpeg")));
        assertEquals(SonosPlayable.Reason.NO_URL, SonosPlayable.check(item("file:///sdcard/podcast.mp3", "audio/mpeg")));
    }

    @Test
    public void testRejectsVideo() {
        assertEquals(SonosPlayable.Reason.VIDEO, SonosPlayable.check(item("https://example.com/ep.mp4", "video/mp4")));
        assertEquals(SonosPlayable.Reason.VIDEO, SonosPlayable.check(item("https://example.com/ep.webm", "video/webm")));
    }

    @Test
    public void testRejectsUnsupportedAudioFormats() {
        assertEquals(SonosPlayable.Reason.FORMAT, SonosPlayable.check(item("https://example.com/ep.opus", "audio/opus")));
        assertEquals(SonosPlayable.Reason.FORMAT, SonosPlayable.check(item("https://example.com/ep.bin", null)));
        assertEquals(SonosPlayable.Reason.FORMAT, SonosPlayable.check(item("https://example.com/ep.wav", "audio/wav")));
    }

    @Test
    public void testAcceptsSupportedAudioFormatsWithHttpOrHttpsUrl() {
        String[] supported = {
            "audio/mpeg",
            "audio/mp4",
            "audio/x-m4a",
            "audio/aac",
            "audio/flac",
            "audio/ogg",
            "audio/vorbis",
            "audio/x-ms-wma"
        };
        for (String mime : supported) {
            assertNull(mime, SonosPlayable.check(item("https://example.com/ep", mime)));
            assertNull(mime, SonosPlayable.check(item("http://example.com/ep", mime)));
        }
    }

    @Test
    public void testRouteDialogFactoryHelpers() {
        assertTrue(SonosRouteDialogFactory.isLastSonosRoute("sonos:RINCON_1:2", "RINCON_1:2"));
        assertTrue(SonosRouteDialogFactory.isLastSonosRoute("pkg:sonos:RINCON_1:2", "RINCON_1:2"));
        assertFalse(SonosRouteDialogFactory.isLastSonosRoute("sonos:RINCON_2:3", "RINCON_1:2"));
        assertFalse(SonosRouteDialogFactory.isLastSonosRoute("sonos:RINCON_1:2", ""));
        assertTrue(SonosRouteDialogFactory.shouldShowNoDevicesHint(true, false));
        assertFalse(SonosRouteDialogFactory.shouldShowNoDevicesHint(true, true));
        assertFalse(SonosRouteDialogFactory.shouldShowNoDevicesHint(false, false));
    }
}
