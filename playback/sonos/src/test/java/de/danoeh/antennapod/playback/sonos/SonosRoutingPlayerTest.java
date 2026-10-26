package de.danoeh.antennapod.playback.sonos;

import android.content.Context;
import android.os.Bundle;
import android.os.Looper;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.DeviceInfo;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MediaMetadata;
import androidx.media3.common.Player;
import androidx.media3.common.SimpleBasePlayer;
import androidx.test.core.app.ApplicationProvider;
import com.google.common.collect.ImmutableList;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import de.danoeh.antennapod.event.MessageEvent;
import de.danoeh.antennapod.playback.base.MediaItemAdapter;
import de.danoeh.antennapod.storage.preferences.SonosPreferences;
import org.greenrobot.eventbus.EventBus;
import org.greenrobot.eventbus.Subscribe;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

@RunWith(RobolectricTestRunner.class)
public class SonosRoutingPlayerTest {
    private static final SonosGroup GROUP = new SonosGroup("RINCON_A01400:1", "Living Room", "RINCON_A01400",
            "192.168.1.23", 1400, Collections.singletonList("Living Room"));

    private FakeSonosClient client;
    private FakeLocalPlayer local;
    private SonosSessionManager sessionManager;
    private SonosRoutingPlayer router;

    @Before
    public void setUp() {
        Context context = ApplicationProvider.getApplicationContext();
        setEnabled(context, true);
        SonosPreferences.init(context);
        client = new FakeSonosClient();
        client.transportState = "STOPPED";
        local = new FakeLocalPlayer();
        sessionManager = new SonosSessionManager(context, client, Runnable::run);
        router = new SonosRoutingPlayer(context, local, sessionManager,
                group -> new SonosPlayer(group, client, Runnable::run, Looper.getMainLooper()));
    }

    private static void setEnabled(Context context, boolean enabled) {
        context.getSharedPreferences(context.getPackageName() + "_preferences", Context.MODE_PRIVATE).edit()
                .putBoolean(SonosPreferences.PREF_ENABLED, enabled).commit();
    }

    private static void idleFor(long ms) {
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms));
    }

    @Test
    public void testWithoutSessionMirrorsLocal() {
        local.load(SonosPlayerTest.item(), 42_000, true);
        idleFor(10);
        assertEquals(DeviceInfo.PLAYBACK_TYPE_LOCAL, router.getDeviceInfo().playbackType);
        assertEquals(Player.STATE_READY, router.getPlaybackState());
        assertTrue(router.getPlayWhenReady());
        assertTrue(router.getCurrentPosition() >= 42_000 && router.getCurrentPosition() < 42_100);
        assertEquals(local.getAvailableCommands(), router.getAvailableCommands());
        assertTrue(client.calls.isEmpty());
    }

    @Test
    public void testConnectRejectedWhenDisabled() {
        setEnabled(ApplicationProvider.getApplicationContext(), false);
        assertFalse(sessionManager.connect(GROUP));
        idleFor(10);
        assertTrue(client.calls.isEmpty());
    }

    @Test
    public void testConnectTransfersPlayback() {
        local.load(SonosPlayerTest.item(), 42_000, true);
        sessionManager.connect(GROUP);
        idleFor(10);
        assertEquals(DeviceInfo.PLAYBACK_TYPE_REMOTE, router.getDeviceInfo().playbackType);
        assertEquals("Living Room", sessionManager.getConnectedGroupName());
        assertTrue(client.calls.contains("setAvTransportUri:" + FakeSonosClient.URL));
        assertTrue(client.calls.contains("seek:42000"));
        assertTrue(client.calls.contains("play"));
        assertEquals(Player.STATE_IDLE, local.getPlaybackState());
        assertEquals("123", router.getCurrentMediaItem().mediaId);
    }

    @Test
    public void testGroupVolumeReachesSessionManager() {
        local.load(SonosPlayerTest.item(), 42_000, true);
        sessionManager.connect(GROUP);
        idleFor(10);
        client.volume = 33;
        idleFor(1000);
        assertEquals(33, sessionManager.getVolume());

        sessionManager.setVolume(45);
        idleFor(10);
        assertTrue(client.calls.contains("setGroupVolume:45"));
        assertEquals(45, sessionManager.getVolume());

        sessionManager.updateVolume(-5);
        idleFor(10);
        assertTrue(client.calls.contains("setGroupVolume:40"));
        assertEquals(40, sessionManager.getVolume());
    }

    @Test
    public void testConnectKeepsPause() {
        local.load(SonosPlayerTest.item(), 42_000, false);
        sessionManager.connect(GROUP);
        idleFor(10);
        assertTrue(client.calls.contains("setAvTransportUri:" + FakeSonosClient.URL));
        assertEquals(0, client.count("play"));
        assertFalse(router.getPlayWhenReady());
    }

    @Test
    public void testDisconnectTransfersBackPaused() {
        local.load(SonosPlayerTest.item(), 42_000, true);
        sessionManager.connect(GROUP);
        idleFor(10);
        client.transportState = "PLAYING";
        client.relTime = "0:01:00";
        idleFor(1000);
        sessionManager.disconnect(true);
        idleFor(10);
        assertEquals(DeviceInfo.PLAYBACK_TYPE_LOCAL, router.getDeviceInfo().playbackType);
        assertEquals("123", local.getCurrentMediaItem().mediaId);
        assertFalse(local.getPlayWhenReady());
        assertTrue(local.getCurrentPosition() >= 60_000 && local.getCurrentPosition() < 62_000);
        assertEquals(1, client.count("stop"));
    }

    @Test
    public void testSyncModeDoesNotReload() {
        client.transportState = "PLAYING";
        client.relTime = "0:05:00";
        client.mediaInfoDidl = FakeSonosClient.didlWithId(77);
        sessionManager.connect(GROUP);
        idleFor(10);
        assertEquals(0, client.count("setAvTransportUri"));
        assertEquals(DeviceInfo.PLAYBACK_TYPE_REMOTE, router.getDeviceInfo().playbackType);
        assertEquals("77", router.getCurrentMediaItem().mediaId);
        assertTrue(router.getPlayWhenReady());
        assertTrue(router.getCurrentPosition() >= 300_000);
        assertEquals(FakeSonosClient.URL, router.getCurrentMediaItem().localConfiguration.uri.toString());
    }

    @Test
    public void testWaitsForCastHandover() {
        local.load(SonosPlayerTest.item(), 42_000, true);
        local.setRemote(true);
        sessionManager.connect(GROUP);
        idleFor(1000);
        assertEquals(0, client.count("setAvTransportUri"));
        local.setRemote(false);
        idleFor(10);
        assertEquals(1, client.count("setAvTransportUri"));
    }

    @Test
    public void testCastHandoverTimeout() {
        local.load(SonosPlayerTest.item(), 42_000, true);
        local.setRemote(true);
        sessionManager.connect(GROUP);
        idleFor(2900);
        assertEquals(0, client.count("setAvTransportUri"));
        idleFor(200);
        assertEquals(1, client.count("setAvTransportUri"));
    }

    @Test
    public void testReleaseDoesNotStopSonos() {
        local.load(SonosPlayerTest.item(), 42_000, true);
        sessionManager.connect(GROUP);
        idleFor(10);
        router.release();
        idleFor(10);
        assertEquals(0, client.count("stop"));
        assertEquals(null, sessionManager.getConnectedGroupName());
    }

    @Test
    public void testSetMediaItemDuringActiveSessionStaysOnSonos() {
        ArrayDeque<Runnable> bgTasks = new ArrayDeque<>();
        router = new SonosRoutingPlayer(ApplicationProvider.getApplicationContext(), local, sessionManager,
                group -> new SonosPlayer(group, client, bgTasks::add, Looper.getMainLooper()));
        local.load(SonosPlayerTest.item(), 42_000, true);
        sessionManager.connect(GROUP);
        while (!bgTasks.isEmpty()) {
            bgTasks.poll().run();
        }
        idleFor(500);
        while (!bgTasks.isEmpty()) {
            bgTasks.poll().run();
        }
        idleFor(10);

        idleFor(1000);
        assertEquals(1, bgTasks.size());

        String nextUrl = "https://example.com/episode2.mp3";
        Bundle extras = new Bundle();
        extras.putString(MediaItemAdapter.KEY_STREAM_URL, nextUrl);
        extras.putString(MediaItemAdapter.KEY_MIME_TYPE, "audio/mpeg");
        MediaItem nextItem = new MediaItem.Builder()
                .setMediaId("456")
                .setMediaMetadata(new MediaMetadata.Builder().setTitle("Next").setExtras(extras).build())
                .build();
        router.setMediaItem(nextItem);
        bgTasks.poll().run();
        idleFor(1);
        client.calls.clear();
        while (!bgTasks.isEmpty()) {
            bgTasks.poll().run();
            idleFor(1);
        }
        idleFor(1000);
        while (!bgTasks.isEmpty()) {
            bgTasks.poll().run();
        }
        idleFor(10);

        assertEquals(DeviceInfo.PLAYBACK_TYPE_REMOTE, router.getDeviceInfo().playbackType);
        assertEquals("456", router.getCurrentMediaItem().mediaId);
        assertEquals("setAvTransportUri:" + nextUrl, client.calls.get(0));
        assertEquals("play", client.calls.get(1));
        assertEquals(0, client.count("getMediaInfo"));
        assertEquals(Player.STATE_IDLE, local.getPlaybackState());
    }

    private final List<String> messages = new ArrayList<>();

    @Subscribe
    public void onMessage(MessageEvent event) {
        messages.add(event.message);
    }

    private static MediaItem itemWith(@Nullable String url, @Nullable String mime) {
        Bundle extras = new Bundle();
        if (url != null) {
            extras.putString(MediaItemAdapter.KEY_STREAM_URL, url);
        }
        if (mime != null) {
            extras.putString(MediaItemAdapter.KEY_MIME_TYPE, mime);
        }
        return new MediaItem.Builder()
                .setMediaId("999")
                .setMediaMetadata(new MediaMetadata.Builder().setTitle("Bad").setExtras(extras).build())
                .build();
    }

    @Test
    public void testConnectRejectsUnplayableEpisode() {
        messages.clear();
        EventBus.getDefault().register(this);
        try {
            local.load(itemWith("https://example.com/video.mp4", "video/mp4"), 42_000, true);
            sessionManager.connect(GROUP);
            idleFor(10);
            assertEquals(DeviceInfo.PLAYBACK_TYPE_LOCAL, router.getDeviceInfo().playbackType);
            assertEquals(null, sessionManager.getConnectedGroupName());
            assertEquals(Player.STATE_READY, local.getPlaybackState());
            assertTrue(local.getPlayWhenReady());
            assertTrue(local.getCurrentPosition() >= 42_000 && local.getCurrentPosition() < 42_100);
            assertEquals(0, client.count("setAvTransportUri"));
            assertEquals(1, messages.size());
            assertTrue(messages.get(0).contains("videos are not supported"));
        } finally {
            EventBus.getDefault().unregister(this);
        }
    }

    @Test
    public void testEpisodeChangeRejectsUnplayableEpisodeAndEndsSession() {
        messages.clear();
        EventBus.getDefault().register(this);
        try {
            local.load(SonosPlayerTest.item(), 42_000, true);
            sessionManager.connect(GROUP);
            idleFor(10);
            assertEquals(DeviceInfo.PLAYBACK_TYPE_REMOTE, router.getDeviceInfo().playbackType);
            client.calls.clear();

            router.setMediaItem(itemWith("https://example.com/ep.opus", "audio/opus"));
            idleFor(10);

            assertEquals(DeviceInfo.PLAYBACK_TYPE_LOCAL, router.getDeviceInfo().playbackType);
            assertEquals(null, sessionManager.getConnectedGroupName());
            assertEquals(0, client.count("setAvTransportUri"));
            assertEquals(1, client.count("stop"));
            assertEquals("999", local.getCurrentMediaItem().mediaId);
            assertEquals(1, messages.size());
            assertTrue(messages.get(0).contains("unsupported audio format"));
        } finally {
            EventBus.getDefault().unregister(this);
        }
    }

    static class FakeLocalPlayer extends SimpleBasePlayer {
        private List<MediaItem> items = Collections.emptyList();
        private long position;
        private boolean playWhenReady;
        private int state = STATE_IDLE;
        private boolean remote;

        FakeLocalPlayer() {
            super(Looper.getMainLooper());
        }

        void load(MediaItem item, long positionMs, boolean play) {
            setMediaItem(item, positionMs);
            setPlayWhenReady(play);
            prepare();
        }

        void setRemote(boolean remote) {
            this.remote = remote;
            invalidateState();
        }

        @NonNull
        @Override
        protected State getState() {
            State.Builder builder = new State.Builder()
                    .setAvailableCommands(new Commands.Builder().addAllCommands().build())
                    .setPlayWhenReady(playWhenReady, PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
                    .setDeviceInfo(new DeviceInfo.Builder(remote
                            ? DeviceInfo.PLAYBACK_TYPE_REMOTE : DeviceInfo.PLAYBACK_TYPE_LOCAL).build())
                    .setContentPositionMs(position);
            if (items.isEmpty()) {
                return builder.setPlaybackState(STATE_IDLE).build();
            }
            return builder.setPlaylist(ImmutableList.of(new MediaItemData.Builder(items.get(0).mediaId)
                            .setMediaItem(items.get(0)).setIsSeekable(true).build()))
                    .setPlaybackState(state)
                    .build();
        }

        @NonNull
        @Override
        protected ListenableFuture<?> handleSetMediaItems(@NonNull List<MediaItem> mediaItems, int startIndex,
                                                          long startPositionMs) {
            items = mediaItems.isEmpty() ? Collections.emptyList() : Collections.singletonList(mediaItems.get(0));
            position = startPositionMs == C.TIME_UNSET ? 0 : startPositionMs;
            return Futures.immediateVoidFuture();
        }

        @NonNull
        @Override
        protected ListenableFuture<?> handleSetPlayWhenReady(boolean playWhenReady) {
            this.playWhenReady = playWhenReady;
            return Futures.immediateVoidFuture();
        }

        @NonNull
        @Override
        protected ListenableFuture<?> handlePrepare() {
            state = STATE_READY;
            return Futures.immediateVoidFuture();
        }

        @NonNull
        @Override
        protected ListenableFuture<?> handleStop() {
            state = STATE_IDLE;
            return Futures.immediateVoidFuture();
        }

        @NonNull
        @Override
        protected ListenableFuture<?> handleSeek(int mediaItemIndex, long positionMs, int seekCommand) {
            position = positionMs;
            return Futures.immediateVoidFuture();
        }

        @NonNull
        @Override
        protected ListenableFuture<?> handleRelease() {
            return Futures.immediateVoidFuture();
        }
    }
}
