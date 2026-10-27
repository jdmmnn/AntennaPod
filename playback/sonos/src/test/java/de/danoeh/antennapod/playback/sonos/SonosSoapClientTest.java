package de.danoeh.antennapod.playback.sonos;

import okhttp3.OkHttpClient;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import okhttp3.mockwebserver.SocketPolicy;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

@RunWith(RobolectricTestRunner.class)
public class SonosSoapClientTest {
    private MockWebServer server;
    private SonosSoapClient client;

    @Before
    public void setUp() throws IOException {
        server = new MockWebServer();
        server.start();
        client = new SonosSoapClient(new OkHttpClient.Builder()
                .connectTimeout(1, TimeUnit.SECONDS)
                .readTimeout(1, TimeUnit.SECONDS)
                .build());
    }

    @After
    public void tearDown() throws IOException {
        server.shutdown();
    }

    private String host() {
        return server.getHostName();
    }

    @Test
    public void testPlaySendsHeadersAndEnvelope() throws Exception {
        server.enqueue(new MockResponse().setBody(Fixtures.load("get_transport_info_playing.xml")));
        client.play(host(), server.getPort());
        RecordedRequest request = server.takeRequest();
        assertEquals("POST", request.getMethod());
        assertEquals("/MediaRenderer/AVTransport/Control", request.getPath());
        assertEquals("\"urn:schemas-upnp-org:service:AVTransport:1#Play\"", request.getHeader("SOAPACTION"));
        assertTrue(request.getHeader("Content-Type").startsWith("text/xml; charset=\"utf-8\""));
        String body = request.getBody().readUtf8();
        assertTrue(body.contains("<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\""));
        assertTrue(body.contains("<u:Play xmlns:u=\"urn:schemas-upnp-org:service:AVTransport:1\">"
                + "<InstanceID>0</InstanceID><Speed>1</Speed></u:Play>"));
    }

    @Test
    public void testServicePaths() throws Exception {
        server.enqueue(new MockResponse().setBody(Fixtures.load("get_group_volume.xml")));
        server.enqueue(new MockResponse().setBody(Fixtures.load("get_zone_group_state.xml")));
        client.getGroupVolume(host(), server.getPort());
        client.getZoneGroupState(host(), server.getPort());
        RecordedRequest volume = server.takeRequest();
        assertEquals("/MediaRenderer/GroupRenderingControl/Control", volume.getPath());
        assertEquals("\"urn:schemas-upnp-org:service:GroupRenderingControl:1#GetGroupVolume\"",
                volume.getHeader("SOAPACTION"));
        RecordedRequest topology = server.takeRequest();
        assertEquals("/ZoneGroupTopology/Control", topology.getPath());
        assertEquals("\"urn:schemas-upnp-org:service:ZoneGroupTopology:1#GetZoneGroupState\"",
                topology.getHeader("SOAPACTION"));
    }

    @Test
    public void testSetAvTransportUriEscapesArguments() throws Exception {
        server.enqueue(new MockResponse().setBody("<ok/>"));
        String url = "https://example.com/e.mp3?a=1&b=2";
        String didl = "<DIDL-Lite><item id=\"antennapod:1\"><dc:title>Q&amp;A</dc:title></item></DIDL-Lite>";
        client.setAvTransportUri(host(), server.getPort(), url, didl);
        String body = server.takeRequest().getBody().readUtf8();
        assertTrue(body.contains("<CurrentURI>https://example.com/e.mp3?a=1&amp;b=2</CurrentURI>"));
        assertTrue(body.contains("<CurrentURIMetaData>&lt;DIDL-Lite&gt;&lt;item id=&quot;antennapod:1&quot;&gt;"
                + "&lt;dc:title&gt;Q&amp;amp;A&lt;/dc:title&gt;"));
        assertFalse(body.contains("a=1&b=2"));
    }

    @Test
    public void testSeekFormatsTarget() throws Exception {
        server.enqueue(new MockResponse().setBody("<ok/>"));
        client.seek(host(), server.getPort(), 3_723_900);
        String body = server.takeRequest().getBody().readUtf8();
        assertTrue(body.contains("<Unit>REL_TIME</Unit><Target>1:02:03</Target>"));
    }

    @Test
    public void testSetGroupVolumeClamps() throws Exception {
        server.enqueue(new MockResponse().setBody("<ok/>"));
        client.setGroupVolume(host(), server.getPort(), 150);
        assertTrue(server.takeRequest().getBody().readUtf8().contains("<DesiredVolume>100</DesiredVolume>"));
    }

    @Test
    public void testFaultThrowsSoapException() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(500).setBody(Fixtures.load("soap_fault_701.xml")));
        try {
            client.stop(host(), server.getPort());
            fail("Expected SonosSoapException");
        } catch (SonosSoapException e) {
            assertEquals(701, e.getErrorCode());
        }
    }

    @Test
    public void testPauseIgnoresFault701() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(500).setBody(Fixtures.load("soap_fault_701.xml")));
        client.pause(host(), server.getPort());
    }

    @Test(expected = IOException.class)
    public void testHttpErrorWithoutFault() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(404));
        client.getTransportInfo(host(), server.getPort());
    }

    @Test(expected = IOException.class)
    public void testTimeout() throws Exception {
        server.enqueue(new MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE));
        client.getPositionInfo(host(), server.getPort());
    }

    @Test
    public void testReturnsResponseBody() throws Exception {
        String fixture = Fixtures.load("get_media_info.xml");
        server.enqueue(new MockResponse().setBody(fixture));
        assertEquals(fixture, client.getMediaInfo(host(), server.getPort()));
    }
}
