package com.catacumbas69.catasonarr.pages;

import com.catacumbas69.catacommons.events.CloudEvent;
import com.catacumbas69.catacommons.events.EventData;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.Arrays;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What a page is told about a change (orch.md decision 118), from one of Catasonarr's events
 * ({@code com.catacumbas69.catasonarr.<entity>.<action>.v1}).
 *
 * @param entity e.g. {@code series}, {@code episode}, {@code rootfolder}
 * @param action e.g. {@code added}, {@code updated}, {@code deleted}, {@code refreshed}
 * @param subject what it is about, e.g. {@code Andor (12)}
 * @param actor who did it: a user, {@code api-key@<address>}, an address, or {@code catasonarr} itself
 * @param seriesId the series it is about, when it is about one (a series, or another entity whose details name
 *                 one, such as an imported file or a grab: orch.md decision 122)
 * @param time when it happened
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record PageChange(String entity, String action, String subject, String actor, Integer seriesId, String time) {

    private static final Pattern TYPE =
            Pattern.compile("^com\\.catacumbas69\\.[a-z0-9-]+\\.([a-z0-9-]+)\\.([a-z0-9-]+)\\.v\\d+$");
    private static final Pattern TRAILING_ID = Pattern.compile("\\((\\d+)\\)$");

    /**
     * Reads an event.
     *
     * @param event the event
     * @return the change, or empty when it is not one of the apps' events
     */
    public static Optional<PageChange> of(CloudEvent event) {
        if (event == null || event.type() == null) {
            return Optional.empty();
        }
        Matcher type = TYPE.matcher(event.type());
        if (!type.matches()) {
            return Optional.empty();
        }
        String entity = type.group(1);
        EventData data = event.data();
        String actor = data == null ? null : data.actor();
        Integer seriesId = "series".equals(entity) ? seriesId(event.subject(), data)
                : data == null || data.details() == null ? null : parse(data.details().get("seriesId"));
        return Optional.of(new PageChange(entity, type.group(2), event.subject(), actor, seriesId, event.time()));
    }

    private static Integer seriesId(String subject, EventData data) {
        if (data != null) {
            for (Map<String, String> values : Arrays.asList(data.after(), data.before(), data.details())) {
                Integer id = values == null ? null : parse(values.get("id"));
                if (id != null) {
                    return id;
                }
            }
        }
        Matcher id = subject == null ? null : TRAILING_ID.matcher(subject);
        return id != null && id.find() ? parse(id.group(1)) : null;
    }

    private static Integer parse(String value) {
        try {
            return value == null ? null : Integer.valueOf(value);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
