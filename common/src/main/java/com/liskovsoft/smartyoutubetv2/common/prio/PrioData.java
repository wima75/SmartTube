package com.liskovsoft.smartyoutubetv2.common.prio;

import android.annotation.SuppressLint;
import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.liskovsoft.sharedutils.helpers.Helpers;
import com.liskovsoft.smartyoutubetv2.common.prefs.AppPrefs;
import com.liskovsoft.smartyoutubetv2.common.prefs.AppPrefs.ProfileChangeListener;
import com.liskovsoft.smartyoutubetv2.common.utils.Utils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Fork: local storage of the prio channels (ordered, first = highest priority) and the known videos of these channels.
 */
public class PrioData implements ProfileChangeListener {
    private static final String PRIO_DATA = "prio_data";
    /**
     * Watched videos that are no longer in the channel's uploads list are dropped after this time
     */
    private static final long PRUNE_AFTER_MS = 30L * 24 * 60 * 60 * 1_000;
    @SuppressLint("StaticFieldLeak")
    private static PrioData sInstance;
    private final AppPrefs mPrefs;
    private final List<Channel> mChannels = new ArrayList<>();
    private final Map<String, Entry> mEntries = new HashMap<>();
    private final Runnable mPersistStateInt = this::persistStateInt;

    public static class Channel {
        public static final int TIER_HIGH = 0;
        public static final int TIER_NORMAL = 1;
        public static final int TIER_LOW = 2;
        public static final int[] TIERS = {TIER_HIGH, TIER_NORMAL, TIER_LOW};
        public final String channelId;
        public String name;
        public String cardImageUrl;
        /**
         * false until the first fetch. The videos of the first fetch count as watched (only new videos are added).
         */
        public boolean initialized;
        /**
         * Playback order: higher tier first, inside a tier the oldest open video first
         */
        public int tier;

        Channel(String channelId, String name, String cardImageUrl, boolean initialized, int tier) {
            this.channelId = channelId;
            this.name = name;
            this.cardImageUrl = cardImageUrl;
            this.initialized = initialized;
            this.tier = tier;
        }

        static Channel fromString(String spec) {
            String[] split = Helpers.splitObj(spec);

            if (split == null || split.length < 4 || Helpers.parseStr(split[0]) == null) {
                return null;
            }

            return new Channel(Helpers.parseStr(split[0]), Helpers.parseStr(split[1]), Helpers.parseStr(split[2]), Helpers.parseBoolean(split, 3),
                    Helpers.parseInt(split, 4, TIER_NORMAL));
        }

        @NonNull
        @Override
        public String toString() {
            return Helpers.mergeObj(channelId, name, cardImageUrl, initialized, tier);
        }
    }

    public static class Entry {
        public static final int STATUS_OPEN = 0;
        public static final int STATUS_WATCHED = 1;
        public final String videoId;
        public final String channelId;
        public String title;
        public String secondTitle;
        public String author;
        public String cardImageUrl;
        public String badge;
        /**
         * Sort key inside the channel (smaller = older). Upload dates aren't available, so the time of the first sight is used.
         */
        public long orderMs;
        public long lastSeenMs;
        public int status;

        Entry(String videoId, String channelId, String title, String secondTitle, String author, String cardImageUrl, String badge,
              long orderMs, long lastSeenMs, int status) {
            this.videoId = videoId;
            this.channelId = channelId;
            this.title = title;
            this.secondTitle = secondTitle;
            this.author = author;
            this.cardImageUrl = cardImageUrl;
            this.badge = badge;
            this.orderMs = orderMs;
            this.lastSeenMs = lastSeenMs;
            this.status = status;
        }

        static Entry fromString(String spec) {
            String[] split = Helpers.splitObj(spec);

            if (split == null || split.length < 10 || Helpers.parseStr(split[0]) == null || Helpers.parseStr(split[1]) == null) {
                return null;
            }

            return new Entry(Helpers.parseStr(split[0]), Helpers.parseStr(split[1]), Helpers.parseStr(split[2]), Helpers.parseStr(split[3]),
                    Helpers.parseStr(split[4]), Helpers.parseStr(split[5]), Helpers.parseStr(split[6]), Helpers.parseLong(split, 7, 0),
                    Helpers.parseLong(split, 8, 0), Helpers.parseInt(split, 9, STATUS_OPEN));
        }

        public boolean isOpen() {
            return status == STATUS_OPEN;
        }

        @NonNull
        @Override
        public String toString() {
            return Helpers.mergeObj(videoId, channelId, title, secondTitle, author, cardImageUrl, badge, orderMs, lastSeenMs, status);
        }
    }

    private PrioData(Context context) {
        mPrefs = AppPrefs.instance(context);
        mPrefs.addListener(this);
        restoreState();
    }

    public static PrioData instance(Context context) {
        if (sInstance == null) {
            sInstance = new PrioData(context.getApplicationContext());
        }

        return sInstance;
    }

    public List<Channel> getChannels() {
        return Collections.unmodifiableList(new ArrayList<>(mChannels));
    }

    @Nullable
    public Channel getChannel(String channelId) {
        int index = indexOf(channelId);
        return index != -1 ? mChannels.get(index) : null;
    }

    public boolean containsChannel(String channelId) {
        return indexOf(channelId) != -1;
    }

    public int indexOf(String channelId) {
        if (channelId == null) {
            return -1;
        }

        for (int i = 0; i < mChannels.size(); i++) {
            if (channelId.equals(mChannels.get(i).channelId)) {
                return i;
            }
        }

        return -1;
    }

    /**
     * New channels are added with the lowest priority
     */
    public void addChannel(String channelId, String name, String cardImageUrl) {
        if (channelId == null || containsChannel(channelId)) {
            return;
        }

        mChannels.add(new Channel(channelId, name, cardImageUrl, false, Channel.TIER_NORMAL));
        persistState();
    }

    public void setTier(String channelId, int tier) {
        Channel channel = getChannel(channelId);

        if (channel != null && channel.tier != tier) {
            channel.tier = tier;
            persistState();
        }
    }

    /**
     * Place the channel next to the target channel and take over its tier
     */
    public void moveChannel(String channelId, String targetChannelId) {
        int index = indexOf(channelId);
        int targetIndex = indexOf(targetChannelId);

        if (index == -1 || targetIndex == -1 || index == targetIndex) {
            return;
        }

        Channel channel = mChannels.remove(index);
        channel.tier = mChannels.get(indexOf(targetChannelId)).tier;
        // Moving down: after the target, moving up: before the target
        int newIndex = indexOf(targetChannelId) + (index < targetIndex ? 1 : 0);
        mChannels.add(newIndex, channel);
        persistState();
    }

    /**
     * New order and tiers (e.g. from the web editor). Channels that are missing get removed.
     */
    public void setChannels(List<String> channelIds, Map<String, Integer> tiers) {
        List<Channel> result = new ArrayList<>();

        for (String channelId : channelIds) {
            Channel channel = getChannel(channelId);

            if (channel != null && !result.contains(channel)) {
                Integer tier = tiers.get(channelId);
                channel.tier = tier != null ? tier : channel.tier;
                result.add(channel);
            }
        }

        for (Channel channel : getChannels()) {
            if (!result.contains(channel)) {
                removeChannel(channel.channelId);
            }
        }

        mChannels.clear();
        mChannels.addAll(result);
        persistState();
    }

    /**
     * -1 if the channel isn't a prio channel
     */
    public int getTier(String channelId) {
        Channel channel = getChannel(channelId);
        return channel != null ? channel.tier : -1;
    }

    public void removeChannel(String channelId) {
        int index = indexOf(channelId);

        if (index == -1) {
            return;
        }

        mChannels.remove(index);
        Helpers.removeIf(mEntries.values(), entry -> channelId.equals(entry.channelId));
        persistState();
    }

    /**
     * Fill in a missing name (e.g. channel added from a card without author)
     */
    public void updateChannelInfo(String channelId, String name, String cardImageUrl) {
        Channel channel = getChannel(channelId);

        if (channel == null) {
            return;
        }

        boolean changed = false;

        if (name != null && !name.isEmpty() && (channel.name == null || channel.name.isEmpty() || channel.name.equals(channelId))) {
            channel.name = name;
            changed = true;
        }

        if (cardImageUrl != null && channel.cardImageUrl == null) {
            channel.cardImageUrl = cardImageUrl;
            changed = true;
        }

        if (changed) {
            persistState();
        }
    }

    public void setInitialized(Channel channel) {
        if (!channel.initialized) {
            channel.initialized = true;
            persistState();
        }
    }

    @Nullable
    public Entry getEntry(String videoId) {
        return videoId != null ? mEntries.get(videoId) : null;
    }

    public List<Entry> getEntries() {
        return new ArrayList<>(mEntries.values());
    }

    /**
     * Add a new video or update the metadata of a known one (the status is kept)
     */
    public void putEntry(Entry newEntry) {
        if (!containsChannel(newEntry.channelId)) {
            return;
        }

        Entry entry = mEntries.get(newEntry.videoId);

        if (entry != null) {
            entry.title = newEntry.title != null ? newEntry.title : entry.title;
            entry.secondTitle = newEntry.secondTitle != null ? newEntry.secondTitle : entry.secondTitle;
            entry.author = newEntry.author != null ? newEntry.author : entry.author;
            entry.cardImageUrl = newEntry.cardImageUrl != null ? newEntry.cardImageUrl : entry.cardImageUrl;
            entry.badge = newEntry.badge != null ? newEntry.badge : entry.badge;
            entry.lastSeenMs = newEntry.lastSeenMs;
        } else {
            mEntries.put(newEntry.videoId, newEntry);
        }

        persistState();
    }

    public void setWatched(String videoId, boolean watched) {
        Entry entry = getEntry(videoId);

        if (entry == null) {
            return;
        }

        int status = watched ? Entry.STATUS_WATCHED : Entry.STATUS_OPEN;

        if (entry.status != status) {
            entry.status = status;
            persistNow(); // app could be killed right after playback
        }
    }

    /**
     * Drop watched videos that left the channel's uploads list long ago, so the store doesn't grow forever.
     * They won't come back because they're no longer in the list.
     */
    public void prune() {
        long threshold = System.currentTimeMillis() - PRUNE_AFTER_MS;

        List<Entry> pruned = Helpers.removeIf(mEntries.values(), entry -> !entry.isOpen() && entry.lastSeenMs < threshold);

        if (pruned != null && !pruned.isEmpty()) {
            persistState();
        }
    }

    private void restoreState() {
        String data = mPrefs.getProfileData(PRIO_DATA);

        String[] split = Helpers.splitData(data);

        mChannels.clear();
        mChannels.addAll(Helpers.parseList(split, 0, Channel::fromString));

        mEntries.clear();
        for (Entry entry : Helpers.parseList(split, 1, Entry::fromString)) {
            mEntries.put(entry.videoId, entry);
        }
    }

    public void persistNow() {
        Utils.removeCallbacks(mPersistStateInt);
        persistStateInt();
    }

    private void persistState() {
        Utils.postDelayed(mPersistStateInt, 5_000);
    }

    private void persistStateInt() {
        mPrefs.setProfileData(PRIO_DATA, Helpers.mergeData(mChannels, new ArrayList<>(mEntries.values())));
    }

    @Override
    public void onProfileChanged() {
        restoreState();
    }
}
