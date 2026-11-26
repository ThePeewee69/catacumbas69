package com.catacumbas69.catasonarr.download;

import com.catacumbas69.catasonarr.model.CustomFormat;
import com.catacumbas69.catasonarr.model.Episode;
import com.catacumbas69.catasonarr.model.Language;
import com.catacumbas69.catasonarr.model.Series;
import com.catacumbas69.catasonarr.quality.QualityModel;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * A download Catasonarr follows, as Sonarr's {@code TrackedDownload} (orch.md decision 122): what its client says,
 * which series and episodes it is for, and where it stands (state, status and the messages shown in the queue).
 */
public final class TrackedDownload {

    /** Sonarr's {@code TrackedDownloadState}, as the API writes it. */
    public static final String DOWNLOADING = "downloading";
    /** Finished, but something stops its import. */
    public static final String IMPORT_BLOCKED = "importBlocked";
    /** Finished and about to be imported. */
    public static final String IMPORT_PENDING = "importPending";
    /** Being imported. */
    public static final String IMPORTING = "importing";
    /** Imported. */
    public static final String IMPORTED = "imported";
    /** Failed, about to be blocklisted. */
    public static final String FAILED_PENDING = "failedPending";
    /** Failed and blocklisted. */
    public static final String FAILED = "failed";

    /** Sonarr's {@code TrackedDownloadStatus}. */
    public static final String OK = "ok";
    /** Something needs a look. */
    public static final String WARNING = "warning";
    /** It went wrong. */
    public static final String ERROR = "error";

    /**
     * One group of messages shown in the queue.
     *
     * @param title what they are about (a file's name, or the download's)
     * @param messages the messages
     */
    public record StatusMessage(String title, List<String> messages) {
    }

    /** What its client says now. */
    public volatile ClientItem item;
    /** Its series, or {@code null} when it could not be matched. */
    public volatile Series series;
    /** Its episodes. */
    public volatile List<Episode> episodes = List.of();
    /** Its quality. */
    public volatile QualityModel quality;
    /** Its languages. */
    public volatile List<Language> languages = List.of();
    /** Its custom formats. */
    public volatile List<CustomFormat> customFormats = List.of();
    /** Their score. */
    public volatile int customFormatScore;
    /** The indexer it came from. */
    public volatile String indexer;
    /** When it was grabbed. */
    public volatile Instant added;
    /** Whether Catasonarr grabbed it (there is a grab in the history). */
    public volatile boolean grabbed;
    /** Its state. */
    public volatile String state = DOWNLOADING;
    /** Its status. */
    public volatile String status = OK;
    /** Its messages. */
    public volatile List<StatusMessage> messages = new ArrayList<>();
    /** When an import was last tried. */
    public volatile Instant lastImport;

    /**
     * Follows a download.
     *
     * @param item what its client says
     */
    public TrackedDownload(ClientItem item) {
        this.item = item;
    }

    /**
     * Sets the status and its messages.
     *
     * @param newStatus {@link #OK}, {@link #WARNING} or {@link #ERROR}
     * @param list the messages
     */
    public void status(String newStatus, List<StatusMessage> list) {
        this.status = newStatus;
        this.messages = list;
    }

    /**
     * A warning with one message about the whole download.
     *
     * @param message the message
     */
    public void warn(String message) {
        status(WARNING, List.of(new StatusMessage(item.title(), List.of(message))));
    }

    /**
     * The episodes' ids.
     *
     * @return the ids
     */
    public List<Integer> episodeIds() {
        return episodes.stream().map(Episode::id).toList();
    }
}
