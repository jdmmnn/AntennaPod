package de.danoeh.antennapod.playback.sonos;

import android.util.Log;
import androidx.annotation.Nullable;
import androidx.media3.common.C;
import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;
import org.xmlpull.v1.XmlPullParserFactory;

import java.io.IOException;
import java.io.StringReader;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class SonosXmlParser {
    private static final String TAG = "SonosXmlParser";
    private static final Pattern TIME = Pattern.compile("(\\d+):(\\d{1,2}):(\\d{1,2})(\\.\\d+)?");

    private SonosXmlParser() {
    }

    static SonosStatus parseStatus(String transportInfoXml, String positionInfoXml,
                                   @Nullable String mediaInfoXml, @Nullable String groupVolumeXml,
                                   long timestamp) {
        long durationMs = parseTimeMs(extractValue(positionInfoXml, "TrackDuration"));
        if (durationMs == 0) {
            durationMs = C.TIME_UNSET;
        }
        String trackUri = extractValue(positionInfoXml, "TrackURI");
        return new SonosStatus(
                parseTransportState(transportInfoXml),
                parseTimeMs(extractValue(positionInfoXml, "RelTime")),
                durationMs,
                trackUri != null ? trackUri : "",
                mediaInfoXml != null ? parseMediaId(mediaInfoXml) : null,
                groupVolumeXml != null ? parseVolume(groupVolumeXml) : SonosStatus.VOLUME_UNKNOWN,
                timestamp);
    }

    static SonosStatus.TransportState parseTransportState(String transportInfoXml) {
        String state = extractValue(transportInfoXml, "CurrentTransportState");
        if (state != null) {
            for (SonosStatus.TransportState value : SonosStatus.TransportState.values()) {
                if (value.name().equals(state.trim())) {
                    return value;
                }
            }
        }
        return SonosStatus.TransportState.NO_MEDIA_PRESENT;
    }

    static long parseTimeMs(@Nullable String time) {
        if (time == null) {
            return C.TIME_UNSET;
        }
        Matcher matcher = TIME.matcher(time.trim());
        if (!matcher.matches()) {
            return C.TIME_UNSET;
        }
        long hours = Long.parseLong(matcher.group(1));
        long minutes = Long.parseLong(matcher.group(2));
        long seconds = Long.parseLong(matcher.group(3));
        return ((hours * 60 + minutes) * 60 + seconds) * 1000;
    }

    @Nullable
    static Long parseMediaId(String mediaInfoXml) {
        return SonosDidl.parseMediaId(extractValue(mediaInfoXml, "CurrentURIMetaData"));
    }

    static int parseVolume(String groupVolumeXml) {
        String volume = extractValue(groupVolumeXml, "CurrentVolume");
        if (volume == null) {
            return SonosStatus.VOLUME_UNKNOWN;
        }
        try {
            return Math.max(0, Math.min(100, Integer.parseInt(volume.trim())));
        } catch (NumberFormatException e) {
            return SonosStatus.VOLUME_UNKNOWN;
        }
    }

    static List<SonosGroup> parseZoneGroupState(String zoneGroupStateXml) {
        String state = extractValue(zoneGroupStateXml, "ZoneGroupState");
        if (state == null) {
            return Collections.emptyList();
        }
        List<SonosGroup> groups = new ArrayList<>();
        try {
            XmlPullParser parser = newParser(state);
            String groupId = null;
            String coordinator = null;
            List<String[]> members = new ArrayList<>();
            for (int event = parser.getEventType(); event != XmlPullParser.END_DOCUMENT; event = parser.next()) {
                if (event == XmlPullParser.START_TAG && "ZoneGroup".equals(parser.getName())) {
                    groupId = parser.getAttributeValue(null, "ID");
                    coordinator = parser.getAttributeValue(null, "Coordinator");
                    members.clear();
                } else if (event == XmlPullParser.START_TAG && "ZoneGroupMember".equals(parser.getName())) {
                    String uuid = parser.getAttributeValue(null, "UUID");
                    String location = parser.getAttributeValue(null, "Location");
                    String zoneName = parser.getAttributeValue(null, "ZoneName");
                    String invisible = parser.getAttributeValue(null, "Invisible");
                    members.add(new String[] {uuid, location, zoneName, invisible});
                } else if (event == XmlPullParser.END_TAG && "ZoneGroup".equals(parser.getName())) {
                    SonosGroup group = buildGroup(groupId, coordinator, members);
                    if (group != null) {
                        groups.add(group);
                    }
                }
            }
        } catch (XmlPullParserException | IOException e) {
            Log.w(TAG, "Unable to parse zone group state: " + e.getMessage());
        }
        return groups;
    }

    @Nullable
    private static SonosGroup buildGroup(String groupId, String coordinator, List<String[]> members) {
        if (groupId == null || !SonosGroup.isValidUuid(coordinator)) {
            return null;
        }
        String[] coordinatorMember = null;
        List<String> otherNames = new ArrayList<>();
        for (String[] member : members) {
            if (coordinator.equals(member[0])) {
                coordinatorMember = member;
            } else if (!"1".equals(member[3]) && member[2] != null) {
                otherNames.add(member[2]);
            }
        }
        if (coordinatorMember == null || coordinatorMember[1] == null || coordinatorMember[2] == null) {
            return null;
        }
        URI location;
        try {
            location = new URI(coordinatorMember[1]);
        } catch (URISyntaxException e) {
            return null;
        }
        if (!SonosGroup.isPrivateIpv4(location.getHost())) {
            return null;
        }
        String name = coordinatorMember[2];
        List<String> memberNames = new ArrayList<>();
        memberNames.add(name);
        memberNames.addAll(otherNames);
        if (!otherNames.isEmpty()) {
            name = name + " + " + otherNames.size();
        }
        int port = location.getPort() > 0 ? location.getPort() : SonosGroup.DEFAULT_PORT;
        return new SonosGroup(groupId, name, coordinator, location.getHost(), port, memberNames);
    }

    @Nullable
    static String extractValue(@Nullable String xml, String tag) {
        if (xml == null || xml.isEmpty()) {
            return null;
        }
        try {
            XmlPullParser parser = newParser(xml);
            for (int event = parser.getEventType(); event != XmlPullParser.END_DOCUMENT; event = parser.next()) {
                if (event == XmlPullParser.START_TAG && tag.equals(parser.getName())) {
                    return parser.nextText();
                }
            }
        } catch (XmlPullParserException | IOException e) {
            Log.w(TAG, "Unable to parse " + tag + ": " + e.getMessage());
        }
        return null;
    }

    static XmlPullParser newParser(String xml) throws XmlPullParserException {
        XmlPullParserFactory factory = XmlPullParserFactory.newInstance();
        factory.setNamespaceAware(false);
        XmlPullParser parser = factory.newPullParser();
        parser.setInput(new StringReader(xml));
        return parser;
    }
}
