package de.danoeh.antennapod.playback.sonos;

import android.net.Uri;
import android.os.Bundle;
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
public class SonosDidlTest {

    private static MediaItem item(String title, String podcast, String artwork, String url, String mime) {
        Bundle extras = new Bundle();
        extras.putString(MediaItemAdapter.KEY_STREAM_URL, url);
        if (mime != null) {
            extras.putString(MediaItemAdapter.KEY_MIME_TYPE, mime);
        }
        MediaMetadata.Builder metadata = new MediaMetadata.Builder()
                .setTitle(title)
                .setArtist(podcast)
                .setSubtitle(podcast)
                .setExtras(extras);
        if (artwork != null) {
            metadata.setArtworkUri(Uri.parse(artwork));
        }
        return new MediaItem.Builder()
                .setMediaId("123")
                .setMediaMetadata(metadata.build())
                .build();
    }

    @Test
    public void testBuild() {
        String didl = SonosDidl.build(item("Ricky", "Darknet Diaries", "https://example.com/cover.jpg",
                "https://example.com/e.mp3", "audio/mpeg"));
        assertTrue(didl.startsWith("<DIDL-Lite "));
        assertTrue(didl.contains("<item id=\"antennapod:123\" parentID=\"-1\" restricted=\"true\">"));
        assertTrue(didl.contains("<dc:title>Ricky</dc:title>"));
        assertTrue(didl.contains("<dc:creator>Darknet Diaries</dc:creator>"));
        assertTrue(didl.contains("<upnp:albumArtURI>https://example.com/cover.jpg</upnp:albumArtURI>"));
        assertTrue(didl.contains("<upnp:class>object.item.audioItem.musicTrack</upnp:class>"));
        assertTrue(didl.contains("<res protocolInfo=\"http-get:*:audio/mpeg:*\">https://example.com/e.mp3</res>"));
        assertTrue(didl.endsWith("</item></DIDL-Lite>"));
    }

    @Test
    public void testBuildSkipsNonHttpArtwork() {
        String didl = SonosDidl.build(item("T", "P", "content://cover", "https://example.com/e.mp3", "audio/mpeg"));
        assertFalse(didl.contains("albumArtURI"));
    }

    @Test
    public void testBuildWithoutMimeType() {
        String didl = SonosDidl.build(item("T", "P", null, "https://example.com/e.mp3", null));
        assertTrue(didl.contains("protocolInfo=\"http-get:*:*:*\""));
        assertFalse(didl.contains("albumArtURI"));
    }

    @Test
    public void testBuildEscapesSpecialCharacters() {
        String didl = SonosDidl.build(item("Q&A <live> \"special\"", "Tom's & Jerry's", null,
                "https://example.com/e.mp3?a=1&b=2", "audio/mpeg"));
        assertTrue(didl.contains("<dc:title>Q&amp;A &lt;live&gt; &quot;special&quot;</dc:title>"));
        assertTrue(didl.contains("<dc:creator>Tom&apos;s &amp; Jerry&apos;s</dc:creator>"));
        assertTrue(didl.contains(">https://example.com/e.mp3?a=1&amp;b=2</res>"));
        String envelopeArgument = SonosSoapClient.escapeXml(didl);
        assertTrue(envelopeArgument.contains("Q&amp;amp;A &amp;lt;live&amp;gt;"));
    }

    @Test
    public void testRoundTrip() {
        String didl = SonosDidl.build(item("Q&A", "P", null, "https://example.com/e.mp3", "audio/mpeg"));
        assertEquals(Long.valueOf(123), SonosDidl.parseMediaId(didl));
    }

    @Test
    public void testParseMediaIdForeign() {
        assertNull(SonosDidl.parseMediaId(null));
        assertNull(SonosDidl.parseMediaId(""));
        assertNull(SonosDidl.parseMediaId("NOT_IMPLEMENTED"));
        assertNull(SonosDidl.parseMediaId("<DIDL-Lite><item id=\"spotify\"/></DIDL-Lite>"));
        assertNull(SonosDidl.parseMediaId("<DIDL-Lite><item id=\"antennapod:abc\"/></DIDL-Lite>"));
        assertNull(SonosDidl.parseMediaId("<DIDL-Lite><item id=\"-1\"/></DIDL-Lite>"));
    }
}
