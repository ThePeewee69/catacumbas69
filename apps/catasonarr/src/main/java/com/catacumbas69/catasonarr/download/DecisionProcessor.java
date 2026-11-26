package com.catacumbas69.catasonarr.download;

import com.catacumbas69.catasonarr.decision.DownloadDecision;
import com.catacumbas69.catasonarr.decision.DownloadDecisionMaker;
import com.catacumbas69.catasonarr.decision.RemoteEpisode;
import com.catacumbas69.catasonarr.service.PendingReleaseService;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Acts on decisions, as Sonarr's {@code ProcessDownloadDecisions} (GPL-3.0, orch.md decision 122): the best
 * approved release of each episode is grabbed, once per episode; temporarily rejected ones (a delay) wait; when no
 * client can take a release, it waits too.
 */
@Service
public class DecisionProcessor {

    private static final Logger LOG = LoggerFactory.getLogger(DecisionProcessor.class);

    /**
     * What was done.
     *
     * @param grabbed releases grabbed
     * @param pending releases held back
     * @param rejected releases rejected
     */
    public record Result(List<String> grabbed, List<String> pending, int rejected) {
    }

    private final DownloadDecisionMaker decisions;
    private final DownloadService downloads;
    private final PendingReleaseService pendingReleases;

    /**
     * Creates the processor.
     *
     * @param decisions the decision maker
     * @param downloads the grabs
     * @param pendingReleases held-back releases
     */
    public DecisionProcessor(DownloadDecisionMaker decisions, DownloadService downloads,
                             PendingReleaseService pendingReleases) {
        this.decisions = decisions;
        this.downloads = downloads;
        this.pendingReleases = pendingReleases;
    }

    /**
     * Grabs the best approved releases and holds back the delayed ones.
     *
     * @param all the decisions
     * @param actor who asked ({@code catasonarr} for automatic ones)
     * @return what was done
     */
    public Result process(List<DownloadDecision> all, String actor) {
        List<String> grabbed = new ArrayList<>();
        List<String> pending = new ArrayList<>();
        Set<Integer> grabbedEpisodes = new HashSet<>();
        Set<Integer> pendingEpisodes = new HashSet<>();
        int rejected = 0;
        for (DownloadDecision d : decisions.prioritize(all)) {
            RemoteEpisode r = d.remoteEpisode();
            if (r.series == null) {
                rejected++;
                continue;
            }
            List<Integer> ids = r.episodeIds();
            if (d.approved()) {
                if (ids.stream().anyMatch(grabbedEpisodes::contains)) {
                    continue;
                }
                try {
                    downloads.grab(r, actor);
                    grabbed.add(r.release.title());
                    grabbedEpisodes.addAll(ids);
                } catch (DownloadService.NoClientException e) {
                    if (ids.stream().noneMatch(pendingEpisodes::contains)) {
                        pendingReleases.add(r.series.id(), ids, r.release, "downloadClientUnavailable");
                        pending.add(r.release.title());
                        pendingEpisodes.addAll(ids);
                    }
                } catch (RuntimeException e) {
                    LOG.warn("Couldn't grab {}: {}", r.release.title(), e.getMessage());
                }
            } else if (d.temporarilyRejected()) {
                if (ids.stream().noneMatch(grabbedEpisodes::contains)
                        && ids.stream().noneMatch(pendingEpisodes::contains)) {
                    pendingReleases.add(r.series.id(), ids, r.release, "delay");
                    pending.add(r.release.title());
                    pendingEpisodes.addAll(ids);
                }
            } else {
                rejected++;
            }
        }
        return new Result(grabbed, pending, rejected);
    }
}
