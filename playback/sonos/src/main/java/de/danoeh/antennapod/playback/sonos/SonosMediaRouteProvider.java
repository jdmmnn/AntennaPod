package de.danoeh.antennapod.playback.sonos;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.IntentFilter;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;
import androidx.mediarouter.app.MediaRouteDialogFactory;
import androidx.mediarouter.media.MediaRouteDescriptor;
import androidx.mediarouter.media.MediaRouteDiscoveryRequest;
import androidx.mediarouter.media.MediaRouteProvider;
import androidx.mediarouter.media.MediaRouteProviderDescriptor;
import androidx.mediarouter.media.MediaRouteSelector;
import androidx.mediarouter.media.MediaRouter;
import de.danoeh.antennapod.storage.preferences.SonosPreferences;

import java.util.ArrayList;
import java.util.List;

public final class SonosMediaRouteProvider extends MediaRouteProvider {
    public static final String CATEGORY_SONOS = "de.danoeh.antennapod.playback.sonos.CATEGORY_SONOS";
    private static final long REDISCOVER_AFTER_MS = 10000;

    @SuppressLint("StaticFieldLeak")
    @Nullable
    private static SonosMediaRouteProvider instance;

    private final SonosSessionManager sessionManager;
    private final SonosDiscovery discovery;
    private List<SonosGroup> groups;
    private boolean discovering;
    private boolean searchCompleted;
    private long lastDiscoveryTime;

    private SonosMediaRouteProvider(Context context) {
        this(context, SonosSessionManager.getInstance(context));
    }

    @VisibleForTesting
    SonosMediaRouteProvider(Context context, SonosSessionManager sessionManager) {
        super(context);
        this.sessionManager = sessionManager;
        discovery = SonosDiscovery.create(context, sessionManager.getClient());
        groups = SonosDiscovery.getCachedGroups();
        sessionManager.addObserver(this::publishRoutes);
        publishRoutes();
    }

    public static void registerIfEnabled(Context context) {
        Context appContext = context.getApplicationContext();
        MediaRouter router = MediaRouter.getInstance(appContext);
        if (SonosPreferences.isEnabled()) {
            if (instance == null) {
                instance = new SonosMediaRouteProvider(appContext);
                router.addProvider(instance);
            }
        } else if (instance != null) {
            router.removeProvider(instance);
            instance = null;
        }
    }

    static boolean isSearching() {
        SonosMediaRouteProvider provider = instance;
        return provider != null && provider.isSearchPending();
    }

    boolean isSearchPending() {
        return discovering || !searchCompleted;
    }

    public static MediaRouteSelector selector() {
        return new MediaRouteSelector.Builder().addControlCategory(CATEGORY_SONOS).build();
    }

    public static MediaRouteDialogFactory dialogFactory() {
        return SonosRouteDialogFactory.getInstance();
    }

    @Override
    public void onDiscoveryRequestChanged(@Nullable MediaRouteDiscoveryRequest request) {
        if (request == null || !request.getSelector().hasControlCategory(CATEGORY_SONOS)) {
            return;
        }
        long now = System.currentTimeMillis();
        if (discovering || now - lastDiscoveryTime < REDISCOVER_AFTER_MS) {
            return;
        }
        discovering = true;
        lastDiscoveryTime = now;
        discovery.start(found -> {
            discovering = false;
            searchCompleted = true;
            groups = found;
            publishRoutes();
        });
    }

    private void publishRoutes() {
        IntentFilter filter = new IntentFilter();
        filter.addCategory(CATEGORY_SONOS);
        SonosGroup connected = sessionManager.getConnectedGroup();
        List<SonosGroup> visible = new ArrayList<>(groups);
        if (connected != null && !visible.contains(connected)) {
            visible.add(connected);
        }
        String lastGroupId = SonosPreferences.getLastGroupId();
        for (int i = 1; i < visible.size(); i++) {
            if (visible.get(i).getGroupId().equals(lastGroupId)) {
                visible.add(0, visible.remove(i));
                break;
            }
        }
        MediaRouteProviderDescriptor.Builder builder = new MediaRouteProviderDescriptor.Builder();
        for (SonosGroup group : visible) {
            boolean isConnected = group.equals(connected);
            builder.addRoute(new MediaRouteDescriptor.Builder(group.getRouteId(), group.getName())
                    .addControlFilter(filter)
                    .setPlaybackType(MediaRouter.RouteInfo.PLAYBACK_TYPE_REMOTE)
                    .setDeviceType(MediaRouter.RouteInfo.DEVICE_TYPE_SPEAKER)
                    .setVolumeHandling(MediaRouter.RouteInfo.PLAYBACK_VOLUME_VARIABLE)
                    .setVolumeMax(100)
                    .setVolume(isConnected ? sessionManager.getVolume() : 0)
                    .setConnectionState(isConnected ? MediaRouter.RouteInfo.CONNECTION_STATE_CONNECTED
                            : MediaRouter.RouteInfo.CONNECTION_STATE_DISCONNECTED)
                    .setEnabled(true)
                    .build());
        }
        setDescriptor(builder.build());
    }

    @Nullable
    private SonosGroup findGroup(String routeId) {
        for (SonosGroup group : groups) {
            if (group.getRouteId().equals(routeId)) {
                return group;
            }
        }
        SonosGroup connected = sessionManager.getConnectedGroup();
        if (connected != null && connected.getRouteId().equals(routeId)) {
            return connected;
        }
        return null;
    }

    @Nullable
    @Override
    public RouteController onCreateRouteController(@NonNull String routeId) {
        return new SonosRouteController(routeId);
    }

    private class SonosRouteController extends RouteController {
        private final String routeId;

        SonosRouteController(String routeId) {
            this.routeId = routeId;
        }

        @Override
        public void onSelect() {
            SonosGroup group = findGroup(routeId);
            if (group != null) {
                sessionManager.connect(group);
            }
        }

        @Override
        public void onUnselect(int reason) {
            SonosGroup connected = sessionManager.getConnectedGroup();
            if (connected == null || !connected.getRouteId().equals(routeId)) {
                return;
            }
            boolean stopRemote = reason == MediaRouter.UNSELECT_REASON_STOPPED
                    || reason == MediaRouter.UNSELECT_REASON_ROUTE_CHANGED;
            sessionManager.disconnect(stopRemote);
        }

        @Override
        public void onSetVolume(int volume) {
            sessionManager.setVolume(volume);
        }

        @Override
        public void onUpdateVolume(int delta) {
            sessionManager.updateVolume(delta);
        }
    }
}
