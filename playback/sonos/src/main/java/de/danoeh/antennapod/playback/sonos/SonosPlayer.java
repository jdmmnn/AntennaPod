package de.danoeh.antennapod.playback.sonos;

import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.OptIn;
import androidx.media3.common.C;
import androidx.media3.common.DeviceInfo;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.SimpleBasePlayer;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.common.util.Util;
import com.google.common.collect.ImmutableList;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.SettableFuture;
import de.danoeh.antennapod.playback.base.MediaItemAdapter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Executor;

@OptIn(markerClass = UnstableApi.class)
class SonosPlayer extends SimpleBasePlayer {
    private static final String TAG = "SonosPlayer";
    static final long POLL_PLAYING_MS = 1000;
    static final long POLL_PAUSED_MS = 5000;
    static final long UNREACHABLE_MS = 10000;
    static final long ENDED_TOLERANCE_MS = 3000;
    static final int VOLUME_STEP = 2;
    private static final int ERROR_NOT_COORDINATOR = 800;
    private static final DeviceInfo DEVICE_INFO = new DeviceInfo.Builder(DeviceInfo.PLAYBACK_TYPE_REMOTE)
            .setMinVolume(0)
            .setMaxVolume(100)
            .build();
    private static final Commands COMMANDS = new Commands.Builder()
            .addAll(COMMAND_PLAY_PAUSE, COMMAND_PREPARE, COMMAND_STOP, COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM,
                    COMMAND_SEEK_BACK, COMMAND_SEEK_FORWARD, COMMAND_SET_MEDIA_ITEM, COMMAND_CHANGE_MEDIA_ITEMS,
                    COMMAND_GET_CURRENT_MEDIA_ITEM, COMMAND_GET_TIMELINE, COMMAND_GET_METADATA,
                    COMMAND_GET_DEVICE_VOLUME, COMMAND_SET_DEVICE_VOLUME_WITH_FLAGS,
                    COMMAND_ADJUST_DEVICE_VOLUME_WITH_FLAGS, COMMAND_RELEASE)
            .build();

    enum EndReason {
        TAKEOVER,
        UNREACHABLE,
        ERROR
    }

    interface Listener {
        void onSessionEnded(EndReason reason);
    }

    private final SonosSoapClient client;
    private final Executor executor;
    private final Handler handler;
    private final Runnable pollRunnable = this::poll;
    private volatile SonosGroup group;
    @Nullable
    private Listener listener;

    private List<MediaItem> items = Collections.emptyList();
    private boolean prepared;
    private boolean loaded;
    private boolean playWhenReady;
    private int playbackState = STATE_IDLE;
    private boolean ended;
    private boolean released;
    @Nullable
    private String expectedUri;
    private long expectedMediaId = C.INDEX_UNSET;
    private long positionMs;
    private long positionTimestamp;
    private boolean positionAdvancing;
    private long durationMs = C.TIME_UNSET;
    private int volume;
    @Nullable
    private SonosStatus.TransportState lastTransportState;
    private long failingSince = C.TIME_UNSET;
    private boolean topologyChecked;
    private int commandSequence;
    @Nullable
    private PlaybackException error;

    SonosPlayer(SonosGroup group, SonosSoapClient client, Executor executor, Looper looper) {
        super(looper);
        this.group = group;
        this.client = client;
        this.executor = executor;
        this.handler = new Handler(looper);
    }

    void setListener(@Nullable Listener listener) {
        this.listener = listener;
    }

    SonosGroup getGroup() {
        return group;
    }

    void attach(MediaItem item, SonosStatus status, long mediaId) {
        items = Collections.singletonList(item);
        prepared = true;
        loaded = true;
        expectedUri = status.getTrackUri();
        expectedMediaId = mediaId;
        applyStatus(status);
        invalidateState();
        schedulePoll(nextPollDelay());
    }

    void endSession(boolean stopRemote) {
        if (stopRemote && loaded) {
            SonosGroup current = group;
            executor.execute(() -> {
                try {
                    client.stop(current.getCoordinatorHost(), current.getCoordinatorPort());
                } catch (IOException e) {
                    Log.w(TAG, "Unable to stop Sonos: " + e.getMessage());
                }
            });
        }
        release();
    }

    @NonNull
    @Override
    protected State getState() {
        State.Builder builder = new State.Builder()
                .setAvailableCommands(COMMANDS)
                .setPlayWhenReady(playWhenReady, PLAY_WHEN_READY_CHANGE_REASON_REMOTE)
                .setDeviceInfo(DEVICE_INFO)
                .setDeviceVolume(volume)
                .setPlayerError(error)
                .setContentPositionMs(this::currentPositionMs);
        if (items.isEmpty()) {
            return builder.setPlaybackState(playbackState == STATE_ENDED ? STATE_ENDED : STATE_IDLE).build();
        }
        MediaItemData itemData = new MediaItemData.Builder(items.get(0).mediaId)
                .setMediaItem(items.get(0))
                .setDurationUs(durationMs == C.TIME_UNSET ? C.TIME_UNSET : Util.msToUs(durationMs))
                .setIsSeekable(true)
                .build();
        return builder.setPlaylist(ImmutableList.of(itemData))
                .setCurrentMediaItemIndex(0)
                .setPlaybackState(playbackState)
                .build();
    }

    @NonNull
    @Override
    protected ListenableFuture<?> handleSetMediaItems(@NonNull List<MediaItem> mediaItems, int startIndex,
                                                      long startPositionMs) {
        commandSequence++;
        ended = false;
        error = null;
        loaded = false;
        expectedUri = null;
        durationMs = C.TIME_UNSET;
        lastTransportState = null;
        if (mediaItems.isEmpty()) {
            items = Collections.emptyList();
            stopPolling();
            return Futures.immediateVoidFuture();
        }
        int index = startIndex == C.INDEX_UNSET ? 0 : Math.min(startIndex, mediaItems.size() - 1);
        items = Collections.singletonList(mediaItems.get(index));
        setPosition(startPositionMs == C.TIME_UNSET ? 0 : startPositionMs, false);
        if (prepared) {
            return load();
        }
        return Futures.immediateVoidFuture();
    }

    @NonNull
    @Override
    protected ListenableFuture<?> handlePrepare() {
        prepared = true;
        error = null;
        if (!items.isEmpty() && !loaded) {
            return load();
        }
        return Futures.immediateVoidFuture();
    }

    private ListenableFuture<?> load() {
        MediaItem item = items.get(0);
        String streamUrl = item.mediaMetadata.extras != null
                ? item.mediaMetadata.extras.getString(MediaItemAdapter.KEY_STREAM_URL) : null;
        if (streamUrl == null || !(streamUrl.startsWith("http://") || streamUrl.startsWith("https://"))) {
            fail(new IOException("No stream URL for Sonos"));
            return Futures.immediateVoidFuture();
        }
        final String didl = SonosDidl.build(item);
        expectedUri = streamUrl;
        expectedMediaId = parseMediaId(item.mediaId);
        loaded = true;
        playbackState = STATE_BUFFERING;
        long start = positionMs;
        boolean play = playWhenReady;
        return run(() -> {
            String host = group.getCoordinatorHost();
            int port = group.getCoordinatorPort();
            client.setAvTransportUri(host, port, streamUrl, didl);
            if (start > 0) {
                client.seek(host, port, start);
            }
            if (play) {
                client.play(host, port);
            }
        }, true);
    }

    @NonNull
    @Override
    protected ListenableFuture<?> handleSetPlayWhenReady(boolean playWhenReady) {
        commandSequence++;
        setPosition(currentPositionMs(), playWhenReady && playbackState == STATE_READY);
        this.playWhenReady = playWhenReady;
        if (!loaded) {
            return Futures.immediateVoidFuture();
        }
        if (playWhenReady && playbackState == STATE_ENDED) {
            ended = false;
            setPosition(0, false);
        }
        return run(() -> {
            if (playWhenReady) {
                client.play(group.getCoordinatorHost(), group.getCoordinatorPort());
            } else {
                client.pause(group.getCoordinatorHost(), group.getCoordinatorPort());
            }
        }, false);
    }

    @NonNull
    @Override
    protected ListenableFuture<?> handleSeek(int mediaItemIndex, long positionMs, int seekCommand) {
        commandSequence++;
        long target = Math.max(0, positionMs == C.TIME_UNSET ? 0 : positionMs);
        setPosition(target, playWhenReady && playbackState == STATE_READY);
        if (!loaded) {
            return Futures.immediateVoidFuture();
        }
        return run(() -> client.seek(group.getCoordinatorHost(), group.getCoordinatorPort(), target), false);
    }

    @NonNull
    @Override
    protected ListenableFuture<?> handleStop() {
        commandSequence++;
        setPosition(currentPositionMs(), false);
        final boolean wasLoaded = loaded;
        prepared = false;
        loaded = false;
        playbackState = STATE_IDLE;
        stopPolling();
        if (!wasLoaded) {
            return Futures.immediateVoidFuture();
        }
        return run(() -> client.stop(group.getCoordinatorHost(), group.getCoordinatorPort()), false);
    }

    @NonNull
    @Override
    protected ListenableFuture<?> handleRelease() {
        released = true;
        stopPolling();
        return Futures.immediateVoidFuture();
    }

    @NonNull
    @Override
    protected ListenableFuture<?> handleRemoveMediaItems(int fromIndex, int toIndex) {
        if (fromIndex <= 0 && toIndex >= items.size()) {
            commandSequence++;
            items = Collections.emptyList();
            loaded = false;
            expectedUri = null;
            stopPolling();
        }
        return Futures.immediateVoidFuture();
    }

    @NonNull
    @Override
    protected ListenableFuture<?> handleReplaceMediaItems(int fromIndex, int toIndex,
                                                          @NonNull List<MediaItem> mediaItems) {
        return handleSetMediaItems(mediaItems, 0, C.TIME_UNSET);
    }

    @NonNull
    @Override
    protected ListenableFuture<?> handleAddMediaItems(int index, @NonNull List<MediaItem> mediaItems) {
        if (items.isEmpty()) {
            return handleSetMediaItems(mediaItems, 0, C.TIME_UNSET);
        }
        return Futures.immediateVoidFuture();
    }

    @NonNull
    @Override
    protected ListenableFuture<?> handleSetDeviceVolume(int deviceVolume, int flags) {
        volume = Math.max(0, Math.min(100, deviceVolume));
        int target = volume;
        return run(() -> client.setGroupVolume(group.getCoordinatorHost(), group.getCoordinatorPort(), target),
                false);
    }

    @NonNull
    @Override
    protected ListenableFuture<?> handleIncreaseDeviceVolume(int flags) {
        return handleSetDeviceVolume(volume + VOLUME_STEP, flags);
    }

    @NonNull
    @Override
    protected ListenableFuture<?> handleDecreaseDeviceVolume(int flags) {
        return handleSetDeviceVolume(volume - VOLUME_STEP, flags);
    }

    private interface SoapCall {
        void run() throws IOException;
    }

    private ListenableFuture<?> run(SoapCall call, boolean failSession) {
        SettableFuture<Void> future = SettableFuture.create();
        executor.execute(() -> {
            try {
                call.run();
                handler.post(() -> {
                    if (!released) {
                        schedulePoll(POLL_PLAYING_MS / 2);
                    }
                    future.set(null);
                });
            } catch (IOException e) {
                Log.w(TAG, "Sonos command failed: " + e.getMessage());
                handler.post(() -> {
                    if (failSession) {
                        fail(e);
                    } else {
                        schedulePoll(0);
                    }
                    future.set(null);
                });
            }
        });
        return future;
    }

    private void fail(IOException e) {
        error = new PlaybackException("Unable to play on Sonos: " + e.getMessage(), e,
                PlaybackException.ERROR_CODE_REMOTE_ERROR);
        playbackState = STATE_IDLE;
        loaded = false;
        stopPolling();
        invalidateState();
        finishSession(EndReason.ERROR);
    }

    private void poll() {
        if (released || !loaded) {
            return;
        }
        int sequence = commandSequence;
        SonosGroup current = group;
        executor.execute(() -> {
            try {
                String host = current.getCoordinatorHost();
                int port = current.getCoordinatorPort();
                String transportInfo = client.getTransportInfo(host, port);
                String positionInfo = client.getPositionInfo(host, port);
                String groupVolume = client.getGroupVolume(host, port);
                SonosStatus status = SonosXmlParser.parseStatus(transportInfo, positionInfo, null, groupVolume,
                        SystemClock.elapsedRealtime());
                handler.post(() -> onPollSuccess(status, sequence));
            } catch (IOException e) {
                handler.post(() -> onPollFailure(e));
            }
        });
    }

    private void onPollSuccess(SonosStatus status, int sequence) {
        if (released || !loaded) {
            return;
        }
        failingSince = C.TIME_UNSET;
        topologyChecked = false;
        if (expectedUri != null && !expectedUri.equals(status.getTrackUri())) {
            verifyTakeover();
            return;
        }
        if (sequence != commandSequence) {
            schedulePoll(POLL_PLAYING_MS);
            return;
        }
        applyStatus(status);
        invalidateState();
        if (playbackState != STATE_ENDED) {
            schedulePoll(nextPollDelay());
        }
    }

    private void applyStatus(SonosStatus status) {
        long previousPosition = currentPositionMs();
        if (status.getGroupVolume() != SonosStatus.VOLUME_UNKNOWN) {
            volume = status.getGroupVolume();
        }
        if (status.getDurationMs() != C.TIME_UNSET) {
            durationMs = status.getDurationMs();
        }
        long statusPosition = status.getPositionMs();
        long age = Math.max(0, SystemClock.elapsedRealtime() - status.getTimestamp());
        switch (status.getTransportState()) {
            case PLAYING:
                ended = false;
                playbackState = STATE_READY;
                playWhenReady = true;
                if (statusPosition != C.TIME_UNSET) {
                    setPosition(statusPosition + age, true);
                }
                break;
            case PAUSED_PLAYBACK:
                ended = false;
                playbackState = STATE_READY;
                playWhenReady = false;
                if (statusPosition != C.TIME_UNSET) {
                    setPosition(statusPosition, false);
                }
                break;
            case TRANSITIONING:
                playbackState = STATE_BUFFERING;
                if (statusPosition != C.TIME_UNSET) {
                    setPosition(statusPosition, false);
                }
                break;
            case STOPPED:
                boolean wasRunning = lastTransportState == SonosStatus.TransportState.PLAYING
                        || lastTransportState == SonosStatus.TransportState.TRANSITIONING;
                if (ended || (wasRunning && durationMs != C.TIME_UNSET
                        && previousPosition >= durationMs - ENDED_TOLERANCE_MS)) {
                    ended = true;
                    playbackState = STATE_ENDED;
                    setPosition(durationMs, false);
                    stopPolling();
                } else {
                    playbackState = STATE_READY;
                    playWhenReady = false;
                    if (statusPosition != C.TIME_UNSET && statusPosition > 0) {
                        setPosition(statusPosition, false);
                    } else {
                        setPosition(previousPosition, false);
                    }
                }
                break;
            case NO_MEDIA_PRESENT:
            default:
                playbackState = STATE_IDLE;
                playWhenReady = false;
                break;
        }
        lastTransportState = status.getTransportState();
    }

    private void verifyTakeover() {
        SonosGroup current = group;
        long mediaId = expectedMediaId;
        executor.execute(() -> {
            Long remoteMediaId = null;
            String remoteUri = null;
            try {
                String mediaInfo = client.getMediaInfo(current.getCoordinatorHost(), current.getCoordinatorPort());
                remoteMediaId = SonosXmlParser.parseMediaId(mediaInfo);
                remoteUri = SonosXmlParser.extractValue(mediaInfo, "CurrentURI");
            } catch (IOException e) {
                Log.w(TAG, "Unable to verify takeover: " + e.getMessage());
            }
            Long finalMediaId = remoteMediaId;
            String finalUri = remoteUri;
            handler.post(() -> {
                if (released || !loaded) {
                    return;
                }
                if (finalMediaId != null && finalMediaId == mediaId && finalUri != null) {
                    expectedUri = finalUri;
                    schedulePoll(0);
                } else {
                    finishSession(EndReason.TAKEOVER);
                }
            });
        });
    }

    private void onPollFailure(IOException e) {
        if (released || !loaded) {
            return;
        }
        long now = SystemClock.elapsedRealtime();
        if (failingSince == C.TIME_UNSET) {
            failingSince = now;
        }
        if (now - failingSince >= UNREACHABLE_MS) {
            finishSession(EndReason.UNREACHABLE);
            return;
        }
        boolean notCoordinator = e instanceof SonosSoapException
                && ((SonosSoapException) e).getErrorCode() == ERROR_NOT_COORDINATOR;
        if (notCoordinator || !topologyChecked) {
            topologyChecked = true;
            refreshCoordinator();
        } else {
            schedulePoll(POLL_PLAYING_MS);
        }
    }

    private void refreshCoordinator() {
        SonosGroup current = group;
        executor.execute(() -> {
            List<SonosGroup> groups = null;
            for (String[] endpoint : topologyEndpoints(current)) {
                try {
                    groups = SonosXmlParser.parseZoneGroupState(
                            client.getZoneGroupState(endpoint[0], Integer.parseInt(endpoint[1])));
                    if (!groups.isEmpty()) {
                        break;
                    }
                } catch (IOException | NumberFormatException e) {
                    groups = null;
                }
            }
            List<SonosGroup> result = groups;
            handler.post(() -> onTopology(current, result));
        });
    }

    private static List<String[]> topologyEndpoints(SonosGroup current) {
        List<String[]> endpoints = new ArrayList<>();
        endpoints.add(new String[] {current.getCoordinatorHost(), String.valueOf(current.getCoordinatorPort())});
        for (SonosGroup cached : SonosDiscovery.getCachedGroups()) {
            if (!cached.getCoordinatorHost().equals(current.getCoordinatorHost())) {
                endpoints.add(new String[] {cached.getCoordinatorHost(), String.valueOf(cached.getCoordinatorPort())});
            }
        }
        return endpoints;
    }

    private void onTopology(SonosGroup previous, @Nullable List<SonosGroup> groups) {
        if (released || !loaded) {
            return;
        }
        if (groups == null || groups.isEmpty()) {
            schedulePoll(POLL_PLAYING_MS);
            return;
        }
        SonosGroup match = findGroup(previous, groups);
        if (match == null) {
            finishSession(EndReason.TAKEOVER);
            return;
        }
        group = match;
        schedulePoll(0);
    }

    @Nullable
    static SonosGroup findGroup(SonosGroup previous, List<SonosGroup> groups) {
        for (SonosGroup candidate : groups) {
            if (candidate.getGroupId().equals(previous.getGroupId())) {
                return candidate;
            }
        }
        for (SonosGroup candidate : groups) {
            if (candidate.getCoordinatorUuid().equals(previous.getCoordinatorUuid())) {
                return candidate;
            }
        }
        return null;
    }

    private void finishSession(EndReason reason) {
        stopPolling();
        loaded = false;
        Listener current = listener;
        if (current != null) {
            handler.post(() -> current.onSessionEnded(reason));
        }
    }

    private long nextPollDelay() {
        return playbackState == STATE_BUFFERING || (playbackState == STATE_READY && playWhenReady)
                ? POLL_PLAYING_MS : POLL_PAUSED_MS;
    }

    private void schedulePoll(long delayMs) {
        handler.removeCallbacks(pollRunnable);
        if (!released && loaded) {
            handler.postDelayed(pollRunnable, delayMs);
        }
    }

    private void stopPolling() {
        handler.removeCallbacks(pollRunnable);
    }

    private void setPosition(long position, boolean advancing) {
        positionMs = Math.max(0, position);
        positionTimestamp = SystemClock.elapsedRealtime();
        positionAdvancing = advancing;
    }

    private long currentPositionMs() {
        long position = positionMs;
        if (positionAdvancing) {
            position += SystemClock.elapsedRealtime() - positionTimestamp;
        }
        if (durationMs != C.TIME_UNSET) {
            position = Math.min(position, durationMs);
        }
        return position;
    }

    private static long parseMediaId(String mediaId) {
        try {
            return Long.parseLong(mediaId);
        } catch (NumberFormatException e) {
            return C.INDEX_UNSET;
        }
    }
}
