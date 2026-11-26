package com.catacumbas69.catasonarr.decision;

import com.catacumbas69.catasonarr.parser.NetMatch;
import com.catacumbas69.catasonarr.parser.NetRegex;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A release profile's term, as Sonarr's {@code TermMatcherService} and {@code PerlRegexFactory}: {@code /x265/i} is
 * a regular expression (modifiers {@code i}, {@code m}, {@code s}, {@code x}, {@code n}); anything else is text,
 * found without case.
 */
public final class TermMatcher {

    private static final NetRegex PERL = NetRegex.compile("/(?<pattern>.*)/(?<modifiers>[a-z]*)");
    private static final Map<String, NetRegex> CACHE = new ConcurrentHashMap<>();

    private TermMatcher() {
    }

    /**
     * The term in a text, as it matched.
     *
     * @param term the term
     * @param value the text
     * @return what matched, or {@code null}
     */
    public static String matchingTerm(String term, String value) {
        NetRegex regex = regex(term);
        if (regex == null) {
            return value.toLowerCase(Locale.ROOT).contains(term.toLowerCase(Locale.ROOT)) ? term : null;
        }
        NetMatch m = regex.match(value);
        return m.success() ? m.value() : null;
    }

    /**
     * Whether a text has the term.
     *
     * @param term the term
     * @param value the text
     * @return whether it does
     */
    public static boolean isMatch(String term, String value) {
        return matchingTerm(term, value) != null;
    }

    private static NetRegex regex(String term) {
        NetMatch m = PERL.match(term);
        if (!m.success()) {
            return null;
        }
        return CACHE.computeIfAbsent(term, t -> {
            int options = 0;
            String pattern = m.group("pattern").value();
            for (char c : m.group("modifiers").value().toCharArray()) {
                switch (c) {
                    case 'i':
                        options |= NetRegex.IGNORE_CASE;
                        break;
                    case 'x':
                        options |= NetRegex.IGNORE_PATTERN_WHITESPACE;
                        break;
                    case 'm':
                        pattern = "(?m)" + pattern;
                        break;
                    case 's':
                        pattern = "(?s)" + pattern;
                        break;
                    case 'n':
                        break;
                    default:
                        throw new IllegalArgumentException("Unknown or unsupported perl regex modifier: " + c);
                }
            }
            return NetRegex.compile(pattern, options);
        });
    }
}
