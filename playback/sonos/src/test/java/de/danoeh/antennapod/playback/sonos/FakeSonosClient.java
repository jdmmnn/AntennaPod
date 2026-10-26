package de.danoeh.antennapod.playback.sonos;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

class FakeSonosClient extends SonosSoapClient {
    static final String URL = "https://example.com/episode.mp3";
    final List<String> calls = new ArrayList<>();
    final List<String> hosts = new ArrayList<>();
    String transportState = "PLAYING";
    String relTime = "0:00:10";
    String duration = "0:30:00";
    String trackUri = URL;
    String mediaInfoDidl = "";
    String mediaInfoUri = URL;
    int volume = 20;
    boolean failAll;
    String zoneGroupState = "";

    static String transportXml(String state) {
        return "<r><CurrentTransportState>" + state + "</CurrentTransportState></r>";
    }

    static String didlWithId(long id) {
        return "<DIDL-Lite><item id=\"antennapod:" + id + "\"><dc:title>T</dc:title>"
                + "<dc:creator>P</dc:creator><res protocolInfo=\"http-get:*:audio/mpeg:*\">" + URL
                + "</res></item></DIDL-Lite>";
    }

    int count(String call) {
        int count = 0;
        for (String c : calls) {
            if (c.equals(call) || c.startsWith(call + ":")) {
                count++;
            }
        }
        return count;
    }

    private void record(String host, String call) throws IOException {
        hosts.add(host);
        calls.add(call);
        if (failAll) {
            throw new IOException("unreachable");
        }
    }

    @Override
    void setAvTransportUri(String host, int port, String uri, String didl) throws IOException {
        record(host, "setAvTransportUri:" + uri);
        trackUri = uri;
        mediaInfoUri = uri;
        mediaInfoDidl = didl;
    }

    @Override
    void play(String host, int port) throws IOException {
        record(host, "play");
    }

    @Override
    void pause(String host, int port) throws IOException {
        record(host, "pause");
    }

    @Override
    void stop(String host, int port) throws IOException {
        record(host, "stop");
    }

    @Override
    void seek(String host, int port, long positionMs) throws IOException {
        record(host, "seek:" + positionMs);
    }

    @Override
    String getTransportInfo(String host, int port) throws IOException {
        record(host, "getTransportInfo");
        return transportXml(transportState);
    }

    @Override
    String getPositionInfo(String host, int port) throws IOException {
        record(host, "getPositionInfo");
        return "<r><TrackDuration>" + duration + "</TrackDuration><RelTime>" + relTime + "</RelTime>"
                + "<TrackURI>" + escapeXml(trackUri) + "</TrackURI></r>";
    }

    @Override
    String getMediaInfo(String host, int port) throws IOException {
        record(host, "getMediaInfo");
        return "<r><CurrentURI>" + escapeXml(mediaInfoUri) + "</CurrentURI><CurrentURIMetaData>"
                + escapeXml(mediaInfoDidl) + "</CurrentURIMetaData></r>";
    }

    @Override
    String getGroupVolume(String host, int port) throws IOException {
        record(host, "getGroupVolume");
        return "<r><CurrentVolume>" + volume + "</CurrentVolume></r>";
    }

    @Override
    void setGroupVolume(String host, int port, int volume) throws IOException {
        record(host, "setGroupVolume:" + volume);
    }

    @Override
    String getZoneGroupState(String host, int port) throws IOException {
        hosts.add(host);
        calls.add("getZoneGroupState");
        return zoneGroupState;
    }
}
