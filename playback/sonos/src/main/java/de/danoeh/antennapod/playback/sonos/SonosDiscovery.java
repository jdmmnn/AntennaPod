package de.danoeh.antennapod.playback.sonos;

import android.content.Context;
import android.net.wifi.WifiManager;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import androidx.annotation.Nullable;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.InetAddress;
import java.net.MulticastSocket;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

class SonosDiscovery {
    private static final String TAG = "SonosDiscovery";
    static final String SEARCH_TARGET = "urn:schemas-upnp-org:device:ZonePlayer:1";
    private static final String SSDP_ADDRESS = "239.255.255.250";
    private static final int SSDP_PORT = 1900;
    private static final int RESPONSE_WINDOW_MS = 3000;
    private static final int ATTEMPTS = 2;
    private static final Executor EXECUTOR = Executors.newSingleThreadExecutor();
    private static volatile List<SonosGroup> cachedGroups = Collections.emptyList();

    interface Callback {
        void onGroupsFound(List<SonosGroup> groups);
    }

    interface SsdpSearcher {
        List<String> search(int timeoutMs) throws IOException;
    }

    static final class Endpoint {
        final String host;
        final int port;

        Endpoint(String host, int port) {
            this.host = host;
            this.port = port;
        }
    }

    private final SonosSoapClient client;
    private final SsdpSearcher searcher;

    SonosDiscovery(SonosSoapClient client, SsdpSearcher searcher) {
        this.client = client;
        this.searcher = searcher;
    }

    static SonosDiscovery create(Context context, SonosSoapClient client) {
        Context appContext = context.getApplicationContext();
        return new SonosDiscovery(client, timeoutMs -> searchWithMulticastLock(appContext, timeoutMs));
    }

    static List<SonosGroup> getCachedGroups() {
        return cachedGroups;
    }

    void start(Callback callback) {
        Handler handler = new Handler(Looper.getMainLooper());
        EXECUTOR.execute(() -> {
            List<SonosGroup> groups = discover();
            handler.post(() -> callback.onGroupsFound(groups));
        });
    }

    List<SonosGroup> discover() {
        Set<String> responses = new LinkedHashSet<>();
        for (int attempt = 0; attempt < ATTEMPTS && responses.isEmpty(); attempt++) {
            try {
                responses.addAll(searcher.search(RESPONSE_WINDOW_MS));
            } catch (IOException e) {
                Log.w(TAG, "SSDP search failed: " + e.getMessage());
            }
        }
        List<Endpoint> endpoints = new ArrayList<>();
        Set<String> seenHosts = new LinkedHashSet<>();
        for (String response : responses) {
            Endpoint endpoint = parseResponse(response);
            if (endpoint != null && seenHosts.add(endpoint.host)) {
                endpoints.add(endpoint);
            }
        }
        for (SonosGroup group : cachedGroups) {
            if (seenHosts.add(group.getCoordinatorHost())) {
                endpoints.add(new Endpoint(group.getCoordinatorHost(), group.getCoordinatorPort()));
            }
        }
        for (Endpoint endpoint : endpoints) {
            try {
                List<SonosGroup> groups = SonosXmlParser.parseZoneGroupState(
                        client.getZoneGroupState(endpoint.host, endpoint.port));
                if (!groups.isEmpty()) {
                    cachedGroups = Collections.unmodifiableList(new ArrayList<>(groups));
                    return cachedGroups;
                }
            } catch (IOException e) {
                Log.w(TAG, "Unable to read topology from " + endpoint.host + ": " + e.getMessage());
            }
        }
        if (responses.isEmpty()) {
            cachedGroups = Collections.emptyList();
        }
        return Collections.emptyList();
    }

    @Nullable
    static Endpoint parseResponse(String response) {
        String location = null;
        String usn = null;
        for (String line : response.split("\r?\n")) {
            int colon = line.indexOf(':');
            if (colon <= 0) {
                continue;
            }
            String name = line.substring(0, colon).trim().toUpperCase(Locale.US);
            String value = line.substring(colon + 1).trim();
            if ("LOCATION".equals(name)) {
                location = value;
            } else if ("USN".equals(name)) {
                usn = value;
            }
        }
        if (location == null || usn == null || !usn.startsWith("uuid:" + SonosGroup.UUID_PREFIX)) {
            return null;
        }
        URI uri;
        try {
            uri = new URI(location);
        } catch (URISyntaxException e) {
            return null;
        }
        if (!SonosGroup.isPrivateIpv4(uri.getHost())) {
            return null;
        }
        return new Endpoint(uri.getHost(), uri.getPort() > 0 ? uri.getPort() : SonosGroup.DEFAULT_PORT);
    }

    private static List<String> searchWithMulticastLock(Context context, int timeoutMs) throws IOException {
        WifiManager wifiManager = (WifiManager) context.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        WifiManager.MulticastLock lock = null;
        if (wifiManager != null) {
            lock = wifiManager.createMulticastLock(TAG);
            lock.setReferenceCounted(false);
            lock.acquire();
        }
        try {
            return search(timeoutMs);
        } finally {
            if (lock != null) {
                lock.release();
            }
        }
    }

    private static List<String> search(int timeoutMs) throws IOException {
        String request = "M-SEARCH * HTTP/1.1\r\n"
                + "HOST: " + SSDP_ADDRESS + ":" + SSDP_PORT + "\r\n"
                + "MAN: \"ssdp:discover\"\r\n"
                + "MX: 1\r\n"
                + "ST: " + SEARCH_TARGET + "\r\n\r\n";
        byte[] data = request.getBytes(StandardCharsets.US_ASCII);
        List<String> responses = new ArrayList<>();
        try (MulticastSocket socket = new MulticastSocket()) {
            socket.setTimeToLive(2);
            socket.setSoTimeout(250);
            socket.send(new DatagramPacket(data, data.length, InetAddress.getByName(SSDP_ADDRESS), SSDP_PORT));
            long deadline = System.currentTimeMillis() + timeoutMs;
            byte[] buffer = new byte[2048];
            while (System.currentTimeMillis() < deadline) {
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                try {
                    socket.receive(packet);
                    responses.add(new String(packet.getData(), 0, packet.getLength(), StandardCharsets.UTF_8));
                } catch (SocketTimeoutException e) {
                    if (!responses.isEmpty() && System.currentTimeMillis() > deadline - timeoutMs / 2) {
                        break;
                    }
                }
            }
        }
        return responses;
    }
}
