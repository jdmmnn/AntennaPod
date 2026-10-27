package de.danoeh.antennapod.playback.sonos;

import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import androidx.annotation.Nullable;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MediaMetadata;
import de.danoeh.antennapod.playback.base.MediaItemAdapter;
import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import java.io.IOException;

final class SonosDidl {
    private static final String TAG = "SonosDidl";
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

    static MediaItem toMediaItem(String didl, String streamUrl, long mediaId) {
        Bundle extras = new Bundle();
        extras.putString(MediaItemAdapter.KEY_STREAM_URL, streamUrl);
        MediaMetadata.Builder metadata = new MediaMetadata.Builder()
                .setIsPlayable(true)
                .setIsBrowsable(false)
                .setMediaType(MediaMetadata.MEDIA_TYPE_PODCAST_EPISODE);
        try {
            XmlPullParser parser = SonosXmlParser.newParser(didl);
            for (int event = parser.getEventType(); event != XmlPullParser.END_DOCUMENT; event = parser.next()) {
                if (event != XmlPullParser.START_TAG) {
                    continue;
                }
                String name = parser.getName();
                if ("res".equals(name)) {
                    String mimeType = parseMimeType(parser.getAttributeValue(null, "protocolInfo"));
                    if (mimeType != null) {
                        extras.putString(MediaItemAdapter.KEY_MIME_TYPE, mimeType);
                    }
                } else if ("dc:title".equals(name)) {
                    metadata.setTitle(parser.nextText());
                } else if ("dc:creator".equals(name)) {
                    String podcast = parser.nextText();
                    metadata.setArtist(podcast);
                    metadata.setSubtitle(podcast);
                } else if ("upnp:albumArtURI".equals(name)) {
                    String artwork = parser.nextText();
                    if (artwork.startsWith("http://") || artwork.startsWith("https://")) {
                        metadata.setArtworkUri(Uri.parse(artwork));
                    }
                }
            }
        } catch (XmlPullParserException | IOException e) {
            Log.w(TAG, "Unable to parse DIDL: " + e.getMessage());
        }
        metadata.setExtras(extras);
        return new MediaItem.Builder()
                .setMediaId(String.valueOf(mediaId))
                .setUri(Uri.parse(streamUrl))
                .setMediaMetadata(metadata.build())
                .build();
    }

    @Nullable
    private static String parseMimeType(@Nullable String protocolInfo) {
        if (protocolInfo == null) {
            return null;
        }
        String[] parts = protocolInfo.split(":");
        if (parts.length < 3 || "*".equals(parts[2])) {
            return null;
        }
        return parts[2];
    }

    private static String escape(@Nullable CharSequence value) {
        return SonosSoapClient.escapeXml(value != null ? value.toString() : "");
    }
}
