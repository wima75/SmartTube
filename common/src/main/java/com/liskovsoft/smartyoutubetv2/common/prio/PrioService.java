package com.liskovsoft.smartyoutubetv2.common.prio;

import android.annotation.SuppressLint;
import android.content.Context;
import android.util.Pair;

import androidx.annotation.Nullable;

import com.liskovsoft.mediaserviceinterfaces.data.MediaGroup;
import com.liskovsoft.mediaserviceinterfaces.data.MediaItem;
import com.liskovsoft.sharedutils.helpers.Helpers;
import com.liskovsoft.sharedutils.mylogger.Log;
import com.liskovsoft.sharedutils.rx.RxHelper;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.SimpleMediaItem;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video;
import com.liskovsoft.smartyoutubetv2.common.app.models.playback.service.VideoStateService;
import com.liskovsoft.smartyoutubetv2.common.app.models.playback.service.VideoStateService.State;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.BrowsePresenter;
import com.liskovsoft.smartyoutubetv2.common.prio.PrioData.Channel;
import com.liskovsoft.smartyoutubetv2.common.prio.PrioData.Entry;
import com.liskovsoft.youtubeapi.service.YouTubeServiceManager;

import io.reactivex.Observable;
import io.reactivex.android.schedulers.AndroidSchedulers;
import io.reactivex.disposables.Disposable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Fork: prio playback. Next video = oldest open video of the channel with the highest priority.<br/>
 * New videos are fetched from the channels' uploads lists (no sign in needed).
 */
public class PrioService {
    private static final String TAG = PrioService.class.getSimpleName();
    /**
     * Sidebar section id. Must be below SidebarService.RESERVED_ID and must not collide with MediaGroup.TYPE_*
     */
    public static final int TYPE_PRIO = 90;
    private static final long SYNC_INTERVAL_MS = 10 * 60 * 1_000;
    private static final int MAX_PARALLEL_REQUESTS = 4;
    private static final float WATCHED_PERCENT = 90;
    @SuppressLint("StaticFieldLeak")
    private static PrioService sInstance;
    private final Context mContext;
    private final PrioData mData;
    private Disposable mSyncAction;
    private Disposable mAddChannelsAction;
    private long mLastSyncMs;

    private PrioService(Context context) {
        mContext = context;
        mData = PrioData.instance(context);
    }

    public static PrioService instance(Context context) {
        if (sInstance == null) {
            sInstance = new PrioService(context.getApplicationContext());
        }

        return sInstance;
    }

    public PrioData getData() {
        return mData;
    }

    // region Channels

    public boolean isPrioChannel(String channelId) {
        return mData.containsChannel(channelId);
    }

    public void addChannel(String channelId, String name, String cardImageUrl) {
        mData.addChannel(channelId, name, cardImageUrl);
        sync(true); // remember the existing videos as watched
        notifyChanged();
    }

    public interface OnChannelsAdded {
        void onChannelsAdded(int added);
    }

    /**
     * Add many channels at once (e.g. all subscriptions). Known channels are skipped.<br/>
     * The subscriptions list has no channel ids. They are taken from the channel's uploads.
     */
    public void addChannels(List<MediaItem> items, OnChannelsAdded callback) {
        RxHelper.disposeActions(mAddChannelsAction);

        List<Observable<Pair<MediaItem, String>>> requests = new ArrayList<>();

        for (MediaItem item : items) {
            if (item != null) {
                requests.add(createChannelIdRequest(item));
            }
        }

        mAddChannelsAction = Observable.concatEager(requests, MAX_PARALLEL_REQUESTS, 1)
                .toList()
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(
                        result -> callback.onChannelsAdded(addChannelsInt(result)),
                        error -> {
                            Log.e(TAG, "Add channels error: %s", error.getMessage());
                            callback.onChannelsAdded(0);
                        }
                );
    }

    private int addChannelsInt(List<Pair<MediaItem, String>> channels) {
        int added = 0;

        for (Pair<MediaItem, String> channel : channels) {
            MediaItem item = channel.first;
            String channelId = channel.second;

            if (mData.containsChannel(channelId)) {
                mData.updateChannelInfo(channelId, item.getTitle(), item.getCardImageUrl());
            } else {
                mData.addChannel(channelId, item.getTitle(), item.getCardImageUrl());
                added++;
            }
        }

        if (added > 0) {
            sync(true);
            notifyChanged();
        }

        return added;
    }

    private Observable<Pair<MediaItem, String>> createChannelIdRequest(MediaItem item) {
        if (item.getChannelId() != null) {
            return Observable.just(new Pair<>(item, item.getChannelId()));
        }

        return YouTubeServiceManager.instance().getContentService().getGroupObserve(item)
                .flatMap(group -> {
                    String channelId = findChannelId(group);
                    return channelId != null ? Observable.just(new Pair<>(item, channelId)) : Observable.<Pair<MediaItem, String>>empty();
                })
                .onErrorResumeNext(error -> {
                    Log.e(TAG, "Can't load channel id %s: %s", item.getTitle(), error.getMessage());
                    return Observable.empty();
                });
    }

    @Nullable
    private static String findChannelId(MediaGroup group) {
        if (group == null || group.getMediaItems() == null) {
            return null;
        }

        for (MediaItem item : group.getMediaItems()) {
            if (item != null && item.getChannelId() != null) {
                return item.getChannelId();
            }
        }

        return group.getChannelId();
    }

    public void removeChannel(String channelId) {
        mData.removeChannel(channelId);
        notifyChanged();
    }

    public void setTier(String channelId, int tier) {
        mData.setTier(channelId, tier);
        notifyChanged();
    }

    public void moveChannel(String channelId, String targetChannelId) {
        mData.moveChannel(channelId, targetChannelId);
        notifyChanged();
    }

    /**
     * Apply the result of the web editor: new order and tiers, channels that are missing get removed
     */
    public void setChannels(List<String> channelIds, Map<String, Integer> tiers) {
        mData.setChannels(channelIds, tiers);
        mData.persistNow();
        notifyChanged();
    }

    // endregion

    // region Queue

    /**
     * Open videos in playback order
     */
    public List<Video> getQueue() {
        List<Video> result = new ArrayList<>();

        for (Entry entry : getOpenEntries()) {
            result.add(toVideo(entry));
        }

        return result;
    }

    /**
     * Used by the sidebar section: returns the cached queue and refreshes it in the background
     */
    public List<Video> getQueueAndSync() {
        sync(false);
        return getQueue();
    }

    public boolean isPrioVideo(Video video) {
        return video != null && mData.getEntry(video.videoId) != null;
    }

    /**
     * @return next prio video or null if the current video isn't a prio video or nothing is open
     */
    @Nullable
    public Video getNext(Video current) {
        if (!isPrioVideo(current)) {
            return null;
        }

        for (Entry entry : getOpenEntries()) {
            if (!entry.videoId.equals(current.videoId)) {
                return toVideo(entry);
            }
        }

        return null;
    }

    public void markWatched(Video video, boolean watched) {
        if (!isPrioVideo(video)) {
            return;
        }

        Entry entry = mData.getEntry(video.videoId);

        if (entry != null && entry.isOpen() == watched) {
            mData.setWatched(video.videoId, watched);
            notifyChanged();
        }
    }

    /**
     * Mark as watched if the user has seen most of the video
     */
    public void markWatchedIfNeeded(Video video, long positionMs, long durationMs) {
        if (durationMs <= 0 || positionMs <= 0) {
            return;
        }

        if (positionMs * 100f / durationMs >= WATCHED_PERCENT) {
            markWatched(video, true);
        }
    }

    private List<Entry> getOpenEntries() {
        List<Entry> result = new ArrayList<>();

        for (Entry entry : mData.getEntries()) {
            if (entry.isOpen() && mData.containsChannel(entry.channelId)) {
                result.add(entry);
            }
        }

        Collections.sort(result, (o1, o2) -> {
            int result1 = mData.getTier(o1.channelId) - mData.getTier(o2.channelId);
            if (result1 == 0) {
                result1 = mData.indexOf(o1.channelId) - mData.indexOf(o2.channelId);
            }
            return result1 != 0 ? result1 : Long.compare(o1.orderMs, o2.orderMs);
        });

        return result;
    }

    private Video toVideo(Entry entry) {
        Video video = new Video();
        video.videoId = entry.videoId;
        video.channelId = entry.channelId;
        video.title = entry.title;
        video.author = entry.author;
        video.secondTitle = entry.secondTitle;
        video.cardImageUrl = entry.cardImageUrl;
        video.bgImageUrl = entry.cardImageUrl;
        video.badge = entry.badge;

        State state = VideoStateService.instance(mContext).getByVideoId(entry.videoId);
        if (state != null && state.durationMs > 0) {
            video.percentWatched = state.positionMs * 100f / state.durationMs;
        }

        return video;
    }

    // endregion

    // region Sync

    public void sync(boolean force) {
        if (RxHelper.isAnyActionRunning(mSyncAction)) {
            return;
        }

        if (!force && System.currentTimeMillis() - mLastSyncMs < SYNC_INTERVAL_MS) {
            return;
        }

        List<Channel> channels = mData.getChannels();

        if (channels.isEmpty()) {
            return;
        }

        mLastSyncMs = System.currentTimeMillis();

        List<Observable<Pair<String, MediaGroup>>> requests = new ArrayList<>();

        for (Channel channel : channels) {
            requests.add(createRequest(channel.channelId));
        }

        mSyncAction = Observable.merge(requests, MAX_PARALLEL_REQUESTS)
                .subscribe(
                        result -> merge(result.first, result.second),
                        error -> Log.e(TAG, "Sync error: %s", error.getMessage()),
                        this::onSyncFinished
                );
    }

    /**
     * Uploads of the channel (newest first). Works without sign in.
     */
    private Observable<Pair<String, MediaGroup>> createRequest(String channelId) {
        Video channel = new Video();
        channel.channelId = channelId;

        return YouTubeServiceManager.instance().getContentService().getGroupObserve(SimpleMediaItem.from(channel))
                .map(group -> new Pair<>(channelId, group))
                .onErrorResumeNext(error -> {
                    Log.e(TAG, "Can't load channel %s: %s", channelId, error.getMessage());
                    return Observable.empty();
                });
    }

    private void merge(String channelId, MediaGroup group) {
        Channel channel = mData.getChannel(channelId);

        if (channel == null || group == null || group.getMediaItems() == null || group.getMediaItems().isEmpty()) {
            return;
        }

        long now = System.currentTimeMillis();
        // Signed in: the watch progress comes from YouTube (resume overlay of the items)
        boolean isSigned = YouTubeServiceManager.instance().getSignInService().isSigned();
        // First fetch after adding the channel: existing videos count as watched (signed in: only the ones watched on YouTube)
        int defaultStatus = channel.initialized || isSigned ? Entry.STATUS_OPEN : Entry.STATUS_WATCHED;
        List<MediaItem> items = group.getMediaItems();

        // Channel may have been added without a name (only the id was known)
        mData.updateChannelInfo(channelId, findChannelName(items), null);
        if (!hasName(channel)) {
            loadChannelName(channelId, items.get(0));
        }

        for (int i = 0; i < items.size(); i++) {
            MediaItem item = items.get(i);

            if (item == null || item.getVideoId() == null || item.isUpcoming() || item.isLive() || item.isShorts()) {
                continue;
            }

            String author = item.getAuthor() != null ? item.getAuthor() : channel.name;
            boolean watchedOnYouTube = isSigned && item.getPercentWatched() >= WATCHED_PERCENT;
            int status = watchedOnYouTube ? Entry.STATUS_WATCHED : defaultStatus;

            // List is sorted newest first. Lower position = newer.
            mData.putEntry(new Entry(item.getVideoId(), channelId, item.getTitle(), Helpers.toString(item.getSecondTitle()),
                    author, item.getCardImageUrl(), item.getBadgeText(), now - i, now, status));

            // Known entry keeps its status in putEntry(). Take over the progress from other devices.
            if (watchedOnYouTube) {
                mData.setWatched(item.getVideoId(), true);
            }
        }

        mData.setInitialized(channel);
    }

    private static boolean hasName(Channel channel) {
        return channel.name != null && !channel.name.isEmpty() && !channel.name.equals(channel.channelId);
    }

    /**
     * Uploads lists often have no author. Take it from the metadata of a video.
     */
    private void loadChannelName(String channelId, MediaItem item) {
        if (item == null || item.getVideoId() == null) {
            return;
        }

        YouTubeServiceManager.instance().getMediaItemService().getMetadataObserve(item.getVideoId())
                .subscribe(
                        metadata -> {
                            mData.updateChannelInfo(channelId, metadata.getAuthor(), null);
                            notifyChanged();
                        },
                        error -> Log.e(TAG, "Can't load channel name %s: %s", channelId, error.getMessage())
                );
    }

    @Nullable
    private static String findChannelName(List<MediaItem> items) {
        for (MediaItem item : items) {
            if (item != null && item.getAuthor() != null && !item.getAuthor().isEmpty()) {
                return item.getAuthor();
            }
        }

        return null;
    }

    private void onSyncFinished() {
        mData.prune();
        notifyChanged();
    }

    // endregion

    private void notifyChanged() {
        BrowsePresenter presenter = BrowsePresenter.instance(mContext);

        if (presenter.getCurrentSection() != null && presenter.getCurrentSection().getId() == TYPE_PRIO) {
            presenter.refresh(false);
        }
    }
}
