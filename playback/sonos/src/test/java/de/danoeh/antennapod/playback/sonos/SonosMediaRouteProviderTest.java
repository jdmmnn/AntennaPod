package de.danoeh.antennapod.playback.sonos;

import android.content.Context;
import android.os.Looper;
import androidx.annotation.Nullable;
import androidx.mediarouter.media.MediaRouteProvider;
import androidx.mediarouter.media.MediaRouter;
import androidx.test.core.app.ApplicationProvider;
import de.danoeh.antennapod.storage.preferences.SonosPreferences;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

@RunWith(RobolectricTestRunner.class)
public class SonosMediaRouteProviderTest {
    private static final SonosGroup GROUP = new SonosGroup("RINCON_A01400:1", "Living Room", "RINCON_A01400",
            "192.168.1.23", 1400, Collections.singletonList("Living Room"));

    private final List<Boolean> disconnects = new ArrayList<>();
    private SonosSessionManager sessionManager;
    private SonosMediaRouteProvider provider;

    @Before
    public void setUp() {
        Context context = ApplicationProvider.getApplicationContext();
        context.getSharedPreferences(context.getPackageName() + "_preferences", Context.MODE_PRIVATE).edit()
                .putBoolean(SonosPreferences.PREF_ENABLED, true).commit();
        SonosPreferences.init(context);
        FakeSonosClient client = new FakeSonosClient();
        client.transportState = "STOPPED";
        sessionManager = new SonosSessionManager(context, client, Runnable::run);
        sessionManager.setListener(new SonosSessionManager.Listener() {
            @Override
            public void onConnect(SonosGroup group, @Nullable SonosSessionManager.SyncInfo syncInfo) {
            }

            @Override
            public void onDisconnect(boolean stopRemote) {
                disconnects.add(stopRemote);
            }

            @Override
            public void onSetVolume(int volume) {
            }

            @Override
            public void onUpdateVolume(int delta) {
            }
        });
        provider = new SonosMediaRouteProvider(context, sessionManager);
    }

    private MediaRouteProvider.RouteController connectedController() {
        sessionManager.connect(GROUP);
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(10));
        assertEquals("Living Room", sessionManager.getConnectedGroupName());
        return provider.onCreateRouteController(GROUP.getRouteId());
    }

    @Test
    public void testSearchPendingUntilFirstDiscoveryCompletes() {
        assertTrue(provider.isSearchPending());
    }

    @Test
    public void testRouteChangedStopsSonos() {
        connectedController().onUnselect(MediaRouter.UNSELECT_REASON_ROUTE_CHANGED);
        assertEquals(Collections.singletonList(true), disconnects);
    }

    @Test
    public void testStoppedStopsSonos() {
        connectedController().onUnselect(MediaRouter.UNSELECT_REASON_STOPPED);
        assertEquals(Collections.singletonList(true), disconnects);
    }

    @Test
    public void testDisconnectedKeepsSonosPlaying() {
        connectedController().onUnselect(MediaRouter.UNSELECT_REASON_DISCONNECTED);
        assertEquals(Collections.singletonList(false), disconnects);
    }

    @Test
    public void testOtherRouteDoesNotDisconnect() {
        connectedController();
        provider.onCreateRouteController("sonos:other").onUnselect(MediaRouter.UNSELECT_REASON_ROUTE_CHANGED);
        assertTrue(disconnects.isEmpty());
        assertEquals("Living Room", sessionManager.getConnectedGroupName());
    }
}
