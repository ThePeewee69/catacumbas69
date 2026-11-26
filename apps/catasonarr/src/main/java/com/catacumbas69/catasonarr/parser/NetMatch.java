package com.catacumbas69.catasonarr.parser;

import java.util.List;
import java.util.Map;

/**
 * A match of a {@link NetRegex}, as .NET's {@code Match}: its text, where it is, and each group by its .NET name or
 * number.
 */
public final class NetMatch {

    /** No match. */
    public static final NetMatch FAILED = new NetMatch(null, 0, Map.of());

    private final String value;
    private final int index;
    private final Map<String, List<NetCapture>> captures;

    NetMatch(String value, int index, Map<String, List<NetCapture>> captures) {
        this.value = value;
        this.index = index;
        this.captures = captures;
    }

    /**
     * Whether there is a match, as {@code Match.Success}.
     *
     * @return whether it matched
     */
    public boolean success() {
        return value != null;
    }

    /**
     * The text matched, or {@code ""}.
     *
     * @return the value
     */
    public String value() {
        return value == null ? "" : value;
    }

    /**
     * Where the match starts.
     *
     * @return the index
     */
    public int index() {
        return index;
    }

    /**
     * The match's length.
     *
     * @return the length
     */
    public int length() {
        return value().length();
    }

    /**
     * A group by its .NET name, or by its number as text ({@code "1"}); {@code "0"} is the whole match.
     *
     * @param name the name
     * @return the group; without captures if it did not take part, or does not exist
     */
    public NetGroup group(String name) {
        if (name.equals("0")) {
            return new NetGroup(success() ? List.of(new NetCapture(value, index)) : List.of());
        }
        return new NetGroup(captures.getOrDefault(name, List.of()));
    }

    /**
     * A group by its .NET number.
     *
     * @param number the number; 0 is the whole match
     * @return the group
     */
    public NetGroup group(int number) {
        return group(String.valueOf(number));
    }

    Map<String, List<NetCapture>> captures() {
        return captures;
    }

    /**
     * A .NET replacement pattern with this match's groups: {@code ${name}}, {@code $1}, {@code $0}, {@code $&},
     * {@code $$}.
     *
     * @param replacement the pattern
     * @return the text
     */
    public String expand(String replacement) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < replacement.length(); i++) {
            char c = replacement.charAt(i);
            if (c != '$' || i + 1 >= replacement.length()) {
                out.append(c);
                continue;
            }
            char next = replacement.charAt(i + 1);
            if (next == '$') {
                out.append('$');
                i++;
            } else if (next == '&') {
                out.append(value());
                i++;
            } else if (next == '{' && replacement.indexOf('}', i) > 0) {
                int end = replacement.indexOf('}', i);
                out.append(group(replacement.substring(i + 2, end)).value());
                i = end;
            } else if (Character.isDigit(next)) {
                int end = i + 1;
                while (end < replacement.length() && Character.isDigit(replacement.charAt(end))) {
                    end++;
                }
                out.append(group(replacement.substring(i + 1, end)).value());
                i = end - 1;
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }
}
