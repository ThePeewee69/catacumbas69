package com.catacumbas69.catasonarr.model;

import java.time.Instant;
import java.util.Map;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * A command sent to {@code POST /api/v3/command} (orch.md decision 112), kept for
 * {@code catasonarr.commands.keep} after it arrives (a TTL index on {@code queued}).
 *
 * @param id Sonarr-style id
 * @param name e.g. {@code RefreshSeries}, {@code SeriesSearch}
 * @param body what was sent
 * @param status {@code queued}, {@code started}, {@code completed} or {@code failed}
 * @param result {@code unknown}, {@code successful} or {@code unsuccessful}
 * @param message what happened
 * @param trigger {@code manual} or {@code scheduled}
 * @param queued when it arrived
 * @param started when it started, or {@code null}
 * @param ended when it ended, or {@code null}
 */
@Document("catasonarr_commands")
public record Command(
        @Id int id,
        String name,
        Map<String, Object> body,
        String status,
        String result,
        String message,
        String trigger,
        Instant queued,
        Instant started,
        Instant ended) {
}
