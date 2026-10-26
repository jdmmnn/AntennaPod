package de.danoeh.antennapod.playback.sonos;

import android.os.Bundle;
import android.os.Looper;
import androidx.media3.common.DeviceInfo;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MediaMetadata;
import androidx.media3.common.Player;
import de.danoeh.antennapod.playback.base.MediaItemAdapter;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

@RunWith(RobolectricTestRunner.class)
public class SonosPlayerTest {
    private static final SonosGroup GROUP = new SonosGroup("RINCON_A01400:1", "Living Room", "RINCON_A01400",
            "192.168.1.23", 1400, Collections.singletonList("Living Room"));

    private FakeSonosClient client;
    private SonosPlayer player;
    private final List<SonosPlayer.EndReason> endReasons = new ArrayList<>();

    static MediaItem item() {
        Bundle extras = new Bundle();
        extras.putString(MediaItemAdapter.KEY_STREAM_URL, FakeSonosClient.URL);
        return new MediaItem.Builder()
                .setMediaId("123")
                .setMediaMetadata(new MediaMetadata.Builder().setTitle("T").setExtras(extras).build())
                .build();
    }

    @Before
    public void setUp() {
        client = new FakeSonosClient();
        player = new SonosPlayer(GROUP, client, Runnable::run, Looper.getMainLooper());
        player.setListener(endReasons::add);
    }

    private static void idleFor(long ms) {
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms));
    }

    private void startPlaying() {
        player.setMediaItem(item(), 5000);
        player.setPlayWhenReady(true);
        player.prepare();
        idleFor(600);
    }

    @Test
    public void testLoadSendsDirectUrlSeekAndPlay() {
        startPlaying();
        assertEquals("setAvTransportUri:" + FakeSonosClient.URL, client.calls.get(0));
        assertEquals("seek:5000", client.calls.get(1));
        assertEquals("play", client.calls.get(2));
        for (String call : client.calls) {
            assertFalse(call.contains("x-rincon-mp3radio"));
        }
    }

    @Test
    public void testPausedLoadDoesNotPlay() {
        player.setMediaItem(item(), 5000);
        player.prepare();
        idleFor(600);
        assertEquals(0, client.count("play"));
        client.transportState = "STOPPED";
        client.relTime = "0:00:05";
        idleFor(5000);
        assertEquals(Player.STATE_READY, player.getPlaybackState());
        assertFalse(player.getPlayWhenReady());
        assertEquals(5000, player.getCurrentPosition());
    }

    @Test
    public void testStateMapping() {
        startPlaying();
        assertEquals(Player.STATE_READY, player.getPlaybackState());
        assertTrue(player.getPlayWhenReady());

        client.transportState = "PAUSED_PLAYBACK";
        idleFor(1000);
        assertEquals(Player.STATE_READY, player.getPlaybackState());
        assertFalse(player.getPlayWhenReady());

        client.transportState = "TRANSITIONING";
        idleFor(5000);
        assertEquals(Player.STATE_BUFFERING, player.getPlaybackState());

        client.transportState = "NO_MEDIA_PRESENT";
        idleFor(1000);
        assertEquals(Player.STATE_IDLE, player.getPlaybackState());
    }

    @Test
    public void testEndedAfterPlayingNearEnd() {
        client.relTime = "0:29:58";
        startPlaying();
        assertEquals(Player.STATE_READY, player.getPlaybackState());
        client.transportState = "STOPPED";
        client.relTime = "0:00:00";
        idleFor(1000);
        assertEquals(Player.STATE_ENDED, player.getPlaybackState());
    }

    @Test
    public void testStoppedMidEpisodeIsNotEnded() {
        client.relTime = "0:10:00";
        startPlaying();
        client.transportState = "STOPPED";
        idleFor(1000);
        assertEquals(Player.STATE_READY, player.getPlaybackState());
        assertFalse(player.getPlayWhenReady());
    }

    @Test
    public void testDeviceInfo() {
        DeviceInfo deviceInfo = player.getDeviceInfo();
        assertEquals(DeviceInfo.PLAYBACK_TYPE_REMOTE, deviceInfo.playbackType);
        assertEquals(0, deviceInfo.minVolume);
        assertEquals(100, deviceInfo.maxVolume);
        assertFalse(player.isCommandAvailable(Player.COMMAND_SET_SPEED_AND_PITCH));
    }

    @Test
    public void testVolume() {
        startPlaying();
        assertEquals(20, player.getDeviceVolume());
        player.setDeviceVolume(35, 0);
        idleFor(10);
        assertTrue(client.calls.contains("setGroupVolume:35"));
    }

    @Test
    public void testPauseAndSeek() {
        startPlaying();
        player.pause();
        player.seekTo(60_000);
        idleFor(10);
        assertTrue(client.calls.contains("pause"));
        assertTrue(client.calls.contains("seek:60000"));
    }

    @Test
    public void testPollIntervals() {
        startPlaying();
        int before = client.count("getTransportInfo");
        idleFor(3000);
        assertEquals(3, client.count("getTransportInfo") - before);
        client.transportState = "PAUSED_PLAYBACK";
        idleFor(1000);
        before = client.count("getTransportInfo");
        idleFor(10_000);
        assertEquals(2, client.count("getTransportInfo") - before);
    }

    @Test
    public void testUnreachableAfterTenSeconds() {
        startPlaying();
        client.failAll = true;
        idleFor(9000);
        assertTrue(endReasons.isEmpty());
        idleFor(3000);
        assertEquals(Collections.singletonList(SonosPlayer.EndReason.UNREACHABLE), endReasons);
        assertEquals(0, client.count("stop"));
    }

    @Test
    public void testTakeoverWithoutStop() {
        startPlaying();
        client.trackUri = "x-sonosapi-stream:radio";
        client.mediaInfoUri = "x-sonosapi-stream:radio";
        client.mediaInfoDidl = "<DIDL-Lite><item id=\"radio\"/></DIDL-Lite>";
        idleFor(1000);
        assertEquals(Collections.singletonList(SonosPlayer.EndReason.TAKEOVER), endReasons);
        assertEquals(0, client.count("stop"));
    }

    @Test
    public void testSameEpisodeWithOtherUriIsNoTakeover() {
        startPlaying();
        client.trackUri = "https://cdn.example.com/episode.mp3";
        client.mediaInfoUri = client.trackUri;
        client.mediaInfoDidl = FakeSonosClient.didlWithId(123);
        idleFor(2000);
        assertTrue(endReasons.isEmpty());
    }

    @Test
    public void testReleaseDoesNotStop() {
        startPlaying();
        player.release();
        idleFor(2000);
        assertEquals(0, client.count("stop"));
    }

    @Test
    public void testEndSessionWithStop() {
        startPlaying();
        player.endSession(true);
        idleFor(10);
        assertEquals(1, client.count("stop"));
    }

    @Test
    public void testCoordinatorChange() {
        startPlaying();
        client.failAll = true;
        client.zoneGroupState = zoneGroupState("RINCON_A01400:1", "RINCON_B01400", "192.168.1.40");
        idleFor(1000);
        client.failAll = false;
        idleFor(1000);
        assertEquals("192.168.1.40", player.getGroup().getCoordinatorHost());
        assertEquals("192.168.1.40", client.hosts.get(client.hosts.size() - 1));
        assertTrue(endReasons.isEmpty());
    }

    @Test
    public void testGroupGoneIsTakeover() {
        startPlaying();
        client.failAll = true;
        client.zoneGroupState = zoneGroupState("RINCON_X01400:9", "RINCON_X01400", "192.168.1.50");
        idleFor(1000);
        assertEquals(Collections.singletonList(SonosPlayer.EndReason.TAKEOVER), endReasons);
    }

    private static String zoneGroupState(String groupId, String coordinator, String host) {
        String state = "<ZoneGroupState><ZoneGroups><ZoneGroup Coordinator=\"" + coordinator + "\" ID=\"" + groupId
                + "\"><ZoneGroupMember UUID=\"" + coordinator + "\" Location=\"http://" + host
                + ":1400/xml/d.xml\" ZoneName=\"Living Room\"/></ZoneGroup></ZoneGroups></ZoneGroupState>";
        return "<r><ZoneGroupState>" + SonosSoapClient.escapeXml(state) + "</ZoneGroupState></r>";
    }
}
