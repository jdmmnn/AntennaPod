package de.danoeh.antennapod.playback.sonos;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

class SonosGroup {
    static final String ROUTE_ID_PREFIX = "sonos:";
    static final String UUID_PREFIX = "RINCON_";
    static final int DEFAULT_PORT = 1400;
    private static final Pattern IPV4 = Pattern.compile("(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})");

    private final String groupId;
    private final String name;
    private final String coordinatorUuid;
    private final String coordinatorHost;
    private final int coordinatorPort;
    private final List<String> memberNames;

    SonosGroup(String groupId, String name, String coordinatorUuid, String coordinatorHost,
               int coordinatorPort, List<String> memberNames) {
        this.groupId = groupId;
        this.name = name;
        this.coordinatorUuid = coordinatorUuid;
        this.coordinatorHost = coordinatorHost;
        this.coordinatorPort = coordinatorPort;
        this.memberNames = Collections.unmodifiableList(new ArrayList<>(memberNames));
    }

    String getGroupId() {
        return groupId;
    }

    String getRouteId() {
        return ROUTE_ID_PREFIX + groupId;
    }

    String getName() {
        return name;
    }

    String getCoordinatorUuid() {
        return coordinatorUuid;
    }

    String getCoordinatorHost() {
        return coordinatorHost;
    }

    int getCoordinatorPort() {
        return coordinatorPort;
    }

    List<String> getMemberNames() {
        return memberNames;
    }

    static boolean isValidUuid(String uuid) {
        return uuid != null && uuid.startsWith(UUID_PREFIX);
    }

    static boolean isPrivateIpv4(String host) {
        if (host == null) {
            return false;
        }
        Matcher matcher = IPV4.matcher(host);
        if (!matcher.matches()) {
            return false;
        }
        int[] octets = new int[4];
        for (int i = 0; i < 4; i++) {
            octets[i] = Integer.parseInt(matcher.group(i + 1));
            if (octets[i] > 255) {
                return false;
            }
        }
        return octets[0] == 10
                || (octets[0] == 172 && octets[1] >= 16 && octets[1] <= 31)
                || (octets[0] == 192 && octets[1] == 168);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof SonosGroup)) {
            return false;
        }
        return groupId.equals(((SonosGroup) o).groupId);
    }

    @Override
    public int hashCode() {
        return groupId.hashCode();
    }
}
