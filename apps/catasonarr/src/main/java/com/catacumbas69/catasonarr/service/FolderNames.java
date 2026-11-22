package com.catacumbas69.catasonarr.service;

import com.catacumbas69.catasonarr.model.NamingConfig;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Series and season folder names, translated from Sonarr 4.0.20's {@code FileNameBuilder} (GPL-3.0, orch.md decisions
 * 109 and 112), so Catasonarr names folders as the Sonarr it replaces does, with the naming imported from it.
 */
public final class FolderNames {

    /**
     * What a folder name is made of.
     *
     * @param title the series' title
     * @param year the year of its first episode, or 0
     * @param tvdbId TheTVDB's id
     * @param tvMazeId TVmaze's id, or 0
     * @param tmdbId TMDB's id, or 0
     * @param imdbId IMDb's id, or {@code null}
     */
    public record Names(String title, int year, int tvdbId, int tvMazeId, int tmdbId, String imdbId) {
    }

    private static final Pattern TOKEN = Pattern.compile("(?<escaped>\\{\\{|\\}\\})|\\{(?<prefix>[- ._\\[(]*)"
            + "(?<token>(?:[a-z0-9]+)(?:(?<separator>[- ._]+)(?:[a-z0-9]+))?)(?::(?<customFormat>[ ,a-z0-9+-]+"
            + "(?<![- ])))?(?<suffix>[- ._)\\]]*)\\}", Pattern.CASE_INSENSITIVE);
    private static final Pattern SIMPLE_TOKEN = Pattern.compile("\\s|_|\\W");
    private static final Pattern CLEANUP = Pattern.compile("([- ._])(\\1)+");
    private static final Pattern TITLE_PREFIX =
            Pattern.compile("^(The|An|A) (.*?)((?: *\\([^)]+\\))*)$", Pattern.CASE_INSENSITIVE);
    private static final Pattern YEAR = Pattern.compile("\\(\\d{4}\\)$", Pattern.CASE_INSENSITIVE);
    private static final Pattern RESERVED =
            Pattern.compile("^(?:aux|com[1-9]|con|lpt[1-9]|nul|prn)\\.", Pattern.CASE_INSENSITIVE);
    private static final Pattern SCENIFY_REMOVE = Pattern.compile("(?<=\\s)(,|<|>|\\/|\\\\|;|:|'|\"|\\||`|’|~|!|\\?|@|$"
            + "|%|^|\\*|-|_|=){1}(?=\\s)|('|`|’|:|\\?|,)(?=(?:(?:s|m|t|ve|ll|d|re)\\s)|\\s|$)"
            + "|(\\(|\\)|\\[|\\]|\\{|\\})",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern SCENIFY_REPLACE = Pattern.compile("[\\/]", Pattern.CASE_INSENSITIVE);
    private static final String[] BAD = {"\\", "/", "<", ">", "?", "*", "|", "\""};
    private static final String[] GOOD = {"+", "+", "", "", "!", "-", "", ""};

    private FolderNames() {
    }

    /**
     * The series' folder name, from {@link NamingConfig#seriesFolderFormat()}.
     *
     * @param naming the naming
     * @param series the series
     * @return the folder name, e.g. {@code Andor (2022)}
     */
    public static String seriesFolder(NamingConfig naming, Names series) {
        return folder(naming.seriesFolderFormat(), naming, seriesTokens(series));
    }

    /**
     * A season's folder name, from {@link NamingConfig#seasonFolderFormat()} or, for season 0,
     * {@link NamingConfig#specialsFolderFormat()}.
     *
     * @param naming the naming
     * @param series the series
     * @param seasonNumber the season
     * @return the folder name, e.g. {@code Season 1}
     */
    public static String seasonFolder(NamingConfig naming, Names series, int seasonNumber) {
        Map<String, Function<String, String>> tokens = seriesTokens(series);
        tokens.put("season", format -> number(seasonNumber, format));
        String format = seasonNumber == 0 ? naming.specialsFolderFormat() : naming.seasonFolderFormat();
        return folder(format, naming, tokens);
    }

    /**
     * Sonarr's {@code CleanTitle}: {@code &} as {@code and}, without punctuation between words.
     *
     * @param title the title
     * @return the clean title
     */
    public static String cleanTitle(String title) {
        String t = title.replace("&", "and");
        t = SCENIFY_REPLACE.matcher(t).replaceAll(" ");
        return SCENIFY_REMOVE.matcher(t).replaceAll("");
    }

    /**
     * Sonarr's {@code TitleThe}: a leading article moved to the end, e.g. {@code Office, The}.
     *
     * @param title the title
     * @return the title
     */
    public static String titleThe(String title) {
        return TITLE_PREFIX.matcher(title).replaceAll("$2, $1$3");
    }

    /**
     * Sonarr's {@code TitleYear}: the year in brackets after the title, unless already there.
     *
     * @param title the title
     * @param year the year, or 0
     * @return the title
     */
    public static String titleYear(String title, int year) {
        if (year == 0 || YEAR.matcher(title).find()) {
            return title;
        }
        return title + " (" + year + ")";
    }

    private static Map<String, Function<String, String>> seriesTokens(Names s) {
        Map<String, Function<String, String>> t = new HashMap<>();
        String title = s.title();
        t.put("seriestitle", f -> truncate(title, f));
        t.put("seriescleantitle", f -> truncate(cleanTitle(title), f));
        t.put("seriestitleyear", f -> truncate(titleYear(title, s.year()), f));
        t.put("seriescleantitleyear", f -> truncate(cleanTitle(titleYear(title, s.year())), f));
        t.put("seriestitlewithoutyear", f -> truncate(titleWithoutYear(title), f));
        t.put("seriescleantitlewithoutyear", f -> truncate(cleanTitle(titleWithoutYear(title)), f));
        t.put("seriestitlethe", f -> truncate(titleThe(title), f));
        t.put("seriescleantitlethe", f -> truncate(cleanTitleThe(title), f));
        t.put("seriestitletheyear", f -> truncate(titleYear(titleThe(title), s.year()), f));
        t.put("seriescleantitletheyear", f -> truncate(cleanTitleTheYear(title, s.year()), f));
        t.put("seriestitlethewithoutyear", f -> truncate(titleWithoutYear(titleThe(title)), f));
        t.put("seriescleantitlethewithoutyear", f -> truncate(cleanTitleThe(titleWithoutYear(title)), f));
        t.put("seriestitlefirstcharacter", f -> truncate(firstCharacter(titleThe(title)), f));
        t.put("seriesyear", f -> Integer.toString(s.year()));
        t.put("imdbid", f -> s.imdbId() == null ? "" : s.imdbId());
        t.put("tvdbid", f -> Integer.toString(s.tvdbId()));
        t.put("tvmazeid", f -> s.tvMazeId() > 0 ? Integer.toString(s.tvMazeId()) : "");
        t.put("tmdbid", f -> s.tmdbId() > 0 ? Integer.toString(s.tmdbId()) : "");
        return t;
    }

    private static String folder(String format, NamingConfig naming, Map<String, Function<String, String>> tokens) {
        Matcher m = TOKEN.matcher(format);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            m.appendReplacement(out, Matcher.quoteReplacement(replace(m, naming, tokens)));
        }
        m.appendTail(out);
        String name = CLEANUP.matcher(out.toString()).replaceAll("$1");
        name = trim(name, " .", true, true);
        Matcher reserved = RESERVED.matcher(name);
        if (reserved.find()) {
            name = reserved.group().replace(".", "_") + name.substring(reserved.end());
        }
        return name.replace("{ellipsis}", "...");
    }

    private static String replace(Matcher m, NamingConfig naming, Map<String, Function<String, String>> tokens) {
        if (m.group("escaped") != null) {
            return "{{".equals(m.group("escaped")) ? "{" : "}";
        }
        String token = m.group("token");
        String custom = m.group("customFormat");
        if (custom != null && custom.isBlank()) {
            custom = null;
        }
        String simple = SIMPLE_TOKEN.matcher(token).replaceAll("").toLowerCase(Locale.ROOT);
        Function<String, String> handler = tokens.get(simple);
        String text = handler == null ? "" : handler.apply(custom);
        text = text.strip();
        if (token.chars().allMatch(c -> !Character.isLetter(c) || Character.isLowerCase(c))) {
            text = text.toLowerCase(Locale.ROOT);
        } else if (token.chars().allMatch(c -> !Character.isLetter(c) || Character.isUpperCase(c))) {
            text = text.toUpperCase(Locale.ROOT);
        }
        String separator = m.group("separator");
        if (separator != null && !separator.isBlank()) {
            text = text.replace(" ", separator);
        }
        text = cleanFileName(text, naming);
        if (!text.isBlank()) {
            text = m.group("prefix") + text + m.group("suffix");
        }
        return text;
    }

    private static String cleanFileName(String name, NamingConfig naming) {
        String result = name;
        if (naming.replaceIllegalCharacters()) {
            if (naming.colonReplacementFormat() == 4) {
                result = result.replace(": ", " - ").replace(":", "-");
            } else {
                String replacement = switch (naming.colonReplacementFormat()) {
                    case 1 -> "-";
                    case 2 -> " -";
                    case 3 -> " - ";
                    case 5 -> naming.customColonReplacementFormat() == null ? ""
                            : naming.customColonReplacementFormat();
                    default -> "";
                };
                result = result.replace(":", replacement);
            }
        } else {
            result = result.replace(":", "");
        }
        for (int i = 0; i < BAD.length; i++) {
            result = result.replace(BAD[i], naming.replaceIllegalCharacters() ? GOOD[i] : "");
        }
        result = trim(result, " .", true, false);
        return trim(result, " ", false, true);
    }

    private static String truncate(String input, String format) {
        if (input == null || input.isBlank()) {
            return "";
        }
        int max = 0;
        if (format != null) {
            try {
                max = Integer.parseInt(format.strip());
            } catch (NumberFormatException ignored) {
                max = 0;
            }
        }
        if (max == 0 || input.length() <= Math.abs(max)) {
            return input;
        }
        if (max < 0) {
            String end = input.substring(input.length() - (Math.abs(max) - 3));
            return "{ellipsis}" + trim(end, " .", true, false);
        }
        return trim(input.substring(0, max - 3), " .", false, true) + "{ellipsis}";
    }

    private static String number(int value, String format) {
        if (format != null && format.matches("0+")) {
            return String.format(Locale.ROOT, "%0" + format.length() + "d", value);
        }
        return Integer.toString(value);
    }

    private static String titleWithoutYear(String title) {
        return YEAR.matcher(title).replaceAll("").strip();
    }

    private static String cleanTitleThe(String title) {
        Matcher m = TITLE_PREFIX.matcher(title);
        if (m.matches()) {
            return cleanTitle(m.group(2)).strip() + ", " + m.group(1) + cleanTitle(m.group(3));
        }
        return cleanTitle(title);
    }

    private static String cleanTitleTheYear(String title, int year) {
        if (year == 0) {
            return cleanTitleThe(title);
        }
        Matcher m = YEAR.matcher(title);
        if (m.find()) {
            return cleanTitleThe(title.substring(0, m.start()).strip()) + " " + m.group().substring(1, 5);
        }
        return cleanTitleThe(title) + " " + year;
    }

    private static String firstCharacter(String title) {
        for (int i = 0; i < Math.min(2, title.length()); i++) {
            char c = title.charAt(i);
            if (Character.isLetterOrDigit(c)) {
                return String.valueOf(Character.toUpperCase(c));
            }
        }
        return "_";
    }

    private static String trim(String s, String chars, boolean start, boolean end) {
        int from = 0;
        int to = s.length();
        while (start && from < to && chars.indexOf(s.charAt(from)) >= 0) {
            from++;
        }
        while (end && to > from && chars.indexOf(s.charAt(to - 1)) >= 0) {
            to--;
        }
        return s.substring(from, to);
    }
}
