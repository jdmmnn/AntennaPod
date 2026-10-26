package de.danoeh.antennapod.playback.sonos;

import android.annotation.SuppressLint;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;
import androidx.mediarouter.media.MediaRouter;
import de.danoeh.antennapod.event.MessageEvent;
import de.danoeh.antennapod.event.playback.SonosSessionEvent;
import de.danoeh.antennapod.storage.preferences.SonosPreferences;
import org.greenrobot.eventbus.EventBus;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

public final class SonosSessionManager {
    private static final String TAG = "SonosSessionManager";

    enum Mode {
        FRESH,
        SYNC
    }

    static final class SyncInfo {
        final long mediaId;
        final String didl;
        final SonosStatus status;

        SyncInfo(long mediaId, String didl, SonosStatus status) {
            this.mediaId = mediaId;
            this.didl = didl;
            this.status = status;
        }
    }

    interface Listener {
        void onConnect(SonosGroup group, @Nullable SyncInfo syncInfo);

        void onDisconnect(boolean stopRemote);

        void onSetVolume(int volume);
    }

    @SuppressLint("StaticFieldLeak")
    private static SonosSessionManager instance;

    private final Context context;
    private final SonosSoapClient client;
    private final Executor executor;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final List<Runnable> observers = new ArrayList<>();
    @Nullable
    private Listener listener;
    @Nullable
    private SonosGroup connectedGroup;
    @Nullable
    private SyncInfo pendingSync;
    private boolean pendingConnect;
    private int connectSequence;
    private int volume;

    @VisibleForTesting
    SonosSessionManager(Context context, SonosSoapClient client, Executor executor) {
        this.context = context;
        this.client = client;
        this.executor = executor;
    }

    public static synchronized SonosSessionManager getInstance(Context context) {
        if (instance == null) {
            instance = new SonosSessionManager(context.getApplicationContext(), new SonosSoapClient(),
                    Executors.newSingleThreadExecutor());
        }
        return instance;
    }

    SonosSoapClient getClient() {
        return client;
    }

    @Nullable
    public String getConnectedGroupName() {
        return connectedGroup != null ? connectedGroup.getName() : null;
    }

    @Nullable
    SonosGroup getConnectedGroup() {
        return connectedGroup;
    }

    void setListener(@Nullable Listener listener) {
        this.listener = listener;
        if (listener != null && pendingConnect && connectedGroup != null) {
            pendingConnect = false;
            listener.onConnect(connectedGroup, pendingSync);
            pendingSync = null;
        }
    }

    void addObserver(Runnable observer) {
        observers.add(observer);
    }

    boolean connect(SonosGroup group) {
        if (!SonosPreferences.isEnabled()) {
            return false;
        }
        if (group.equals(connectedGroup)) {
            return true;
        }
        if (connectedGroup != null) {
            disconnect(false);
        }
        int sequence = ++connectSequence;
        executor.execute(() -> {
            SyncInfo syncInfo = null;
            IOException failure = null;
            try {
                syncInfo = probe(group);
            } catch (IOException e) {
                failure = e;
            }
            SyncInfo finalSync = syncInfo;
            IOException finalFailure = failure;
            handler.post(() -> onProbeFinished(sequence, group, finalSync, finalFailure));
        });
        return true;
    }

    @Nullable
    private SyncInfo probe(SonosGroup group) throws IOException {
        String host = group.getCoordinatorHost();
        int port = group.getCoordinatorPort();
        String transportInfo = client.getTransportInfo(host, port);
        String positionInfo = client.getPositionInfo(host, port);
        String mediaInfo = client.getMediaInfo(host, port);
        String groupVolume = client.getGroupVolume(host, port);
        SonosStatus status = SonosXmlParser.parseStatus(transportInfo, positionInfo, mediaInfo, groupVolume,
                SystemClock.elapsedRealtime());
        boolean running = status.getTransportState() == SonosStatus.TransportState.PLAYING
                || status.getTransportState() == SonosStatus.TransportState.PAUSED_PLAYBACK;
        if (status.getMediaId() == null || !running) {
            return null;
        }
        String didl = SonosXmlParser.extractValue(mediaInfo, "CurrentURIMetaData");
        return new SyncInfo(status.getMediaId(), didl != null ? didl : "", status);
    }

    private void onProbeFinished(int sequence, SonosGroup group, @Nullable SyncInfo syncInfo,
                                 @Nullable IOException failure) {
        if (sequence != connectSequence) {
            return;
        }
        if (failure != null) {
            Log.w(TAG, "Unable to connect to " + group.getName() + ": " + failure.getMessage());
            EventBus.getDefault().post(new MessageEvent(context.getString(R.string.sonos_session_unreachable)));
            unselectRoute();
            return;
        }
        connectedGroup = group;
        SonosPreferences.setLastGroupId(group.getGroupId());
        notifyChanged();
        if (listener != null) {
            listener.onConnect(group, syncInfo);
        } else {
            pendingConnect = true;
            pendingSync = syncInfo;
        }
    }

    void disconnect(boolean stopRemote) {
        connectSequence++;
        if (connectedGroup == null) {
            return;
        }
        connectedGroup = null;
        pendingConnect = false;
        pendingSync = null;
        notifyChanged();
        if (listener != null) {
            listener.onDisconnect(stopRemote);
        }
    }

    int getVolume() {
        return volume;
    }

    void onVolumeChanged(int volume) {
        if (this.volume != volume) {
            this.volume = volume;
            notifyObservers();
        }
    }

    void setVolume(int volume) {
        if (listener != null && connectedGroup != null) {
            listener.onSetVolume(volume);
        }
    }

    void onSessionEnded() {
        if (connectedGroup == null) {
            return;
        }
        connectedGroup = null;
        notifyChanged();
        unselectRoute();
    }

    private void unselectRoute() {
        try {
            MediaRouter router = MediaRouter.getInstance(context);
            if (router.getSelectedRoute().getId().contains(SonosGroup.ROUTE_ID_PREFIX)) {
                router.unselect(MediaRouter.UNSELECT_REASON_UNKNOWN);
            }
        } catch (RuntimeException e) {
            Log.w(TAG, "Unable to unselect route: " + e.getMessage());
        }
    }

    private void notifyChanged() {
        EventBus.getDefault().post(new SonosSessionEvent(getConnectedGroupName()));
        notifyObservers();
    }

    private void notifyObservers() {
        for (Runnable observer : new ArrayList<>(observers)) {
            observer.run();
        }
    }
}
