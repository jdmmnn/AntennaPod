package de.danoeh.antennapod.playback.sonos;

import androidx.media3.common.C;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

@RunWith(RobolectricTestRunner.class)
public class SonosXmlParserTest {
    private static final String DARKNET_URL = "https://www.podtrac.com/pts/redirect.mp3/dovetail.prxu.org/7057/"
            + "88c4d72a-2377-4fef-9dac-3cd23529b881/darknet-diaries-ep181-ricky.mp3";

    @Test
    public void testParseTimeMs() {
        assertEquals(0, SonosXmlParser.parseTimeMs("0:00:00"));
        assertEquals(602_000, SonosXmlParser.parseTimeMs("0:10:02"));
        assertEquals(5_034_000, SonosXmlParser.parseTimeMs("1:23:54"));
        assertEquals(C.TIME_UNSET, SonosXmlParser.parseTimeMs("NOT_IMPLEMENTED"));
        assertEquals(C.TIME_UNSET, SonosXmlParser.parseTimeMs(""));
        assertEquals(C.TIME_UNSET, SonosXmlParser.parseTimeMs(null));
        assertEquals(C.TIME_UNSET, SonosXmlParser.parseTimeMs("garbage"));
    }

    @Test
    public void testParseTransportState() throws Exception {
        assertEquals(SonosStatus.TransportState.PLAYING,
                SonosXmlParser.parseTransportState(Fixtures.load("get_transport_info_playing.xml")));
        assertEquals(SonosStatus.TransportState.PAUSED_PLAYBACK,
                SonosXmlParser.parseTransportState(Fixtures.load("get_transport_info_paused.xml")));
        assertEquals(SonosStatus.TransportState.STOPPED,
                SonosXmlParser.parseTransportState(Fixtures.load("get_transport_info_stopped.xml")));
        assertEquals(SonosStatus.TransportState.TRANSITIONING, SonosXmlParser.parseTransportState(
                "<r><CurrentTransportState>TRANSITIONING</CurrentTransportState></r>"));
        assertEquals(SonosStatus.TransportState.NO_MEDIA_PRESENT, SonosXmlParser.parseTransportState(
                "<r><CurrentTransportState>NO_MEDIA_PRESENT</CurrentTransportState></r>"));
        assertEquals(SonosStatus.TransportState.NO_MEDIA_PRESENT, SonosXmlParser.parseTransportState(
                "<r><CurrentTransportState>SOMETHING_NEW</CurrentTransportState></r>"));
    }

    @Test
    public void testParseStatusPlaying() throws Exception {
        SonosStatus status = SonosXmlParser.parseStatus(
                Fixtures.load("get_transport_info_playing.xml"),
                Fixtures.load("get_position_info_playing.xml"),
                Fixtures.load("get_media_info.xml"),
                Fixtures.load("get_group_volume.xml"),
                42);
        assertEquals(SonosStatus.TransportState.PLAYING, status.getTransportState());
        assertEquals(602_000, status.getPositionMs());
        assertEquals(5_034_000, status.getDurationMs());
        assertEquals(DARKNET_URL, status.getTrackUri());
        assertEquals(Long.valueOf(123), status.getMediaId());
        assertEquals(15, status.getGroupVolume());
        assertEquals(42, status.getTimestamp());
    }

    @Test
    public void testParseStatusWithoutMediaInfoAndVolume() throws Exception {
        SonosStatus status = SonosXmlParser.parseStatus(
                Fixtures.load("get_transport_info_paused.xml"),
                Fixtures.load("get_position_info_playing.xml"),
                null, null, 0);
        assertEquals(SonosStatus.TransportState.PAUSED_PLAYBACK, status.getTransportState());
        assertNull(status.getMediaId());
        assertEquals(SonosStatus.VOLUME_UNKNOWN, status.getGroupVolume());
    }

    @Test
    public void testTrackMetaDataNeverContainsMarker() throws Exception {
        String trackMetaData = SonosXmlParser.extractValue(
                Fixtures.load("get_position_info_playing.xml"), "TrackMetaData");
        assertTrue(trackMetaData.startsWith("<DIDL-Lite"));
        assertNull(SonosDidl.parseMediaId(trackMetaData));
    }

    @Test
    public void testParseMediaIdFromMediaInfo() throws Exception {
        assertEquals(Long.valueOf(123), SonosXmlParser.parseMediaId(Fixtures.load("get_media_info.xml")));
    }

    @Test
    public void testEnded() throws Exception {
        SonosStatus status = SonosXmlParser.parseStatus(
                Fixtures.load("get_transport_info_stopped.xml"),
                Fixtures.load("get_position_info_ended.xml"),
                null, null, 0);
        assertEquals(SonosStatus.TransportState.STOPPED, status.getTransportState());
        assertEquals(0, status.getPositionMs());
        assertEquals(376_000, status.getDurationMs());
        assertEquals("https://op3.dev/e,pg=9b024349-ccf0-5f69-a609-6b82873eab3c/podnews.net/audio/podnews261026.mp3",
                status.getTrackUri());
    }

    @Test
    public void testForeignSource() throws Exception {
        SonosStatus status = SonosXmlParser.parseStatus(
                Fixtures.load("get_transport_info_paused.xml"),
                Fixtures.load("get_position_info_foreign.xml"),
                null, null, 0);
        assertTrue(status.getTrackUri().startsWith("x-sonos-vli:"));
        assertEquals(C.TIME_UNSET, status.getPositionMs());
        assertEquals(C.TIME_UNSET, status.getDurationMs());
    }

    @Test
    public void testGroupMember() throws Exception {
        SonosStatus status = SonosXmlParser.parseStatus(
                Fixtures.load("get_transport_info_playing.xml"),
                Fixtures.load("get_position_info_group_member.xml"),
                null, null, 0);
        assertTrue(status.getTrackUri().startsWith("x-rincon:"));
    }

    @Test
    public void testZeroDurationIsUnset() {
        SonosStatus status = SonosXmlParser.parseStatus(
                "<r><CurrentTransportState>STOPPED</CurrentTransportState></r>",
                "<r><TrackDuration>0:00:00</TrackDuration><RelTime>0:00:00</RelTime><TrackURI></TrackURI></r>",
                null, null, 0);
        assertEquals(C.TIME_UNSET, status.getDurationMs());
        assertEquals(0, status.getPositionMs());
        assertEquals("", status.getTrackUri());
    }

    @Test
    public void testParseVolume() throws Exception {
        assertEquals(15, SonosXmlParser.parseVolume(Fixtures.load("get_group_volume.xml")));
        assertEquals(100, SonosXmlParser.parseVolume("<r><CurrentVolume>120</CurrentVolume></r>"));
        assertEquals(SonosStatus.VOLUME_UNKNOWN, SonosXmlParser.parseVolume("<r><CurrentVolume>x</CurrentVolume></r>"));
    }

    @Test
    public void testParseZoneGroupStateFixture() throws Exception {
        List<SonosGroup> groups = SonosXmlParser.parseZoneGroupState(Fixtures.load("get_zone_group_state.xml"));
        assertEquals(1, groups.size());
        SonosGroup group = groups.get(0);
        assertEquals("RINCON_000E58A0000101400:3866083528", group.getGroupId());
        assertEquals("sonos:RINCON_000E58A0000101400:3866083528", group.getRouteId());
        assertEquals("Living Room + 1", group.getName());
        assertEquals("RINCON_000E58A0000101400", group.getCoordinatorUuid());
        assertEquals("192.168.1.23", group.getCoordinatorHost());
        assertEquals(1400, group.getCoordinatorPort());
        assertEquals(Arrays.asList("Living Room", "Sonos Roam SL"), group.getMemberNames());
    }

    @Test
    public void testParseZoneGroupStateHidesInvisibleAndSatellites() {
        String state = "<ZoneGroupState><ZoneGroups>"
                + "<ZoneGroup Coordinator=\"RINCON_A01400\" ID=\"RINCON_A01400:1\">"
                + "<ZoneGroupMember UUID=\"RINCON_A01400\" Location=\"http://10.0.0.2:1400/xml/d.xml\" "
                + "ZoneName=\"Kitchen\">"
                + "<Satellite UUID=\"RINCON_S01400\" Location=\"http://10.0.0.9:1400/xml/d.xml\" ZoneName=\"Kitchen\" "
                + "Invisible=\"1\"/>"
                + "</ZoneGroupMember>"
                + "<ZoneGroupMember UUID=\"RINCON_B01400\" Location=\"http://10.0.0.3:1400/xml/d.xml\" "
                + "ZoneName=\"Kitchen\" Invisible=\"1\"/>"
                + "</ZoneGroup>"
                + "</ZoneGroups></ZoneGroupState>";
        List<SonosGroup> groups = SonosXmlParser.parseZoneGroupState(wrapZoneGroupState(state));
        assertEquals(1, groups.size());
        assertEquals("Kitchen", groups.get(0).getName());
        assertEquals(Collections.singletonList("Kitchen"), groups.get(0).getMemberNames());
        assertEquals("10.0.0.2", groups.get(0).getCoordinatorHost());
    }

    @Test
    public void testParseZoneGroupStateLegacyRootAndPort() {
        String state = "<ZoneGroups>"
                + "<ZoneGroup Coordinator=\"RINCON_A01400\" ID=\"RINCON_A01400:1\">"
                + "<ZoneGroupMember UUID=\"RINCON_A01400\" Location=\"http://172.16.4.2:1401/xml/d.xml\" "
                + "ZoneName=\"Bath\"/>"
                + "</ZoneGroup></ZoneGroups>";
        List<SonosGroup> groups = SonosXmlParser.parseZoneGroupState(wrapZoneGroupState(state));
        assertEquals(1, groups.size());
        assertEquals(1401, groups.get(0).getCoordinatorPort());
    }

    @Test
    public void testParseZoneGroupStateDropsInvalidGroups() {
        String state = "<ZoneGroupState><ZoneGroups>"
                + "<ZoneGroup Coordinator=\"RINCON_A01400\" ID=\"RINCON_A01400:1\">"
                + "<ZoneGroupMember UUID=\"RINCON_A01400\" Location=\"http://8.8.8.8:1400/xml/d.xml\" ZoneName=\"Pub\"/>"
                + "</ZoneGroup>"
                + "<ZoneGroup Coordinator=\"OTHER_B\" ID=\"OTHER_B:1\">"
                + "<ZoneGroupMember UUID=\"OTHER_B\" Location=\"http://192.168.0.5:1400/xml/d.xml\" ZoneName=\"X\"/>"
                + "</ZoneGroup>"
                + "<ZoneGroup Coordinator=\"RINCON_C01400\" ID=\"RINCON_C01400:1\">"
                + "<ZoneGroupMember UUID=\"RINCON_C01400\" Location=\"http://172.32.0.5:1400/xml/d.xml\" ZoneName=\"Y\"/>"
                + "</ZoneGroup>"
                + "<ZoneGroup Coordinator=\"RINCON_D01400\" ID=\"RINCON_D01400:1\">"
                + "<ZoneGroupMember UUID=\"RINCON_D01400\" Location=\"http://192.168.0.6:1400/xml/d.xml\" ZoneName=\"Z\"/>"
                + "</ZoneGroup>"
                + "</ZoneGroups></ZoneGroupState>";
        List<SonosGroup> groups = SonosXmlParser.parseZoneGroupState(wrapZoneGroupState(state));
        assertEquals(1, groups.size());
        assertEquals("Z", groups.get(0).getName());
    }

    @Test
    public void testParseZoneGroupStateGarbage() {
        assertTrue(SonosXmlParser.parseZoneGroupState("not xml").isEmpty());
    }

    private static String wrapZoneGroupState(String state) {
        return "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\"><s:Body>"
                + "<u:GetZoneGroupStateResponse xmlns:u=\"urn:schemas-upnp-org:service:ZoneGroupTopology:1\">"
                + "<ZoneGroupState>" + SonosSoapClient.escapeXml(state) + "</ZoneGroupState>"
                + "</u:GetZoneGroupStateResponse></s:Body></s:Envelope>";
    }
}
