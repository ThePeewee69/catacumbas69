package com.catacumbas69.catasonarr.decision;

import com.catacumbas69.catasonarr.model.CustomFormat;
import com.catacumbas69.catasonarr.quality.QualityModel;
import java.util.List;

/** What is downloading now, as the decision engine needs it (the queue's side of Sonarr's {@code QueueService}). */
public interface QueueView {

    /**
     * One download in the queue.
     *
     * @param seriesId its series
     * @param episodeIds its episodes
     * @param quality its quality
     * @param customFormats its custom formats
     * @param failedPending failed and about to be removed (not counted)
     */
    record Queued(int seriesId, List<Integer> episodeIds, QualityModel quality, List<CustomFormat> customFormats,
                  boolean failedPending) {
    }

    /**
     * Everything in the queue.
     *
     * @return the downloads
     */
    List<Queued> queued();
}
