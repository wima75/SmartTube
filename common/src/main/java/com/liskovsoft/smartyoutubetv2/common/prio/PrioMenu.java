package com.liskovsoft.smartyoutubetv2.common.prio;

import android.content.Context;

import com.liskovsoft.sharedutils.helpers.MessageHelpers;
import com.liskovsoft.smartyoutubetv2.common.R;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video;
import com.liskovsoft.smartyoutubetv2.common.app.models.playback.ui.OptionItem;
import com.liskovsoft.smartyoutubetv2.common.app.models.playback.ui.UiOptionItem;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.AppDialogPresenter;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.dialogs.menu.VideoMenuPresenter.VideoMenuCallback;
import com.liskovsoft.smartyoutubetv2.common.misc.MediaServiceManager;
import com.liskovsoft.smartyoutubetv2.common.prio.PrioData.Channel;
import com.liskovsoft.smartyoutubetv2.common.prio.PrioData.Entry;
import com.liskovsoft.smartyoutubetv2.common.utils.Utils;

import com.liskovsoft.mediaserviceinterfaces.data.MediaItem;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Fork: context menu items and the dialog to manage the prio channels
 */
public class PrioMenu {
    /**
     * Add the prio items to the video/channel context menu
     */
    public static void appendMenuItems(Context context, AppDialogPresenter dialog, Video video, VideoMenuCallback callback) {
        if (context == null || video == null || video.isChapter) {
            return;
        }

        PrioService service = PrioService.instance(context);

        appendWatchedButton(context, dialog, service, video, callback);
        appendChannelButton(context, dialog, service, video);

        Channel channel = service.getData().getChannel(video.channelId);
        if (channel != null) {
            dialog.appendSingleButton(UiOptionItem.from(context.getString(R.string.prio_tier_title, getTierName(context, channel.tier)),
                    optionItem -> {
                        dialog.closeDialog();
                        Utils.postDelayed(() -> showChannelDialog(context, channel), 500);
                    }));
        }

        if (service.isPrioVideo(video)) {
            dialog.appendSingleButton(UiOptionItem.from(context.getString(R.string.prio_manage_channels), optionItem -> showManageDialog(context)));
        }
    }

    private static void appendWatchedButton(Context context, AppDialogPresenter dialog, PrioService service, Video video, VideoMenuCallback callback) {
        Entry entry = service.getData().getEntry(video.videoId);

        if (entry == null) {
            return;
        }

        boolean isOpen = entry.isOpen();

        dialog.appendSingleButton(UiOptionItem.from(context.getString(isOpen ? R.string.prio_mark_watched : R.string.prio_mark_unwatched),
                optionItem -> {
                    service.markWatched(video, isOpen);
                    if (isOpen && callback != null) {
                        callback.onItemAction(video, VideoMenuCallback.ACTION_REMOVE);
                    }
                    dialog.closeDialog();
                }));
    }

    private static void appendChannelButton(Context context, AppDialogPresenter dialog, PrioService service, Video video) {
        if (video.channelId == null && video.videoId == null) {
            return;
        }

        boolean isPrio = service.isPrioChannel(video.channelId);

        dialog.appendSingleButton(UiOptionItem.from(context.getString(isPrio ? R.string.prio_remove_channel : R.string.prio_add_channel),
                optionItem -> {
                    dialog.closeDialog();

                    if (isPrio) {
                        service.removeChannel(video.channelId);
                        MessageHelpers.showMessage(context, R.string.prio_channel_removed);
                    } else if (video.channelId != null) {
                        addChannel(context, service, video);
                    } else {
                        // Channel id unknown (e.g. some rows). Fetch it first.
                        MessageHelpers.showMessage(context, R.string.wait_data_loading);
                        MediaServiceManager.instance().loadMetadata(video, metadata -> {
                            video.sync(metadata);
                            if (video.channelId != null) {
                                addChannel(context, service, video);
                            }
                        });
                    }
                }));
    }

    private static void addChannel(Context context, PrioService service, Video video) {
        if (service.isPrioChannel(video.channelId)) {
            return;
        }

        boolean isChannelCard = !video.hasVideo();
        String name = isChannelCard ? video.getTitle() : video.getAuthor();
        String cardImageUrl = isChannelCard ? video.cardImageUrl : null;

        service.addChannel(video.channelId, name, cardImageUrl);
        MessageHelpers.showMessage(context, context.getString(R.string.prio_channel_added, name != null ? name : video.channelId));
    }

    /**
     * List of the prio channels grouped by tier. Click on a channel to change its tier or remove it.
     */
    public static void showManageDialog(Context context) {
        PrioService service = PrioService.instance(context);
        AppDialogPresenter dialog = AppDialogPresenter.instance(context);

        List<Channel> channels = getSortedChannels(service);

        // Fetch the missing names in the background
        for (Channel channel : channels) {
            if (!hasName(channel)) {
                service.sync(true);
                break;
            }
        }

        if (!channels.isEmpty()) {
            dialog.appendSingleButton(UiOptionItem.from(context.getString(R.string.prio_edit_on_phone), optionItem -> {
                // Keep the dialog activity open. The QR dialog is attached to it.
                PrioWebEditor.instance(context).show(dialog.getContext(), () -> reopenManageDialog(context, dialog));
            }));
        }

        for (Channel channel : channels) {
            dialog.appendSingleButton(UiOptionItem.from(getTierName(context, channel.tier) + " · " + getName(channel),
                    optionItem -> showChannelDialog(context, channel)));
        }

        if (channels.isEmpty()) {
            dialog.appendSingleButton(UiOptionItem.from(context.getString(R.string.prio_no_channels), optionItem -> dialog.closeDialog()));
        } else {
            dialog.appendSingleButton(UiOptionItem.from(context.getString(R.string.prio_refresh), optionItem -> {
                service.sync(true);
                MessageHelpers.showMessage(context, R.string.prio_refreshing);
            }));
        }

        dialog.appendSingleButton(UiOptionItem.from(context.getString(R.string.prio_add_subscriptions), optionItem -> addSubscriptions(context, dialog)));

        dialog.showDialog(context.getString(R.string.prio_manage_channels));
    }

    /**
     * Highest tier first, inside a tier the user's order (sort is stable)
     */
    static List<Channel> getSortedChannels(PrioService service) {
        List<Channel> channels = new ArrayList<>(service.getData().getChannels());
        Collections.sort(channels, (o1, o2) -> o1.tier - o2.tier);
        return channels;
    }

    private static void showChannelDialog(Context context, Channel channel) {
        PrioService service = PrioService.instance(context);
        AppDialogPresenter dialog = AppDialogPresenter.instance(context);

        if (service.getData().getChannels().size() > 1) {
            dialog.appendSingleButton(UiOptionItem.from(context.getString(R.string.prio_move), optionItem -> {
                dialog.closeDialog();
                Utils.postDelayed(() -> showMoveDialog(context, channel), 500);
            }));
        }

        for (int tier : Channel.TIERS) {
            String title = context.getString(R.string.prio_tier_title, getTierName(context, tier));
            dialog.appendSingleButton(UiOptionItem.from(channel.tier == tier ? title + "  ✓" : title, optionItem -> {
                service.setTier(channel.channelId, tier);
                reopenManageDialog(context, dialog);
            }));
        }

        dialog.appendSingleButton(UiOptionItem.from(context.getString(R.string.prio_remove_channel), optionItem -> {
            service.removeChannel(channel.channelId);
            reopenManageDialog(context, dialog);
        }));

        dialog.showDialog(getName(channel));
    }

    /**
     * Remote friendly sorting: the list starts at the channel's position. Navigate up/down to the new position and press OK.
     */
    private static void showMoveDialog(Context context, Channel channel) {
        PrioService service = PrioService.instance(context);
        AppDialogPresenter dialog = AppDialogPresenter.instance(context);

        List<OptionItem> items = new ArrayList<>();

        for (Channel item : getSortedChannels(service)) {
            boolean isCurrent = item.channelId.equals(channel.channelId);
            String title = getTierName(context, item.tier) + " \u00B7 " + getName(item);
            items.add(UiOptionItem.from(isCurrent ? "\u25B6 " + title : title, optionItem -> {
                if (optionItem.isSelected() && !isCurrent) {
                    service.moveChannel(channel.channelId, item.channelId);
                }
                reopenManageDialog(context, dialog);
            }, isCurrent));
        }

        String title = context.getString(R.string.prio_move_title, getName(channel));
        dialog.appendRadioCategory(title, items);
        dialog.showDialog(title);
    }

    static String getTierName(Context context, int tier) {
        switch (tier) {
            case Channel.TIER_HIGH:
                return context.getString(R.string.prio_tier_high);
            case Channel.TIER_LOW:
                return context.getString(R.string.prio_tier_low);
            default:
                return context.getString(R.string.prio_tier_normal);
        }
    }

    private static void addSubscriptions(Context context, AppDialogPresenter dialog) {
        dialog.closeDialog();
        MessageHelpers.showMessage(context, R.string.wait_data_loading);

        MediaServiceManager.instance().loadSubscribedChannels(group -> {
            List<MediaItem> items = group != null ? group.getMediaItems() : null;

            if (items == null || items.isEmpty()) {
                MessageHelpers.showMessage(context, R.string.prio_no_subscriptions);
                return;
            }

            PrioService.instance(context).addChannels(items, added -> {
                MessageHelpers.showMessage(context, context.getString(R.string.prio_subscriptions_added, added));
                showManageDialog(context);
            });
        });
    }

    /**
     * Show the updated list. Wait until the old dialog is gone.
     */
    private static void reopenManageDialog(Context context, AppDialogPresenter dialog) {
        dialog.closeDialog();
        Utils.postDelayed(() -> showManageDialog(context), 500);
    }

    private static boolean hasName(Channel channel) {
        return channel.name != null && !channel.name.isEmpty() && !channel.name.equals(channel.channelId);
    }

    static String getName(Channel channel) {
        return hasName(channel) ? channel.name : channel.channelId;
    }
}
