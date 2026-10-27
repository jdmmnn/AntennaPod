package de.danoeh.antennapod.playback.sonos;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

@RunWith(RobolectricTestRunner.class)
public class SonosDiscoveryTest {
    private static String response(String location, String usn) {
        return "HTTP/1.1 200 OK\r\nCACHE-CONTROL: max-age = 1800\r\nEXT:\r\n"
                + "LOCATION: " + location + "\r\nSERVER: Linux UPnP/1.0 Sonos/97.2\r\n"
                + "ST: urn:schemas-upnp-org:device:ZonePlayer:1\r\n"
                + "USN: " + usn + "::urn:schemas-upnp-org:device:ZonePlayer:1\r\n\r\n";
    }

    @Test
    public void testParseResponse() {
        SonosDiscovery.Endpoint endpoint = SonosDiscovery.parseResponse(response(
                "http://192.168.1.23:1400/xml/device_description.xml", "uuid:RINCON_000E58A0000101400"));
        assertNotNull(endpoint);
        assertEquals("192.168.1.23", endpoint.host);
        assertEquals(1400, endpoint.port);
    }

    @Test
    public void testParseResponseRejectsForeignAndPublic() {
        assertNull(SonosDiscovery.parseResponse(response(
                "http://192.168.1.30:49152/description.xml", "uuid:2f402f80-da50-11e1-9b23-001788")));
        assertNull(SonosDiscovery.parseResponse(response(
                "http://8.8.8.8:1400/xml/device_description.xml", "uuid:RINCON_000E58A0000101400")));
        assertNull(SonosDiscovery.parseResponse("garbage"));
    }

    @Test
    public void testDiscoverBuildsGroupsFromTopology() throws Exception {
        FakeSonosClient client = new FakeSonosClient();
        client.zoneGroupState = Fixtures.load("get_zone_group_state.xml");
        List<String> responses = Arrays.asList(
                response("http://192.168.1.23:1400/xml/device_description.xml", "uuid:RINCON_000E58A0000101400"),
                response("http://192.168.1.35:1400/xml/device_description.xml", "uuid:RINCON_000E58A0000201400"));
        SonosDiscovery discovery = new SonosDiscovery(client, timeout -> responses);
        List<SonosGroup> groups = discovery.discover();
        assertEquals(1, groups.size());
        assertEquals("Living Room + 1", groups.get(0).getName());
        assertEquals(Collections.singletonList("192.168.1.23"), client.hosts);
        assertEquals(groups, SonosDiscovery.getCachedGroups());
    }

    @Test
    public void testDiscoverWithoutResponses() {
        FakeSonosClient client = new FakeSonosClient();
        SonosDiscovery discovery = new SonosDiscovery(client, timeout -> Collections.emptyList());
        assertTrue(discovery.discover().isEmpty());
    }
}
