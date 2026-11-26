package com.catacumbas69.catasonarr.service;

import com.catacumbas69.catasonarr.media.MediaInfoFormatter;
import com.catacumbas69.catasonarr.model.CustomFormat;
import com.catacumbas69.catasonarr.model.Episode;
import com.catacumbas69.catasonarr.model.EpisodeFile;
import com.catacumbas69.catasonarr.model.MediaInfo;
import com.catacumbas69.catasonarr.model.NamingConfig;
import com.catacumbas69.catasonarr.parser.IsoLanguages;
import com.catacumbas69.catasonarr.parser.ParserText;
import com.catacumbas69.catasonarr.quality.QualityModel;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Episode file names, translated from Sonarr 4.0.20's {@code FileNameBuilder.BuildFileName} (GPL-3.0, orch.md
 * decision 122): the standard, daily or anime format with every token Sonarr has (series, season and episode with
 * the six multi-episode styles, absolute numbers, air date, episode titles shortened to fit 255 bytes, quality,
 * MediaInfo, release group, custom formats and ids), and the same cleaning as the folder names
 * ({@link FolderNames}).
 */
@SuppressWarnings("checkstyle:nestedifdepth")
public final class EpisodeFileNames {

    /**
     * What a file name is made of.
     *
     * @param series the series' names and ids
     * @param seriesType {@code standard}, {@code daily} or {@code anime}
     * @param episodes the file's episodes
     * @param file the file (quality, group, media info, scene name)
     * @param qualityTitle the quality definition's title, e.g. {@code WEBDL-1080p}
     * @param customFormats the file's custom formats
     */
    public record Input(FolderNames.Names series, String seriesType, List<Episode> episodes, EpisodeFile file,
                        String qualityTitle, List<CustomFormat> customFormats) {
    }

    private static final int MAX_FILE_NAME = 255;
    private static final Pattern EPISODE = Pattern.compile("(?<episode>\\{episode(?::0+)?})", Pattern.CASE_INSENSITIVE);
    private static final Pattern SEASON = Pattern.compile("(?<season>\\{season(?::0+)?})", Pattern.CASE_INSENSITIVE);
    private static final Pattern ABSOLUTE = Pattern.compile("(?<absolute>\\{absolute(?::0+)?})",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern SEASON_EPISODE = Pattern.compile("(?<separator>(?<=})[- ._]+?)?(?<seasonEpisode>s?"
            + "\\{season(?::0+)?}(?<episodeSeparator>[- ._]?[ex])(?<episode>\\{episode(?::0+)?}))(?<separator2>[- ._]+?"
            + "(?=\\{))?", Pattern.CASE_INSENSITIVE);
    private static final Pattern ABSOLUTE_EPISODE = Pattern.compile("(?<separator>(?<=})[- ._]+?)?(?<absolute>"
            + "\\{absolute(?::0+)?})(?<separator2>[- ._]+?(?=\\{))?", Pattern.CASE_INSENSITIVE);
    private static final Pattern AIR_DATE = Pattern.compile("\\{Air(\\s|\\W|_)Date}", Pattern.CASE_INSENSITIVE);
    private static final Pattern TRIM_SEPARATORS = Pattern.compile("[- ._]+$");
    private static final Pattern MULTI_PART = Pattern.compile("(?::?\\s?(?:\\(\\d+\\)|(Part|Pt\\.?)\\s?\\d+))$",
            Pattern.CASE_INSENSITIVE);

    private EpisodeFileNames() {
    }

    /**
     * The file's name in the season folder, with its extension; the original name when renaming is off.
     *
     * @param naming the naming
     * @param in what the name is made of
     * @param extension e.g. {@code .mkv}
     * @return the name (may hold {@code /} when the format makes folders)
     */
    public static String fileName(NamingConfig naming, Input in, String extension) {
        if (!naming.renameEpisodes()) {
            return originalTitle(in.file(), true, naming) + extension;
        }
        List<Episode> episodes = in.episodes().stream()
                .sorted(Comparator.comparingInt(Episode::seasonNumber).thenComparingInt(Episode::episodeNumber))
                .toList();
        String pattern = naming.standardEpisodeFormat();
        if ("daily".equals(in.seriesType()) && episodes.get(0).seasonNumber() > 0) {
            pattern = naming.dailyEpisodeFormat();
        }
        if ("anime".equals(in.seriesType()) && episodes.stream().allMatch(e -> e.absoluteEpisodeNumber() != null)) {
            pattern = naming.animeEpisodeFormat();
        }
        if (pattern == null || pattern.isBlank()) {
            throw new IllegalStateException("The " + in.seriesType() + " episode format cannot be empty");
        }
        String[] parts = pattern.split("[\\\\/]+");
        List<String> components = new ArrayList<>();
        for (int i = 0; i < parts.length; i++) {
            if (parts[i].isEmpty()) {
                continue;
            }
            Map<String, Function<String, String>> tokens = new HashMap<>();
            boolean hasEpisodeIdentifier = SEASON_EPISODE.matcher(parts[i]).find()
                    || ABSOLUTE_EPISODE.matcher(parts[i]).find() || AIR_DATE.matcher(parts[i]).find();
            String part = seasonEpisodeTokens(parts[i], tokens, episodes, naming);
            part = absoluteTokens(part, tokens, in.seriesType(), episodes, naming);
            part = part.replace("...", "{{ellipsis}}");
            tokens.putAll(FolderNames.seriesTokens(in.series()));
            tokens.put("airdate", f -> episodes.get(0).airDate() != null && !episodes.get(0).airDate().isBlank()
                    ? episodes.get(0).airDate().replace('-', ' ') : "Unknown");
            tokens.put("episodetitle", f -> null);
            tokens.put("episodecleantitle", f -> null);
            fileTokens(tokens, in.file(), !hasEpisodeIdentifier || in.file().id() == 0, naming);
            qualityTokens(tokens, in);
            mediaInfoTokens(tokens, in.file());
            customFormatTokens(tokens, in.customFormats());
            String component = replaceTokens(part, tokens, naming, true).strip();
            int max = MAX_FILE_NAME - (i == parts.length - 1 ? bytes(extension) : 0);
            int maxTitle = max - lengthWithoutTitle(component, naming);
            List<String> titles = titles(episodes);
            tokens.put("episodetitle", f -> episodeTitle(titles, "+", maxTitle, f));
            tokens.put("episodecleantitle", f -> episodeTitle(titles.stream().map(FolderNames::cleanTitle).toList(),
                    "and", maxTitle, f));
            component = replaceTokens(component, tokens, naming, false).strip();
            component = FolderNames.CLEANUP.matcher(component).replaceAll("$1");
            component = TRIM_SEPARATORS.matcher(component).replaceAll("");
            component = component.replace("{ellipsis}", "...");
            Matcher reserved = FolderNames.RESERVED.matcher(component);
            if (reserved.find()) {
                component = reserved.group().replace(".", "_") + component.substring(reserved.end());
            }
            components.add(component);
        }
        return String.join("/", components) + extension;
    }

    private static String seasonEpisodeTokens(String pattern, Map<String, Function<String, String>> tokens,
                                              List<Episode> episodes, NamingConfig naming) {
        Matcher m = SEASON_EPISODE.matcher(pattern);
        List<String[]> formats = new ArrayList<>();
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        while (m.find()) {
            if (seen.add(m.group("seasonEpisode"))) {
                String separator = m.group("separator2") != null ? m.group("separator2") : nz(m.group("separator"));
                formats.add(new String[] {separator, m.group("seasonEpisode"),
                        m.group("episodeSeparator"), m.group("episode")});
            }
        }
        int index = 1;
        for (String[] f : formats) {
            String separator = f[0];
            String seasonEpisode = f[1];
            String episodeSeparator = f[2];
            String episodePattern = f[3];
            String rendered = switch (naming.multiEpisodeStyle()) {
                case 1 -> numbers(seasonEpisode, separator + seasonEpisode, episodes);
                case 2 -> numbers(seasonEpisode, episodeSeparator + episodePattern, episodes);
                case 3 -> numbers(seasonEpisode, "-" + episodeSeparator + episodePattern, episodes);
                case 4 -> numbers(seasonEpisode, "-" + episodePattern, range(episodes));
                case 5 -> numbers(seasonEpisode, "-" + episodeSeparator + episodePattern, range(episodes));
                default -> numbers(seasonEpisode, "-" + episodePattern, episodes);
            };
            String key = "seasonepisode" + index++;
            pattern = pattern.replace(seasonEpisode, "{Season Episode" + key.substring(13) + "}");
            tokens.put(key, x -> rendered);
        }
        int season = episodes.get(0).seasonNumber();
        tokens.put("season", f -> FolderNames.number(season, f));
        int first = episodes.get(0).episodeNumber();
        int last = episodes.get(episodes.size() - 1).episodeNumber();
        tokens.put("episode", f -> episodes.size() > 1 ? FolderNames.number(first, f) + "-"
                + FolderNames.number(last, f) : FolderNames.number(first, f));
        return pattern;
    }

    private static String absoluteTokens(String pattern, Map<String, Function<String, String>> tokens, String type,
                                         List<Episode> episodes, NamingConfig naming) {
        Matcher m = ABSOLUTE_EPISODE.matcher(pattern);
        List<String[]> formats = new ArrayList<>();
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        while (m.find()) {
            if (seen.add(m.group("absolute"))) {
                String last = m.group("separator2") != null ? m.group("separator2") : m.group("separator");
                String separator = last != null && !last.isBlank() ? last : "-";
                formats.add(new String[] {separator, m.group("absolute")});
            }
        }
        int index = 1;
        for (String[] f : formats) {
            if (!"anime".equals(type) || episodes.stream().anyMatch(e -> e.absoluteEpisodeNumber() == null)) {
                pattern = pattern.replace(f[1], "");
                continue;
            }
            String repeat = f[0].strip().isBlank() ? " " : f[0].strip();
            String rendered = switch (naming.multiEpisodeStyle()) {
                case 1 -> absolutes(f[1], f[0] + f[1], episodes);
                case 2 -> absolutes(f[1], repeat + f[1], episodes);
                case 4, 5 -> absolutes(f[1], "-" + f[1], range(episodes));
                default -> absolutes(f[1], "-" + f[1], episodes);
            };
            String key = "absolutepattern" + index;
            pattern = pattern.replace(f[1], "{Absolute Pattern" + index++ + "}");
            tokens.put(key, x -> rendered);
        }
        return pattern;
    }

    private static List<Episode> range(List<Episode> episodes) {
        return episodes.size() > 1 ? List.of(episodes.get(0), episodes.get(episodes.size() - 1))
                : List.of(episodes.get(0));
    }

    private static String numbers(String base, String format, List<Episode> episodes) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < episodes.size(); i++) {
            int n = episodes.get(i).episodeNumber();
            out.append(EPISODE.matcher(i == 0 ? base : format)
                    .replaceAll(r -> Matcher.quoteReplacement(numberToken(r.group("episode"), n))));
        }
        int season = episodes.get(0).seasonNumber();
        return SEASON.matcher(out.toString())
                .replaceAll(r -> Matcher.quoteReplacement(numberToken(r.group("season"), season)));
    }

    private static String absolutes(String base, String format, List<Episode> episodes) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < episodes.size(); i++) {
            int n = episodes.get(i).absoluteEpisodeNumber();
            out.append(ABSOLUTE.matcher(i == 0 ? base : format)
                    .replaceAll(r -> Matcher.quoteReplacement(numberToken(r.group("absolute"), n))));
        }
        int season = episodes.get(0).seasonNumber();
        return SEASON.matcher(out.toString())
                .replaceAll(r -> Matcher.quoteReplacement(numberToken(r.group("season"), season)));
    }

    private static String numberToken(String token, int value) {
        String[] split = token.replaceAll("^\\{|}$", "").split(":");
        return split.length == 1 ? Integer.toString(value) : FolderNames.number(value, split[1]);
    }

    private static void fileTokens(Map<String, Function<String, String>> tokens, EpisodeFile file, boolean fallback,
                                   NamingConfig naming) {
        tokens.put("originaltitle", f -> originalTitle(file, fallback, naming));
        tokens.put("originalfilename", f -> originalFileName(file, fallback));
        tokens.put("releasegroup", f -> file.releaseGroup() == null || file.releaseGroup().isBlank()
                ? (f == null ? "Sonarr" : f) : FolderNames.truncate(file.releaseGroup(), f));
        tokens.put("releasehash", f -> "");
    }

    private static void qualityTokens(Map<String, Function<String, String>> tokens, Input in) {
        QualityModel q = in.file().quality();
        String proper = q.revision.version > 1 ? ("anime".equals(in.seriesType()) ? "v" + q.revision.version
                : "Proper") : "";
        String real = q.revision.real > 0 ? "REAL" : "";
        tokens.put("qualityfull", f -> in.qualityTitle() + " " + proper + " " + real);
        tokens.put("qualitytitle", f -> in.qualityTitle());
        tokens.put("qualityproper", f -> proper);
        tokens.put("qualityreal", f -> real);
    }

    private static void mediaInfoTokens(Map<String, Function<String, String>> tokens, EpisodeFile file) {
        MediaInfo mi = file.mediaInfo();
        if (mi == null) {
            return;
        }
        String scene = file.sceneName() != null && !file.sceneName().isBlank() ? file.sceneName()
                : ParserText.fileName(file.relativePath());
        String video = nz(MediaInfoFormatter.videoCodec(mi, scene));
        String audio = nz(MediaInfoFormatter.audioCodec(mi, scene));
        java.math.BigDecimal channels = MediaInfoFormatter.audioChannels(mi);
        String channelText = channels.signum() > 0 ? channels.setScale(1, RoundingMode.HALF_UP).toPlainString() : "";
        List<String> audioLanguages = mi.audioLanguages() == null ? List.of() : mi.audioLanguages();
        List<String> subtitles = mi.subtitles() == null ? List.of() : mi.subtitles();
        String bitDepth = Integer.toString(mi.videoBitDepth() > 0 ? mi.videoBitDepth() : 8);
        tokens.put("mediainfovideo", f -> video);
        tokens.put("mediainfovideocodec", f -> video);
        tokens.put("mediainfovideobitdepth", f -> bitDepth);
        tokens.put("mediainfoaudio", f -> audio);
        tokens.put("mediainfoaudiocodec", f -> audio);
        tokens.put("mediainfoaudiochannels", f -> channelText);
        tokens.put("mediainfoaudiolanguages", f -> languages(audioLanguages, f, true));
        tokens.put("mediainfoaudiolanguagesall", f -> languages(audioLanguages, f, false));
        tokens.put("mediainfosubtitlelanguages", f -> languages(subtitles, f, false));
        tokens.put("mediainfosubtitlelanguagesall", f -> languages(subtitles, f, false));
        tokens.put("mediainfosimple", f -> video + " " + audio);
        tokens.put("mediainfofull", f -> video + " " + audio + languages(audioLanguages, f, true) + " "
                + languages(subtitles, f, false));
        tokens.put("mediainfovideodynamicrange", f -> MediaInfoFormatter.videoDynamicRange(mi));
        tokens.put("mediainfovideodynamicrangetype", f -> MediaInfoFormatter.videoDynamicRangeType(mi));
    }

    private static void customFormatTokens(Map<String, Function<String, String>> tokens, List<CustomFormat> formats) {
        List<CustomFormat> named = formats == null ? List.of()
                : formats.stream().filter(CustomFormat::includeCustomFormatWhenRenaming).toList();
        tokens.put("customformats", f -> {
            List<CustomFormat> list = named;
            if (f != null && !f.isBlank()) {
                List<String> filter = List.of((f.startsWith("-") ? f.substring(1) : f).split(","));
                list = named.stream().filter(c -> f.startsWith("-") != filter.contains(c.name())).toList();
            }
            return list.stream().map(CustomFormat::name).collect(Collectors.joining(" "));
        });
        tokens.put("customformat", f -> f == null || f.isBlank() ? "" : named.stream()
                .filter(c -> c.name().equals(f)).map(CustomFormat::name).findFirst().orElse(""));
    }

    /** Sonarr's {@code GetLanguagesToken}: ISO 639-1 in capitals, {@code [EN+DE]}, English alone left out. */
    private static String languages(List<String> mediaInfoLanguages, String filter, boolean skipEnglishOnly) {
        List<String> tokens = new ArrayList<>();
        for (String item : mediaInfoLanguages) {
            if (item != null && !item.isBlank() && !item.equals("und")) {
                String t = item.strip().toLowerCase(Locale.ROOT);
                t = IsoLanguages.ISO_639_BT.getOrDefault(t, t);
                IsoLanguages.IsoLanguage iso = IsoLanguages.find(t);
                Locale locale = Locale.forLanguageTag(t);
                String two = iso != null ? iso.twoLetterCode() : !locale.getLanguage().isEmpty()
                        && locale.getLanguage().length() == 2 ? locale.getLanguage() : item.strip();
                tokens.add(two.toUpperCase(Locale.ROOT));
            }
        }
        tokens = tokens.stream().distinct().collect(Collectors.toCollection(ArrayList::new));
        List<String> filtered = tokens;
        if (filter != null && !filter.isBlank()) {
            if (filter.startsWith("-")) {
                List<String> remove = List.of(filter.split("-"));
                filtered = tokens.stream().filter(t -> !remove.contains(t))
                        .collect(Collectors.toCollection(ArrayList::new));
            } else {
                List<String> keep = tokens;
                filtered = java.util.Arrays.stream(filter.split("\\+")).filter(keep::contains).distinct()
                        .collect(Collectors.toCollection(ArrayList::new));
            }
            if (filter.endsWith("+") && filtered.size() != tokens.size()) {
                filtered.add("--");
            }
        }
        if (skipEnglishOnly && filtered.size() == 1 && filtered.get(0).equals("EN")) {
            return "";
        }
        String response = String.join("+", filtered);
        return response.isBlank() ? "" : "[" + response + "]";
    }

    private static List<String> titles(List<Episode> episodes) {
        if (episodes.size() == 1) {
            return List.of(trimTitle(episodes.get(0).title()));
        }
        List<String> titles = episodes.stream().map(e -> trimTitle(e.title()))
                .map(t -> MULTI_PART.matcher(t).replaceAll("").strip()).distinct().toList();
        if (titles.stream().allMatch(String::isBlank)) {
            titles = episodes.stream().map(e -> trimTitle(e.title())).distinct().toList();
        }
        return titles;
    }

    private static String trimTitle(String title) {
        return title == null ? "" : FolderNames.trim(title, " .?", false, true);
    }

    private static String episodeTitle(List<String> titles, String separator, int maxLength, String formatter) {
        int max = maxLength;
        try {
            int fromFormat = formatter == null ? 0 : Integer.parseInt(formatter.strip());
            if (fromFormat > 0) {
                max = Math.min(max, fromFormat);
            }
        } catch (NumberFormatException ignored) {
            max = maxLength;
        }
        String sep = " " + separator.strip() + " ";
        String joined = String.join(sep, titles);
        if (bytes(joined) <= max) {
            return joined;
        }
        String first = titles.get(0);
        if (titles.size() >= 2) {
            String last = titles.get(titles.size() - 1);
            if (bytes(first) + bytes(last) + 3 <= max) {
                return FolderNames.trim(first, " .", false, true) + "{ellipsis}" + last;
            }
        }
        if (titles.size() > 1 && bytes(first) + 3 <= max) {
            return FolderNames.trim(first, " .", false, true) + "{ellipsis}";
        }
        if (titles.size() == 1 && bytes(first) <= max) {
            return first;
        }
        return FolderNames.trim(first.substring(0, Math.max(0, Math.min(first.length(), max - 3))), " .", false, true)
                + "{ellipsis}";
    }

    private static int lengthWithoutTitle(String pattern, NamingConfig naming) {
        Map<String, Function<String, String>> tokens = new HashMap<>();
        tokens.put("episodetitle", f -> "");
        tokens.put("episodecleantitle", f -> "");
        tokens.put("ellipsis", f -> "...");
        return bytes(replaceTokens(pattern, tokens, naming, false));
    }

    /** Sonarr's {@code ReplaceTokens}: a handler's {@code null} keeps the token for a second pass. */
    private static String replaceTokens(String pattern, Map<String, Function<String, String>> tokens,
                                        NamingConfig naming, boolean escape) {
        Matcher m = FolderNames.TOKEN.matcher(pattern);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            m.appendReplacement(out, Matcher.quoteReplacement(replace(m, tokens, naming, escape)));
        }
        m.appendTail(out);
        return out.toString();
    }

    private static String replace(Matcher m, Map<String, Function<String, String>> tokens, NamingConfig naming,
                                  boolean escape) {
        if (m.group("escaped") != null) {
            return escape ? m.group() : "{{".equals(m.group("escaped")) ? "{" : "}";
        }
        String token = m.group("token");
        String custom = m.group("customFormat");
        if (custom != null && custom.isBlank()) {
            custom = null;
        }
        String simple = FolderNames.SIMPLE_TOKEN.matcher(token).replaceAll("").toLowerCase(Locale.ROOT);
        Function<String, String> handler = tokens.getOrDefault(simple, f -> "");
        String text = handler.apply(custom);
        if (text == null) {
            return m.group();
        }
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
        text = FolderNames.cleanFileName(text, naming);
        if (!text.isBlank()) {
            text = m.group("prefix") + text + m.group("suffix");
        }
        return escape ? text.replace("{", "{{").replace("}", "}}") : text;
    }

    private static String originalTitle(EpisodeFile file, boolean fallback, NamingConfig naming) {
        String name = file.sceneName() == null || file.sceneName().isBlank() ? originalFileName(file, fallback)
                : file.sceneName();
        return FolderNames.cleanFileName(name, naming);
    }

    private static String originalFileName(EpisodeFile file, boolean fallback) {
        if (!fallback) {
            return "";
        }
        return ParserText.nameWithoutExtension(Objects.requireNonNullElse(file.relativePath(), ""));
    }

    private static int bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8).length;
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }
}
