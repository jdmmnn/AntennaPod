package de.danoeh.antennapod.playback.sonos;

import android.net.Uri;
import android.os.Bundle;
import androidx.annotation.Nullable;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MediaMetadata;
import de.danoeh.antennapod.playback.base.MediaItemAdapter;
import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import java.io.IOException;

final class SonosDidl {
    static final String ID_PREFIX = "antennapod:";

    private SonosDidl() {
    }

    static String build(MediaItem item) {
        MediaMetadata metadata = item.mediaMetadata;
        CharSequence podcast = metadata.artist != null ? metadata.artist : metadata.subtitle;
        Bundle extras = metadata.extras;
        String streamUrl = extras != null ? extras.getString(MediaItemAdapter.KEY_STREAM_URL) : null;
        String mimeType = extras != null ? extras.getString(MediaItemAdapter.KEY_MIME_TYPE) : null;
        Uri artwork = metadata.artworkUri;
        StringBuilder didl = new StringBuilder()
                .append("<DIDL-Lite xmlns:dc=\"http://purl.org/dc/elements/1.1/\" ")
                .append("xmlns:upnp=\"urn:schemas-upnp-org:metadata-1-0/upnp/\" ")
                .append("xmlns=\"urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/\">")
                .append("<item id=\"").append(SonosSoapClient.escapeXml(ID_PREFIX + item.mediaId))
                .append("\" parentID=\"-1\" restricted=\"true\">")
                .append("<dc:title>").append(escape(metadata.title)).append("</dc:title>")
                .append("<dc:creator>").append(escape(podcast)).append("</dc:creator>");
        if (artwork != null && ("http".equals(artwork.getScheme()) || "https".equals(artwork.getScheme()))) {
            didl.append("<upnp:albumArtURI>").append(SonosSoapClient.escapeXml(artwork.toString()))
                    .append("</upnp:albumArtURI>");
        }
        didl.append("<upnp:class>object.item.audioItem.musicTrack</upnp:class>")
                .append("<res protocolInfo=\"http-get:*:")
                .append(SonosSoapClient.escapeXml(mimeType != null ? mimeType : "*")).append(":*\">")
                .append(SonosSoapClient.escapeXml(streamUrl)).append("</res>")
                .append("</item></DIDL-Lite>");
        return didl.toString();
    }

    @Nullable
    static Long parseMediaId(@Nullable String didl) {
        if (didl == null || !didl.contains(ID_PREFIX)) {
            return null;
        }
        try {
            XmlPullParser parser = SonosXmlParser.newParser(didl);
            for (int event = parser.getEventType(); event != XmlPullParser.END_DOCUMENT; event = parser.next()) {
                if (event == XmlPullParser.START_TAG && "item".equals(parser.getName())) {
                    String id = parser.getAttributeValue(null, "id");
                    if (id == null || !id.startsWith(ID_PREFIX)) {
                        return null;
                    }
                    return Long.parseLong(id.substring(ID_PREFIX.length()));
                }
            }
        } catch (XmlPullParserException | IOException | NumberFormatException e) {
            return null;
        }
        return null;
    }

    private static String escape(@Nullable CharSequence value) {
        return SonosSoapClient.escapeXml(value != null ? value.toString() : "");
    }
}
