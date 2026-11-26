package com.catacumbas69.catasonarr.decision;

import java.util.List;

/**
 * A release and whether to grab it (Sonarr's {@code DownloadDecision}).
 *
 * @param remoteEpisode the release with its series and episodes
 * @param rejections why not, or empty to grab it
 */
public record DownloadDecision(RemoteEpisode remoteEpisode, List<Rejection> rejections) {

    /**
     * Whether it may be grabbed.
     *
     * @return no rejections
     */
    public boolean approved() {
        return rejections.isEmpty();
    }

    /**
     * Whether it is only rejected for now (pending).
     *
     * @return every rejection temporary
     */
    public boolean temporarilyRejected() {
        return !rejections.isEmpty() && rejections.stream().allMatch(Rejection::temporary);
    }
}
