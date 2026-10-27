package de.danoeh.antennapod.playback.sonos;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;

import java.io.IOException;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

class SonosSoapClient {
    private static final MediaType CONTENT_TYPE = MediaType.get("text/xml; charset=\"utf-8\"");
    private static final String INSTANCE = "<InstanceID>0</InstanceID>";
    private static final int ERROR_TRANSITION_NOT_AVAILABLE = 701;

    enum Service {
        AV_TRANSPORT("/MediaRenderer/AVTransport/Control", "AVTransport"),
        GROUP_RENDERING_CONTROL("/MediaRenderer/GroupRenderingControl/Control", "GroupRenderingControl"),
        ZONE_GROUP_TOPOLOGY("/ZoneGroupTopology/Control", "ZoneGroupTopology");

        final String path;
        final String type;

        Service(String path, String type) {
            this.path = path;
            this.type = type;
        }
    }

    private final OkHttpClient client;

    SonosSoapClient() {
        this(new OkHttpClient.Builder()
                .connectTimeout(2, TimeUnit.SECONDS)
                .readTimeout(3, TimeUnit.SECONDS)
                .build());
    }

    SonosSoapClient(OkHttpClient client) {
        this.client = client;
    }

    void setAvTransportUri(String host, int port, String uri, String didl) throws IOException {
        call(host, port, Service.AV_TRANSPORT, "SetAVTransportURI", INSTANCE
                + "<CurrentURI>" + escapeXml(uri) + "</CurrentURI>"
                + "<CurrentURIMetaData>" + escapeXml(didl) + "</CurrentURIMetaData>");
    }

    void play(String host, int port) throws IOException {
        call(host, port, Service.AV_TRANSPORT, "Play", INSTANCE + "<Speed>1</Speed>");
    }

    void pause(String host, int port) throws IOException {
        try {
            call(host, port, Service.AV_TRANSPORT, "Pause", INSTANCE);
        } catch (SonosSoapException e) {
            if (e.getErrorCode() != ERROR_TRANSITION_NOT_AVAILABLE) {
                throw e;
            }
        }
    }

    void stop(String host, int port) throws IOException {
        call(host, port, Service.AV_TRANSPORT, "Stop", INSTANCE);
    }

    void seek(String host, int port, long positionMs) throws IOException {
        call(host, port, Service.AV_TRANSPORT, "Seek", INSTANCE
                + "<Unit>REL_TIME</Unit><Target>" + formatTime(positionMs) + "</Target>");
    }

    String getTransportInfo(String host, int port) throws IOException {
        return call(host, port, Service.AV_TRANSPORT, "GetTransportInfo", INSTANCE);
    }

    String getPositionInfo(String host, int port) throws IOException {
        return call(host, port, Service.AV_TRANSPORT, "GetPositionInfo", INSTANCE);
    }

    String getMediaInfo(String host, int port) throws IOException {
        return call(host, port, Service.AV_TRANSPORT, "GetMediaInfo", INSTANCE);
    }

    String getGroupVolume(String host, int port) throws IOException {
        return call(host, port, Service.GROUP_RENDERING_CONTROL, "GetGroupVolume", INSTANCE);
    }

    void setGroupVolume(String host, int port, int volume) throws IOException {
        int clamped = Math.max(0, Math.min(100, volume));
        call(host, port, Service.GROUP_RENDERING_CONTROL, "SetGroupVolume", INSTANCE
                + "<DesiredVolume>" + clamped + "</DesiredVolume>");
    }

    String getZoneGroupState(String host, int port) throws IOException {
        return call(host, port, Service.ZONE_GROUP_TOPOLOGY, "GetZoneGroupState", "");
    }

    private String call(String host, int port, Service service, String action, String arguments)
            throws IOException {
        String serviceType = "urn:schemas-upnp-org:service:" + service.type + ":1";
        String envelope = "<?xml version=\"1.0\" encoding=\"utf-8\"?>"
                + "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" "
                + "s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\"><s:Body>"
                + "<u:" + action + " xmlns:u=\"" + serviceType + "\">" + arguments + "</u:" + action + ">"
                + "</s:Body></s:Envelope>";
        Request request = new Request.Builder()
                .url("http://" + host + ":" + port + service.path)
                .header("SOAPACTION", "\"" + serviceType + "#" + action + "\"")
                .post(RequestBody.create(envelope, CONTENT_TYPE))
                .build();
        try (Response response = client.newCall(request).execute()) {
            ResponseBody body = response.body();
            String text = body != null ? body.string() : "";
            if (response.isSuccessful()) {
                return text;
            }
            String errorCode = SonosXmlParser.extractValue(text, "errorCode");
            if (errorCode != null) {
                int code;
                try {
                    code = Integer.parseInt(errorCode.trim());
                } catch (NumberFormatException e) {
                    throw new IOException("Invalid UPnP error code " + errorCode);
                }
                throw new SonosSoapException(code);
            }
            throw new IOException("HTTP " + response.code() + " for " + action);
        }
    }

    static String formatTime(long positionMs) {
        long totalSeconds = Math.max(0, positionMs) / 1000;
        return String.format(Locale.US, "%d:%02d:%02d",
                totalSeconds / 3600, (totalSeconds % 3600) / 60, totalSeconds % 60);
    }

    static String escapeXml(String value) {
        if (value == null) {
            return "";
        }
        StringBuilder builder = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '&':
                    builder.append("&amp;");
                    break;
                case '<':
                    builder.append("&lt;");
                    break;
                case '>':
                    builder.append("&gt;");
                    break;
                case '"':
                    builder.append("&quot;");
                    break;
                case '\'':
                    builder.append("&apos;");
                    break;
                default:
                    builder.append(c);
            }
        }
        return builder.toString();
    }
}
