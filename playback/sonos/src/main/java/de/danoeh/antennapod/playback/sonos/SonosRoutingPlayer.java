package de.danoeh.antennapod.playback.sonos;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.OptIn;
import androidx.media3.common.C;
import androidx.media3.common.DeviceInfo;
import androidx.media3.common.ForwardingSimpleBasePlayer;
import androidx.media3.common.MediaItem;
import androidx.media3.common.Player;
import androidx.media3.common.PlayerTransferState;
import androidx.media3.common.util.UnstableApi;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import de.danoeh.antennapod.event.MessageEvent;
import org.greenrobot.eventbus.EventBus;

import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

@OptIn(markerClass = UnstableApi.class)
public class SonosRoutingPlayer extends ForwardingSimpleBasePlayer
        implements SonosSessionManager.Listener, SonosPlayer.Listener {
    static final long CAST_HANDOVER_TIMEOUT_MS = 3000;

    interface SonosPlayerFactory {
        SonosPlayer create(SonosGroup group);
    }

    public interface MediaItemResolver {
        @Nullable
        MediaItem resolve(long mediaId);
    }

    private final Context context;
    private final Player localPlayer;
    private final SonosSessionManager sessionManager;
    private final SonosPlayerFactory playerFactory;
    @Nullable
    private final MediaItemResolver resolver;
    private final Executor resolverExecutor;
    private final Handler handler;
    @Nullable
    private String syncedMediaId;
    @Nullable
    private SonosPlayer sonosPlayer;
    @Nullable
    private Runnable pendingConnect;
    @Nullable
    private Player.Listener castHandoverListener;
    private final Player.Listener volumeListener = new Player.Listener() {
        @Override
        public void onDeviceVolumeChanged(int volume, boolean muted) {
            sessionManager.onVolumeChanged(volume);
        }
    };

    SonosRoutingPlayer(Context context, Player localPlayer, SonosSessionManager sessionManager,
                       SonosPlayerFactory playerFactory) {
        this(context, localPlayer, sessionManager, playerFactory, null, Runnable::run);
    }

    SonosRoutingPlayer(Context context, Player localPlayer, SonosSessionManager sessionManager,
                       SonosPlayerFactory playerFactory, @Nullable MediaItemResolver resolver,
                       Executor resolverExecutor) {
        super(localPlayer);
        this.context = context;
        this.localPlayer = localPlayer;
        this.sessionManager = sessionManager;
        this.playerFactory = playerFactory;
        this.resolver = resolver;
        this.resolverExecutor = resolverExecutor;
        this.handler = new Handler(localPlayer.getApplicationLooper());
        sessionManager.setListener(this);
    }

    public static Player wrap(Player localPlayer, Context context, MediaItemResolver resolver) {
        SonosSessionManager sessionManager = SonosSessionManager.getInstance(context);
        Executor executor = Executors.newSingleThreadExecutor();
        Looper looper = localPlayer.getApplicationLooper();
        return new SonosRoutingPlayer(context.getApplicationContext(), localPlayer, sessionManager,
                group -> new SonosPlayer(group, sessionManager.getClient(), executor, looper), resolver,
                Executors.newSingleThreadExecutor());
    }

    @Override
    public void onConnect(SonosGroup group, @Nullable SonosSessionManager.SyncInfo syncInfo) {
        cancelPendingConnect();
        Runnable connect = () -> {
            pendingConnect = null;
            switchToSonos(group, syncInfo);
        };
        if (localPlayer.getDeviceInfo().playbackType != DeviceInfo.PLAYBACK_TYPE_REMOTE) {
            connect.run();
            return;
        }
        pendingConnect = connect;
        castHandoverListener = new Player.Listener() {
            @Override
            public void onDeviceInfoChanged(@NonNull DeviceInfo deviceInfo) {
                if (deviceInfo.playbackType != DeviceInfo.PLAYBACK_TYPE_REMOTE && pendingConnect == connect) {
                    handler.removeCallbacks(connect);
                    removeCastHandoverListener();
                    connect.run();
                }
            }
        };
        localPlayer.addListener(castHandoverListener);
        handler.postDelayed(() -> {
            if (pendingConnect == connect) {
                removeCastHandoverListener();
                connect.run();
            }
        }, CAST_HANDOVER_TIMEOUT_MS);
    }

    private void switchToSonos(SonosGroup group, @Nullable SonosSessionManager.SyncInfo syncInfo) {
        if (syncInfo == null && localPlayer.getCurrentMediaItem() != null) {
            SonosPlayable.Reason reason = SonosPlayable.check(localPlayer.getCurrentMediaItem());
            if (reason != null) {
                sessionManager.onPlayableRejected(reason);
                return;
            }
        }
        if (sonosPlayer != null) {
            switchToLocal(false, false);
        }
        SonosPlayer sonos = playerFactory.create(group);
        sonos.setListener(this);
        sonos.addListener(volumeListener);
        if (syncInfo != null) {
            long mediaId = syncInfo.mediaId;
            MediaItem item = SonosDidl.toMediaItem(syncInfo.didl, syncInfo.status.getTrackUri(), mediaId);
            syncedMediaId = item.mediaId;
            localPlayer.stop();
            sonos.attach(item, syncInfo.status, mediaId);
        } else {
            boolean hadMedia = localPlayer.getCurrentMediaItem() != null;
            boolean wasIdle = localPlayer.getPlaybackState() == STATE_IDLE;
            if (hadMedia) {
                PlayerTransferState.fromPlayer(localPlayer).setToPlayer(sonos);
                if (!wasIdle) {
                    sonos.prepare();
                }
            }
            localPlayer.stop();
        }
        sonosPlayer = sonos;
        setPlayer(sonos);
    }

    @Override
    public void onDisconnect(boolean stopRemote) {
        cancelPendingConnect();
        switchToLocal(stopRemote, false);
    }

    @Override
    public void onSetVolume(int volume) {
        if (sonosPlayer != null) {
            sonosPlayer.setDeviceVolume(volume, 0);
        }
    }

    @Override
    public void onUpdateVolume(int delta) {
        if (sonosPlayer != null) {
            sonosPlayer.setDeviceVolume(sonosPlayer.getDeviceVolume() + delta, 0);
        }
    }

    @Override
    public void onSessionEnded(SonosPlayer.EndReason reason) {
        if (sonosPlayer == null) {
            return;
        }
        boolean resumeLocally = reason == SonosPlayer.EndReason.ERROR && sonosPlayer.getPlayWhenReady();
        switchToLocal(false, resumeLocally);
        sessionManager.onSessionEnded();
        if (reason == SonosPlayer.EndReason.ERROR) {
            EventBus.getDefault().post(new MessageEvent(context.getString(R.string.sonos_cannot_play,
                    context.getString(R.string.sonos_reason_load_failed))));
        } else if (reason == SonosPlayer.EndReason.TAKEOVER) {
            EventBus.getDefault().post(new MessageEvent(context.getString(R.string.sonos_session_taken_over)));
        } else if (reason == SonosPlayer.EndReason.UNREACHABLE) {
            EventBus.getDefault().post(new MessageEvent(context.getString(R.string.sonos_session_unreachable)));
        }
    }

    private void switchToLocal(boolean stopRemote, boolean playWhenReady) {
        SonosPlayer sonos = sonosPlayer;
        if (sonos == null) {
            return;
        }
        sonosPlayer = null;
        final MediaItem item = sonos.getCurrentMediaItem();
        final long position = sonos.getCurrentPosition();
        final String synced = syncedMediaId;
        syncedMediaId = null;
        sonos.setListener(null);
        sonos.removeListener(volumeListener);
        if (item != null) {
            localPlayer.setMediaItem(item, position == C.TIME_UNSET ? 0 : position);
            localPlayer.setPlayWhenReady(playWhenReady);
            localPlayer.prepare();
        }
        setPlayer(localPlayer);
        sonos.endSession(stopRemote);
        if (item != null && item.mediaId.equals(synced)) {
            resolveLocalItem(item.mediaId);
        }
    }

    private void resolveLocalItem(String mediaId) {
        if (resolver == null) {
            return;
        }
        long id;
        try {
            id = Long.parseLong(mediaId);
        } catch (NumberFormatException e) {
            return;
        }
        MediaItemResolver currentResolver = resolver;
        resolverExecutor.execute(() -> {
            MediaItem resolved = currentResolver.resolve(id);
            if (resolved == null) {
                return;
            }
            handler.post(() -> {
                MediaItem current = localPlayer.getCurrentMediaItem();
                if (sonosPlayer != null || current == null || !mediaId.equals(current.mediaId)) {
                    return;
                }
                boolean playWhenReady = localPlayer.getPlayWhenReady();
                localPlayer.setMediaItem(resolved, localPlayer.getCurrentPosition());
                localPlayer.setPlayWhenReady(playWhenReady);
                localPlayer.prepare();
            });
        });
    }

    private void cancelPendingConnect() {
        if (pendingConnect != null) {
            handler.removeCallbacksAndMessages(null);
            pendingConnect = null;
        }
        removeCastHandoverListener();
    }

    private void removeCastHandoverListener() {
        if (castHandoverListener != null) {
            localPlayer.removeListener(castHandoverListener);
            castHandoverListener = null;
        }
    }

    @NonNull
    @Override
    protected ListenableFuture<?> handleSetMediaItems(@NonNull List<MediaItem> mediaItems, int startIndex,
                                                      long startPositionMs) {
        if (sonosPlayer != null && !mediaItems.isEmpty()) {
            int index = startIndex != C.INDEX_UNSET ? startIndex : 0;
            MediaItem item = mediaItems.get(index);
            SonosPlayable.Reason reason = SonosPlayable.check(item);
            if (reason != null) {
                switchToLocal(true, false);
                sessionManager.onPlayableRejected(reason);
                return super.handleSetMediaItems(mediaItems, startIndex, startPositionMs);
            }
        }
        return super.handleSetMediaItems(mediaItems, startIndex, startPositionMs);
    }

    @NonNull
    @Override
    protected ListenableFuture<?> handleRelease() {
        cancelPendingConnect();
        sessionManager.setListener(null);
        SonosPlayer sonos = sonosPlayer;
        sonosPlayer = null;
        if (sonos != null) {
            sonos.setListener(null);
            sonos.endSession(false);
            sessionManager.onSessionEnded();
        }
        localPlayer.release();
        return Futures.immediateVoidFuture();
    }
}
