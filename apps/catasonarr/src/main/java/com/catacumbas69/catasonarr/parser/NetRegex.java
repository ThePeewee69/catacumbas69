package com.catacumbas69.catasonarr.parser;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A regular expression written for .NET, as Sonarr's parser writes them, run by Java's engine (orch.md decision 122).
 * The two engines agree on almost everything; what they do not, this class translates:
 * <ul>
 *   <li>.NET keeps <b>every capture</b> of a group that repeats ({@code (?:e(?<episode>\d+))+} gives each episode);
 *   Java keeps the last. Each repeated part with groups inside is captured whole, and read again one repetition at a
 *   time to collect them, in order.</li>
 *   <li>.NET lets <b>several groups share a name</b> (one per alternative) and names may hold {@code _}; Java allows
 *   neither. Every group gets a Java name; a .NET name collects the captures of all its groups.</li>
 *   <li>.NET's look-behind may have any length ({@code (?<!\d+)}); Java's must be bounded: {@code +} and {@code *}
 *   in a look-behind become {@code {1,64}} and {@code {0,64}}.</li>
 *   <li>Inside a character class, .NET reads {@code [} and {@code &&} as characters; Java does not. They are
 *   escaped. A {@code {} that starts no quantifier is a character in .NET and is escaped too.</li>
 *   <li>{@code \p{IsCyrillic}} (a .NET block) becomes {@code \p{InCyrillic}}; {@code IgnorePatternWhitespace} is done
 *   here, as Java's comments mode also drops spaces inside character classes; {@code .} stops only at {@code \n}
 *   and {@code \d}, {@code \w}, {@code \b} are Unicode's, as in .NET.</li>
 * </ul>
 * Translated by hand where needed (the parser's own classes); this class only runs them.
 */
public final class NetRegex {

    /** {@code RegexOptions.IgnoreCase}. */
    public static final int IGNORE_CASE = 1;
    /** {@code RegexOptions.IgnorePatternWhitespace}. */
    public static final int IGNORE_PATTERN_WHITESPACE = 2;

    private static final int LOOKBEHIND_MAX = 64;

    private final String source;
    private final Pattern pattern;
    /** .NET group name (or number, for unnamed groups) → its Java groups, outside any repeated part. */
    private final Map<String, List<String>> groups;
    /** Repeated parts: the Java group that captures one whole, and the regex of one repetition. */
    private final List<Repeat> repeats;
    /** .NET numbers of the groups, as {@code Groups[1]} reads them: unnamed first, then named. */
    private final List<String> numbered;

    private record Repeat(String javaGroup, NetRegex body) {
    }

    private NetRegex(String source, Pattern pattern, Map<String, List<String>> groups, List<Repeat> repeats,
                     List<String> numbered) {
        this.source = source;
        this.pattern = pattern;
        this.groups = groups;
        this.repeats = repeats;
        this.numbered = numbered;
    }

    /**
     * Compiles a .NET regular expression.
     *
     * @param netPattern the pattern as .NET reads it
     * @param options {@link #IGNORE_CASE}, {@link #IGNORE_PATTERN_WHITESPACE}, or 0
     * @return the regex
     */
    public static NetRegex compile(String netPattern, int options) {
        String text = (options & IGNORE_PATTERN_WHITESPACE) != 0 ? stripWhitespace(netPattern) : netPattern;
        int flags = Pattern.UNICODE_CHARACTER_CLASS | Pattern.UNIX_LINES;
        if ((options & IGNORE_CASE) != 0) {
            flags |= Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE;
        }
        return new Translator(text, options).translate(netPattern, flags);
    }

    /**
     * Compiles a .NET regular expression without options.
     *
     * @param netPattern the pattern
     * @return the regex
     */
    public static NetRegex compile(String netPattern) {
        return compile(netPattern, 0);
    }

    /**
     * The pattern as it was written for .NET.
     *
     * @return the .NET pattern
     */
    public String source() {
        return source;
    }

    /**
     * The first match, as .NET's {@code Regex.Match}.
     *
     * @param input the text
     * @return the match; {@link NetMatch#success()} says whether there is one
     */
    public NetMatch match(CharSequence input) {
        return match(input, 0);
    }

    /**
     * The first match from a position on.
     *
     * @param input the text
     * @param start where to start looking
     * @return the match
     */
    public NetMatch match(CharSequence input, int start) {
        Matcher m = pattern.matcher(input);
        return m.find(start) ? toMatch(input, m) : NetMatch.FAILED;
    }

    /**
     * Whether the text has a match, as {@code Regex.IsMatch}.
     *
     * @param input the text
     * @return whether it matches anywhere
     */
    public boolean isMatch(CharSequence input) {
        return pattern.matcher(input).find();
    }

    /**
     * Every match, one after another, as {@code Regex.Matches}.
     *
     * @param input the text
     * @return the matches, possibly none
     */
    public List<NetMatch> matches(CharSequence input) {
        List<NetMatch> all = new ArrayList<>();
        Matcher m = pattern.matcher(input);
        while (m.find()) {
            all.add(toMatch(input, m));
        }
        return all;
    }

    /**
     * Replaces every match, as {@code Regex.Replace} with a replacement pattern: {@code ${name}}, {@code $1},
     * {@code $0}, {@code $&} and {@code $$}.
     *
     * @param input the text
     * @param replacement the .NET replacement
     * @return the text with the matches replaced
     */
    public String replace(CharSequence input, String replacement) {
        return replace(input, m -> m.expand(replacement));
    }

    /**
     * Replaces every match with what a function makes of it, as {@code Regex.Replace} with a {@code MatchEvaluator}.
     *
     * @param input the text
     * @param evaluator the replacement of each match
     * @return the text with the matches replaced
     */
    public String replace(CharSequence input, Function<NetMatch, String> evaluator) {
        StringBuilder out = new StringBuilder();
        int last = 0;
        for (NetMatch m : matches(input)) {
            out.append(input, last, m.index()).append(evaluator.apply(m));
            last = m.index() + m.length();
        }
        return out.append(input, last, input.length()).toString();
    }

    /**
     * Splits the text around the matches, as {@code Regex.Split}: empty pieces are kept.
     *
     * @param input the text
     * @return the pieces
     */
    public String[] split(CharSequence input) {
        return pattern.split(input, -1);
    }

    private NetMatch toMatch(CharSequence input, Matcher m) {
        Map<String, List<NetCapture>> captures = new LinkedHashMap<>();
        groups.forEach((name, javaGroups) -> {
            List<NetCapture> list = captures.computeIfAbsent(name, n -> new ArrayList<>());
            for (String g : javaGroups) {
                if (m.start(g) >= 0) {
                    list.add(new NetCapture(input.subSequence(m.start(g), m.end(g)).toString(), m.start(g)));
                }
            }
        });
        for (Repeat r : repeats) {
            if (m.start(r.javaGroup()) < 0) {
                continue;
            }
            for (NetMatch one : r.body().repetitions(input, m.start(r.javaGroup()), m.end(r.javaGroup()))) {
                one.captures().forEach((name, list) ->
                        captures.computeIfAbsent(name, n -> new ArrayList<>()).addAll(list));
            }
        }
        captures.values().forEach(list -> list.sort((a, b) -> Integer.compare(a.index(), b.index())));
        Map<String, List<NetCapture>> byNumber = new HashMap<>();
        for (int i = 0; i < numbered.size(); i++) {
            byNumber.put(String.valueOf(i + 1), captures.getOrDefault(numbered.get(i), List.of()));
        }
        captures.putAll(byNumber);
        return new NetMatch(input.subSequence(m.start(), m.end()).toString(), m.start(), captures);
    }

    /**
     * The repetitions that make up {@code [start, end)}, one after another, as the main match made them: each one
     * as long as the regex likes it best, unless what is left then cannot be made of repetitions (as when
     * {@code (\\d{3}|\\d{4})} must take four digits for what follows the repeated part to match).
     */
    private List<NetMatch> repetitions(CharSequence input, int start, int end) {
        List<NetMatch> all = new ArrayList<>();
        tile(input, start, end, all, new java.util.HashSet<>());
        return all;
    }

    private boolean tile(CharSequence input, int at, int end, List<NetMatch> out, java.util.Set<Integer> dead) {
        if (at == end) {
            return true;
        }
        if (dead.contains(at)) {
            return false;
        }
        Matcher m = pattern.matcher(input);
        m.useTransparentBounds(true);
        m.useAnchoringBounds(false);
        m.region(at, end);
        int preferred = m.lookingAt() && m.end() > at ? m.end() : -1;
        if (preferred > 0) {
            out.add(toMatch(input, m));
            if (tile(input, preferred, end, out, dead)) {
                return true;
            }
            out.remove(out.size() - 1);
        }
        for (int to = end; to > at; to--) {
            if (to == preferred) {
                continue;
            }
            m.region(at, to);
            if (m.matches()) {
                out.add(toMatch(input, m));
                if (tile(input, to, end, out, dead)) {
                    return true;
                }
                out.remove(out.size() - 1);
            }
        }
        dead.add(at);
        return false;
    }

    /** Removes the spaces and {@code #} comments of {@code IgnorePatternWhitespace}, outside character classes. */
    private static String stripWhitespace(String p) {
        StringBuilder out = new StringBuilder();
        boolean inClass = false;
        for (int i = 0; i < p.length(); i++) {
            char c = p.charAt(i);
            if (c == '\\' && i + 1 < p.length()) {
                out.append(c).append(p.charAt(++i));
            } else if (inClass) {
                inClass = c != ']';
                out.append(c);
            } else if (c == '[') {
                inClass = true;
                out.append(c);
            } else if (c == '#') {
                while (i < p.length() && p.charAt(i) != '\n') {
                    i++;
                }
            } else if (!Character.isWhitespace(c)) {
                out.append(c);
            }
        }
        return out.toString();
    }

    /** Turns a .NET pattern into a Java one, group by group. */
    private static final class Translator {

        private final String p;
        private final int options;
        private int pos;
        private int lookbehind;
        private int javaNames;
        private int unnamed;
        private final Map<String, List<String>> groups = new LinkedHashMap<>();
        private final List<Repeat> repeats = new ArrayList<>();
        private final List<String> named = new ArrayList<>();
        private final List<String> numberedUnnamed = new ArrayList<>();

        Translator(String p, int options) {
            this.p = p;
            this.options = options & ~IGNORE_PATTERN_WHITESPACE;
        }

        NetRegex translate(String original, int flags) {
            String java = sequence(false);
            List<String> numbered = new ArrayList<>(numberedUnnamed);
            named.stream().filter(n -> !numbered.contains(n)).forEach(numbered::add);
            return new NetRegex(original, Pattern.compile(java, flags), groups, repeats, numbered);
        }

        /** Everything up to the closing parenthesis of the current group (or the end). */
        private String sequence(boolean inRepeat) {
            StringBuilder out = new StringBuilder();
            while (pos < p.length() && p.charAt(pos) != ')') {
                char c = p.charAt(pos);
                if (c == '\\') {
                    out.append(escape());
                } else if (c == '[') {
                    out.append(characterClass());
                } else if (c == '(') {
                    int groupStart = pos;
                    int afterGroup = groupEnd(groupStart);
                    boolean repeated = !inRepeat && lookbehind == 0 && repeats(quantifierAt(afterGroup))
                            && hasCaptures(groupStart);
                    String group = group(inRepeat || repeated);
                    String quantifier = quantifier();
                    if (repeated) {
                        String wrapper = "r" + (++javaNames);
                        String body = p.substring(groupStart, afterGroup);
                        repeats.add(new Repeat(wrapper, compile(body, options)));
                        out.append("(?<").append(wrapper).append('>').append(group).append(quantifier).append(')');
                    } else {
                        out.append(group).append(quantifier);
                    }
                    continue;
                } else if (c == '{' && !isQuantifier(pos)) {
                    out.append("\\{");
                    pos++;
                } else if ((c == '+' || c == '*') && lookbehind > 0) {
                    out.append(c == '+' ? "{1," + LOOKBEHIND_MAX + "}" : "{0," + LOOKBEHIND_MAX + "}");
                    pos++;
                } else if (c == '{' && lookbehind > 0 && p.startsWith(",}", p.indexOf(',', pos))) {
                    int comma = p.indexOf(',', pos);
                    out.append(p, pos, comma + 1).append(LOOKBEHIND_MAX).append('}');
                    pos = comma + 2;
                } else {
                    out.append(c);
                    pos++;
                }
            }
            return out.toString();
        }

        /** A group, from its {@code (} to its {@code )}, translated. */
        private String group(boolean inRepeat) {
            pos++;
            String head;
            String name = null;
            boolean behind = false;
            if (p.startsWith("?<=", pos) || p.startsWith("?<!", pos)) {
                head = "(" + p.substring(pos, pos + 3);
                pos += 3;
                behind = true;
            } else if (p.startsWith("?<", pos) || p.startsWith("?'", pos)) {
                char close = p.charAt(pos + 1) == '<' ? '>' : '\'';
                int end = p.indexOf(close, pos + 2);
                name = p.substring(pos + 2, end);
                pos = end + 1;
                head = null;
            } else if (p.startsWith("?", pos)) {
                int end = pos + 1;
                while (end < p.length() && p.charAt(end) != ':' && p.charAt(end) != ')' && p.charAt(end) != '='
                        && p.charAt(end) != '!' && p.charAt(end) != '>') {
                    end++;
                }
                head = "(" + p.substring(pos, Math.min(end + 1, p.length()));
                pos = Math.min(end + 1, p.length());
                if (p.charAt(end) == ')') {
                    return head;
                }
            } else {
                name = String.valueOf(++unnamed);
                numberedUnnamed.add(name);
                head = null;
            }
            if (name != null) {
                String java = "g" + (++javaNames);
                if (!inRepeat) {
                    groups.computeIfAbsent(name, n -> new ArrayList<>()).add(java);
                }
                if (!Character.isDigit(name.charAt(0)) && !named.contains(name)) {
                    named.add(name);
                }
                head = "(?<" + java + ">";
            }
            if (behind) {
                lookbehind++;
            }
            String inside = sequence(inRepeat);
            if (behind) {
                lookbehind--;
            }
            pos++;
            return head + inside + ")";
        }

        /** The quantifier at a position, without moving on. */
        private String quantifierAt(int at) {
            int saved = pos;
            int savedBehind = lookbehind;
            pos = at;
            lookbehind = 0;
            String q = quantifier();
            pos = saved;
            lookbehind = savedBehind;
            return q;
        }

        /** The quantifier right after a group, if any. */
        private String quantifier() {
            int start = pos;
            if (pos < p.length() && (p.charAt(pos) == '*' || p.charAt(pos) == '+' || p.charAt(pos) == '?')) {
                pos++;
            } else if (pos < p.length() && p.charAt(pos) == '{' && isQuantifier(pos)) {
                pos = p.indexOf('}', pos) + 1;
            } else {
                return "";
            }
            if (pos < p.length() && (p.charAt(pos) == '?' || p.charAt(pos) == '+')) {
                pos++;
            }
            String q = p.substring(start, pos);
            if (lookbehind > 0) {
                q = q.replace("+", "{1," + LOOKBEHIND_MAX + "}").replace("*", "{0," + LOOKBEHIND_MAX + "}");
            }
            return q;
        }

        private static boolean repeats(String quantifier) {
            if (quantifier.isEmpty() || quantifier.startsWith("?")) {
                return false;
            }
            if (quantifier.startsWith("{")) {
                String inside = quantifier.substring(1, quantifier.indexOf('}'));
                String[] bounds = inside.split(",", -1);
                return bounds.length == 2 && (bounds[1].isEmpty() || Integer.parseInt(bounds[1]) > 1)
                        || bounds.length == 1 && Integer.parseInt(bounds[0]) > 1;
            }
            return true;
        }

        /** Whether the group starting at {@code start} has a capturing group in it, itself included. */
        private boolean hasCaptures(int start) {
            int end = groupEnd(start);
            for (int i = start; i < end; i++) {
                char c = p.charAt(i);
                if (c == '\\') {
                    i++;
                } else if (c == '[') {
                    i = classEnd(i) - 1;
                } else if (c == '(' && (i + 1 >= end || p.charAt(i + 1) != '?'
                        || (p.startsWith("?<", i + 1) && !p.startsWith("?<=", i + 1) && !p.startsWith("?<!", i + 1))
                        || p.startsWith("?'", i + 1))) {
                    return true;
                }
            }
            return false;
        }

        /** Where the group starting at {@code start} ends, after its {@code )}. */
        private int groupEnd(int start) {
            int depth = 0;
            for (int i = start; i < p.length(); i++) {
                char c = p.charAt(i);
                if (c == '\\') {
                    i++;
                } else if (c == '[') {
                    i = classEnd(i) - 1;
                } else if (c == '(') {
                    depth++;
                } else if (c == ')' && --depth == 0) {
                    return i + 1;
                }
            }
            return p.length();
        }

        /** Where the character class starting at {@code start} ends, after its {@code ]}. */
        private int classEnd(int start) {
            int i = start + 1;
            if (i < p.length() && p.charAt(i) == '^') {
                i++;
            }
            if (i < p.length() && p.charAt(i) == ']') {
                i++;
            }
            while (i < p.length() && p.charAt(i) != ']') {
                i += p.charAt(i) == '\\' ? 2 : 1;
            }
            return Math.min(i + 1, p.length());
        }

        private String characterClass() {
            int end = classEnd(pos);
            StringBuilder out = new StringBuilder("[");
            int i = pos + 1;
            if (p.charAt(i) == '^') {
                out.append('^');
                i++;
            }
            if (p.charAt(i) == ']') {
                out.append("\\]");
                i++;
            }
            for (; i < end - 1; i++) {
                char c = p.charAt(i);
                if (c == '\\') {
                    out.append(blockName(p.substring(i, Math.min(i + 2, end - 1)), i));
                    i = afterEscape(i) - 1;
                } else if (c == '[' || c == '&') {
                    out.append('\\').append(c);
                } else {
                    out.append(c);
                }
            }
            pos = end;
            return out.append(']').toString();
        }

        private String escape() {
            int end = afterEscape(pos);
            String text = blockName(p.substring(pos, Math.min(pos + 2, p.length())), pos);
            if (p.startsWith("\\k<", pos) || p.startsWith("\\k'", pos)) {
                String name = p.substring(pos + 3, end - 1);
                List<String> java = groups.get(name);
                text = java == null ? p.substring(pos, end) : "\\k<" + java.get(0) + ">";
            }
            pos = end;
            return text;
        }

        /** An escape as Java writes it: {@code \p{IsX}} (a .NET block) as {@code \p{InX}}; others as they are. */
        private String blockName(String start, int at) {
            int end = afterEscape(at);
            String text = p.substring(at, end);
            if ((start.equals("\\p") || start.equals("\\P")) && text.startsWith("{Is", 2)) {
                return text.substring(0, 3) + "In" + text.substring(5);
            }
            return text;
        }

        private int afterEscape(int at) {
            if (at + 1 >= p.length()) {
                return p.length();
            }
            char c = p.charAt(at + 1);
            if ((c == 'p' || c == 'P') && at + 2 < p.length() && p.charAt(at + 2) == '{') {
                return p.indexOf('}', at) + 1;
            }
            if (c == 'k' && at + 2 < p.length() && (p.charAt(at + 2) == '<' || p.charAt(at + 2) == '\'')) {
                return p.indexOf(p.charAt(at + 2) == '<' ? '>' : '\'', at + 3) + 1;
            }
            if (c == 'u') {
                return at + 6;
            }
            if (c == 'x') {
                return at + 4;
            }
            return at + 2;
        }

        private boolean isQuantifier(int at) {
            int end = p.indexOf('}', at);
            return end > at + 1 && p.substring(at + 1, end).matches("\\d+(,\\d*)?");
        }
    }
}
