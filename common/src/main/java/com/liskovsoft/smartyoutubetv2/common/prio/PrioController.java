package com.liskovsoft.smartyoutubetv2.common.prio;

import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video;
import com.liskovsoft.smartyoutubetv2.common.app.models.playback.BasePlayerController;

/**
 * Fork: tracks the watched state of prio videos.<br/>
 * NOTE: must be added before VideoLoaderController (it loads the next video on play end).
 */
public class PrioController extends BasePlayerController {
    @Override
    public void onPlayEnd() {
        markWatched();
    }

    @Override
    public boolean onNextClicked() {
        // Skipped by the user
        markWatched();
        return false;
    }

    @Override
    public void onNewVideo(Video item) {
        checkWatched();
    }

    @Override
    public void onTickle() {
        checkWatched();
    }

    @Override
    public void onEngineReleased() {
        checkWatched();
    }

    private void checkWatched() {
        Video video = getVideo();

        if (video == null || getContext() == null || getPlayer() == null || !getPlayer().containsMedia() || !getService().isPrioVideo(video)) {
            return;
        }

        getService().markWatchedIfNeeded(video, getPlayer().getPositionMs(), getPlayer().getDurationMs());
    }

    private void markWatched() {
        if (getContext() != null) {
            getService().markWatched(getVideo(), true);
        }
    }

    private PrioService getService() {
        return PrioService.instance(getContext());
    }
}
