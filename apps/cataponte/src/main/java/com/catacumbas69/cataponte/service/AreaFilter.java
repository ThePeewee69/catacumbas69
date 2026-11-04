package com.catacumbas69.cataponte.service;

import com.catacumbas69.cataponte.model.AreaDocument;
import com.catacumbas69.cataponte.model.BridgeDocument;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.regex.Pattern;

/**
 * The glob patterns of an area (ADR 0014). A pattern is written without {@code persistent://}, as
 * {@code tenant/namespace/topic}; one that starts with {@code non-persistent://} matches only non-persistent topics,
 * any other only persistent ones. {@code *} matches any characters within one segment, {@code **} any characters
 * across segments. An area without patterns allows every topic.
 */
public final class AreaFilter {

    /** At most this many patterns per area. */
    public static final int MAX_PATTERNS = 20;

    private static final String PERSISTENT = "persistent://";
    private static final String NON_PERSISTENT = "non-persistent://";
    private static final Pattern SYNTAX = Pattern.compile("^[A-Za-z0-9_.=:*-]+(/[A-Za-z0-9_.=:*-]+)*$");
    private static final int SEGMENTS = 3;

    private final List<Compiled> patterns;

    private AreaFilter(List<Compiled> patterns) {
        this.patterns = patterns;
    }

    /**
     * Builds the filter of an area.
     *
     * @param area the area; {@code null} allows every topic
     * @return the filter
     */
    public static AreaFilter of(AreaDocument area) {
        List<Compiled> compiled = new ArrayList<>();
        if (area != null && area.getPatterns() != null) {
            area.getPatterns().forEach(pattern -> compiled.add(compile(pattern)));
        }
        return new AreaFilter(compiled);
    }

    /**
     * Trims the patterns, drops blanks and duplicates, and checks them.
     *
     * @param patterns patterns as sent by the client; {@code null} means none
     * @return the clean patterns, in their order
     * @throws BridgeValidationException if there are too many or one is not valid
     */
    public static List<String> normalize(List<String> patterns) {
        LinkedHashSet<String> clean = new LinkedHashSet<>();
        if (patterns != null) {
            patterns.stream().filter(pattern -> pattern != null && !pattern.isBlank())
                    .map(String::trim).forEach(clean::add);
        }
        if (clean.size() > MAX_PATTERNS) {
            throw new BridgeValidationException("patterns", "At most " + MAX_PATTERNS + " patterns per area");
        }
        clean.forEach(AreaFilter::check);
        return List.copyOf(clean);
    }

    /**
     * Whether a topic matches the filter.
     *
     * @param topic fully-qualified topic
     * @return {@code true} if it matches a pattern, or the area has none
     */
    public boolean matches(String topic) {
        if (patterns.isEmpty()) {
            return true;
        }
        return patterns.stream().anyMatch(pattern -> pattern.matches(topic));
    }

    /**
     * The topics of a bridge (source and destinations) that do not match the filter.
     *
     * @param bridge the bridge
     * @return the topics outside the filter, source first; empty when all fit
     */
    public List<String> outside(BridgeDocument bridge) {
        List<String> topics = new ArrayList<>();
        if (bridge.getSource() != null && bridge.getSource().getTopic() != null) {
            topics.add(bridge.getSource().getTopic());
        }
        topics.addAll(bridge.resolveDestinationTopics());
        return topics.stream().filter(topic -> !matches(topic)).distinct().toList();
    }

    private static void check(String pattern) {
        String body = pattern.startsWith(NON_PERSISTENT) ? pattern.substring(NON_PERSISTENT.length()) : pattern;
        if (body.startsWith(PERSISTENT) || !SYNTAX.matcher(body).matches()) {
            throw new BridgeValidationException("patterns", "'" + pattern + "' is not a valid pattern: write"
                    + " tenant/namespace/topic without persistent://, using letters, digits, '.', '_', '=', ':',"
                    + " '-', '*' (one segment) and '**' (several segments)");
        }
        if (!body.contains("**") && body.split("/", -1).length != SEGMENTS) {
            throw new BridgeValidationException("patterns", "'" + pattern + "' needs three segments,"
                    + " tenant/namespace/topic (or '**' for several)");
        }
    }

    private static Compiled compile(String pattern) {
        boolean persistent = !pattern.startsWith(NON_PERSISTENT);
        String body = persistent ? pattern : pattern.substring(NON_PERSISTENT.length());
        StringBuilder regex = new StringBuilder();
        for (int i = 0; i < body.length(); i++) {
            char c = body.charAt(i);
            if (c == '*' && i + 1 < body.length() && body.charAt(i + 1) == '*') {
                regex.append(".*");
                i++;
            } else if (c == '*') {
                regex.append("[^/]*");
            } else {
                regex.append(Pattern.quote(String.valueOf(c)));
            }
        }
        return new Compiled(persistent, Pattern.compile(regex.toString()));
    }

    private record Compiled(boolean persistent, Pattern regex) {

        boolean matches(String topic) {
            String scheme = persistent ? PERSISTENT : NON_PERSISTENT;
            return topic != null && topic.startsWith(scheme)
                    && regex.matcher(topic.substring(scheme.length())).matches();
        }
    }
}
