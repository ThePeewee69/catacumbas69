package com.catacumbas69.catasonarr.parser;

import java.util.function.Function;

/**
 * A regex with its replacement, as Sonarr's {@code RegexReplace} (GPL-3.0).
 */
public final class RegexReplace {

    private final NetRegex regex;
    private final String replacement;
    private final Function<NetMatch, String> evaluator;

    /**
     * A regex and a .NET replacement pattern.
     *
     * @param pattern the .NET pattern
     * @param replacement the replacement, e.g. {@code ${title}}
     * @param options {@link NetRegex} options
     */
    public RegexReplace(String pattern, String replacement, int options) {
        this.regex = NetRegex.compile(pattern, options);
        this.replacement = replacement;
        this.evaluator = null;
    }

    /**
     * A regex and a function that makes the replacement of each match.
     *
     * @param pattern the .NET pattern
     * @param evaluator the replacement of each match
     * @param options {@link NetRegex} options
     */
    public RegexReplace(String pattern, Function<NetMatch, String> evaluator, int options) {
        this.regex = NetRegex.compile(pattern, options);
        this.replacement = null;
        this.evaluator = evaluator;
    }

    /**
     * Replaces every match.
     *
     * @param input the text
     * @return the text replaced
     */
    public String replace(String input) {
        return evaluator != null ? regex.replace(input, evaluator) : regex.replace(input, replacement);
    }

    /**
     * Whether the text matches; Sonarr's {@code TryReplace} also replaces, which {@link #replace} does.
     *
     * @param input the text
     * @return whether it matched
     */
    public boolean matches(String input) {
        return regex.isMatch(input);
    }

    @Override
    public String toString() {
        return regex.source();
    }
}
