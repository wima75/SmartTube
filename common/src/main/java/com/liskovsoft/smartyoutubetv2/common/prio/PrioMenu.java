package com.liskovsoft.smartyoutubetv2.common.prio;

import android.content.Context;

import com.liskovsoft.sharedutils.helpers.MessageHelpers;
import com.liskovsoft.smartyoutubetv2.common.R;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video;
import com.liskovsoft.smartyoutubetv2.common.app.models.playback.ui.UiOptionItem;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.AppDialogPresenter;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.dialogs.menu.VideoMenuPresenter.VideoMenuCallback;
import com.liskovsoft.smartyoutubetv2.common.misc.MediaServiceManager;
import com.liskovsoft.smartyoutubetv2.common.prio.PrioData.Channel;
import com.liskovsoft.smartyoutubetv2.common.prio.PrioData.Entry;
import com.liskovsoft.smartyoutubetv2.common.utils.Utils;

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
     * List of the prio channels (first = highest priority). Click on a channel to move or remove it.
     */
    public static void showManageDialog(Context context) {
        PrioService service = PrioService.instance(context);
        AppDialogPresenter dialog = AppDialogPresenter.instance(context);

        List<Channel> channels = service.getData().getChannels();

        for (int i = 0; i < channels.size(); i++) {
            Channel channel = channels.get(i);
            dialog.appendSingleButton(UiOptionItem.from((i + 1) + ". " + getName(channel), optionItem -> showChannelDialog(context, channel)));
        }

        if (channels.isEmpty()) {
            dialog.appendSingleButton(UiOptionItem.from(context.getString(R.string.prio_no_channels), optionItem -> dialog.closeDialog()));
        } else {
            dialog.appendSingleButton(UiOptionItem.from(context.getString(R.string.prio_refresh), optionItem -> {
                service.sync(true);
                MessageHelpers.showMessage(context, R.string.prio_refreshing);
            }));
        }

        dialog.showDialog(context.getString(R.string.prio_manage_channels));
    }

    private static void showChannelDialog(Context context, Channel channel) {
        PrioService service = PrioService.instance(context);
        AppDialogPresenter dialog = AppDialogPresenter.instance(context);

        int index = service.getData().indexOf(channel.channelId);
        int last = service.getData().getChannels().size() - 1;

        if (index > 0) {
            appendMoveButton(context, dialog, service, channel, R.string.prio_move_top, 0);
            appendMoveButton(context, dialog, service, channel, R.string.prio_move_up, index - 1);
        }

        if (index < last) {
            appendMoveButton(context, dialog, service, channel, R.string.prio_move_down, index + 1);
            appendMoveButton(context, dialog, service, channel, R.string.prio_move_bottom, last);
        }

        dialog.appendSingleButton(UiOptionItem.from(context.getString(R.string.prio_remove_channel), optionItem -> {
            service.removeChannel(channel.channelId);
            reopenManageDialog(context, dialog);
        }));

        dialog.showDialog((index + 1) + ". " + getName(channel));
    }

    private static void appendMoveButton(Context context, AppDialogPresenter dialog, PrioService service, Channel channel, int titleResId, int newIndex) {
        dialog.appendSingleButton(UiOptionItem.from(context.getString(titleResId), optionItem -> {
            service.moveChannel(channel.channelId, newIndex);
            reopenManageDialog(context, dialog);
        }));
    }

    /**
     * Show the list with the new order. Wait until the old dialog is gone.
     */
    private static void reopenManageDialog(Context context, AppDialogPresenter dialog) {
        dialog.closeDialog();
        Utils.postDelayed(() -> showManageDialog(context), 500);
    }

    private static String getName(Channel channel) {
        return channel.name != null ? channel.name : channel.channelId;
    }
}
