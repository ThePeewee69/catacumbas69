package com.catacumbas69.catasonarr.parser;

import com.catacumbas69.catasonarr.model.Language;
import com.catacumbas69.catasonarr.model.Languages;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The languages a release's name says, and a subtitle file's language, translated from Sonarr 4.0.20's
 * {@code LanguageParser.cs} (GPL-3.0, orch.md decision 122).
 */
@SuppressWarnings("checkstyle:linelength")
public final class LanguageParser {

    private static final RegexReplace[] CLEAN_SERIES_TITLE_REGEX = {
        new RegexReplace(".*?[_. ](S\\d{2}(?:E\\d{2,4})*[_. ].*)", "$1", NetRegex.IGNORE_CASE)
    };

    private static final NetRegex LANGUAGE_REGEX = NetRegex.compile("(?:\\W|_)(?<english>\\b(?:ing|eng)\\b)|(?<italian>\\b(?:ita|italian)\\b)|(?<german>german\\b|videomann|ger[. ]dub)|(?<flemish>flemish)|(?<greek>greek)|(?<french>(?:\\W|_)(?:FR|VF|VF2|VFF|VFI|VFQ|TRUEFRENCH|FRENCH)(?:\\W|_))|(?<russian>\\b(?:rus|ru)\\b)|(?<hungarian>\\b(?:HUNDUB|HUN)\\b)|(?<hebrew>\\bHebDub\\b)|(?<polish>\\b(?:PL\\W?DUB|DUB\\W?PL|LEK\\W?PL|PL\\W?LEK)\\b)|(?<chinese>\\[(?:CH[ST]|BIG5|GB)\\]|简|繁|字幕)|(?<bulgarian>\\bbgaudio\\b)|(?<spanish>\\b(?:español|castellano|esp|spa(?!\\(Latino\\)))\\b)|(?<ukrainian>\\b(?:\\dx?)?(?:ukr))|(?<thai>\\b(?:THAI)\\b)|(?<romanian>\\b(?:RoDubbed|ROMANIAN)\\b)|(?<catalan>[-,. ]cat[. ](?:DD|subs)|\\b(?:catalan|catalán)\\b)|(?<latvian>\\b(?:lat|lav|lv)\\b)|(?<turkish>\\b(?:tur)\\b)|(?<original>\\b(?:orig|original)\\b)", NetRegex.IGNORE_CASE);

    private static final NetRegex CASE_SENSITIVE_LANGUAGE_REGEX = NetRegex.compile("(?:(?i)(?<!SUB[\\W|_|^]))(?:(?<lithuanian>\\bLT\\b)|(?<czech>\\bCZ\\b)|(?<polish>\\bPL\\b)|(?<bulgarian>\\bBG\\b)|(?<slovak>\\bSK\\b))(?:(?i)(?![\\W|_|^]SUB))", 0);

    private static final NetRegex GERMAN_DUAL_LANGUAGE_REGEX = NetRegex.compile("(?<!WEB[-_. ]?)\\bDL\\b", NetRegex.IGNORE_CASE);
    private static final NetRegex GERMAN_MULTI_LANGUAGE_REGEX = NetRegex.compile("\\bML\\b", NetRegex.IGNORE_CASE);

    private static final NetRegex SUBTITLE_LANGUAGE_REGEX = NetRegex.compile(".+?([-_. ](?<tags>forced|foreign|default|cc|psdh|sdh))*[-_. ](?<iso_code>[a-z]{2,3})([-_. ](?<tags>forced|foreign|default|cc|psdh|sdh))*$", NetRegex.IGNORE_CASE);

    private static final NetRegex SUBTITLE_LANGUAGE_TITLE_REGEX = NetRegex.compile(".+?(\\.((?<tags1>forced|foreign|default|cc|psdh|sdh)|(?<iso_code>[a-z]{2,3})))*[-_. ](?<title>[^.]*)(\\.((?<tags2>forced|foreign|default|cc|psdh|sdh)|(?<iso_code>[a-z]{2,3})))*$", NetRegex.IGNORE_CASE);

    private static final NetRegex SUBTITLE_TITLE_REGEX = NetRegex.compile("^((?<title>.+) - )?(?<copy>(?<!\\d+)\\d{1,3}(?!\\d+))$", 0);

    /** Words that name a language anywhere in the title, in Sonarr's order. */
    private static final List<Map.Entry<List<String>, String>> WORDS = List.of(
            Map.entry(List.of("spanish"), "Spanish"),
            Map.entry(List.of("danish"), "Danish"),
            Map.entry(List.of("dutch"), "Dutch"),
            Map.entry(List.of("japanese"), "Japanese"),
            Map.entry(List.of("icelandic"), "Icelandic"),
            Map.entry(List.of("mandarin", "cantonese", "chinese"), "Chinese"),
            Map.entry(List.of("korean"), "Korean"),
            Map.entry(List.of("russian"), "Russian"),
            Map.entry(List.of("polish"), "Polish"),
            Map.entry(List.of("vietnamese"), "Vietnamese"),
            Map.entry(List.of("swedish"), "Swedish"),
            Map.entry(List.of("norwegian"), "Norwegian"),
            Map.entry(List.of("finnish"), "Finnish"),
            Map.entry(List.of("turkish"), "Turkish"),
            Map.entry(List.of("portuguese"), "Portuguese"),
            Map.entry(List.of("hungarian"), "Hungarian"),
            Map.entry(List.of("hebrew"), "Hebrew"),
            Map.entry(List.of("arabic"), "Arabic"),
            Map.entry(List.of("hindi"), "Hindi"),
            Map.entry(List.of("malayalam"), "Malayalam"),
            Map.entry(List.of("ukrainian"), "Ukrainian"),
            Map.entry(List.of("bulgarian"), "Bulgarian"),
            Map.entry(List.of("slovak"), "Slovak"),
            Map.entry(List.of("brazilian", "dublado"), "Portuguese (Brazil)"),
            Map.entry(List.of("latino"), "Spanish (Latino)"),
            Map.entry(List.of("latvian"), "Latvian"));

    /** Groups of {@link #LANGUAGE_REGEX} and their languages, in Sonarr's order. */
    private static final List<Map.Entry<String, String>> GROUPS = List.of(
            Map.entry("english", "English"),
            Map.entry("italian", "Italian"),
            Map.entry("german", "German"),
            Map.entry("flemish", "Flemish"),
            Map.entry("greek", "Greek"),
            Map.entry("french", "French"),
            Map.entry("russian", "Russian"),
            Map.entry("dutch", "Dutch"),
            Map.entry("hungarian", "Hungarian"),
            Map.entry("hebrew", "Hebrew"),
            Map.entry("polish", "Polish"),
            Map.entry("chinese", "Chinese"),
            Map.entry("bulgarian", "Bulgarian"),
            Map.entry("ukrainian", "Ukrainian"),
            Map.entry("spanish", "Spanish"),
            Map.entry("thai", "Thai"),
            Map.entry("romanian", "Romanian"),
            Map.entry("catalan", "Catalan"),
            Map.entry("latvian", "Latvian"),
            Map.entry("turkish", "Turkish"),
            Map.entry("original", "Original"));

    /** Groups of {@link #CASE_SENSITIVE_LANGUAGE_REGEX} and their languages. */
    private static final List<Map.Entry<String, String>> CASE_SENSITIVE_GROUPS = List.of(
            Map.entry("lithuanian", "Lithuanian"),
            Map.entry("czech", "Czech"),
            Map.entry("polish", "Polish"),
            Map.entry("bulgarian", "Bulgarian"),
            Map.entry("slovak", "Slovak"));

    private LanguageParser() {
    }

    /**
     * The languages of a release, Unknown when it names none; a German release with DL or ML adds Original (and
     * English for ML).
     *
     * @param title the release's title, or the part after its season and episode
     * @return the languages, without repeats
     */
    public static List<Language> parseLanguages(String title) {
        for (RegexReplace regex : CLEAN_SERIES_TITLE_REGEX) {
            boolean matched = regex.matches(title);
            title = regex.replace(title);
            if (matched) {
                break;
            }
        }
        String lowerTitle = title.toLowerCase(Locale.ROOT);
        List<Language> languages = new ArrayList<>();
        for (Map.Entry<List<String>, String> word : WORDS) {
            if (word.getKey().stream().anyMatch(lowerTitle::contains)) {
                languages.add(Languages.byName(word.getValue()));
            }
        }
        languages.addAll(regexLanguage(title));
        if (lowerTitle.contains("english")) {
            languages.add(Languages.byName("English"));
        }
        if (languages.isEmpty()) {
            languages.add(Languages.UNKNOWN);
        }
        if (languages.size() == 1 && languages.get(0).name().equals("German")) {
            if (GERMAN_DUAL_LANGUAGE_REGEX.isMatch(title)) {
                languages.add(Languages.ORIGINAL);
            } else if (GERMAN_MULTI_LANGUAGE_REGEX.isMatch(title)) {
                languages.add(Languages.ORIGINAL);
                languages.add(Languages.byName("English"));
            }
        }
        Map<Integer, Language> distinct = new LinkedHashMap<>();
        languages.forEach(l -> distinct.putIfAbsent(l.id(), l));
        return new ArrayList<>(distinct.values());
    }

    /**
     * A subtitle file's language, from its ISO code ({@code .en.srt}) or its language's name at the end.
     *
     * @param fileName the file's name
     * @return the language, or Unknown
     */
    public static Language parseSubtitleLanguage(String fileName) {
        try {
            String simpleFilename = ParserText.nameWithoutExtension(fileName);
            NetMatch languageMatch = SUBTITLE_LANGUAGE_REGEX.match(simpleFilename);
            if (languageMatch.success()) {
                IsoLanguages.IsoLanguage iso = IsoLanguages.find(languageMatch.group("iso_code").value()
                        .toLowerCase(Locale.ROOT));
                return iso == null ? Languages.UNKNOWN : iso.language();
            }
            for (Language language : Languages.ALL) {
                if (simpleFilename.toLowerCase(Locale.ROOT).endsWith(language.name().toLowerCase(Locale.ROOT))) {
                    return language;
                }
            }
        } catch (RuntimeException e) {
            return Languages.UNKNOWN;
        }
        return Languages.UNKNOWN;
    }

    /**
     * A subtitle file's language, tags and title, as Sonarr reads {@code Name.en.forced.srt} or
     * {@code Name.en.Commentary.srt}.
     *
     * @param fileName the file's name
     * @return what it says
     */
    public static SubtitleTitleInfo parseSubtitleLanguageInformation(String fileName) {
        String simpleFilename = ParserText.nameWithoutExtension(fileName);
        NetMatch matchTitle = SUBTITLE_LANGUAGE_TITLE_REGEX.match(simpleFilename);
        if (!matchTitle.group("title").success() || matchTitle.group("iso_code").captures().size() != 1) {
            return parseBasicSubtitle(fileName);
        }
        IsoLanguages.IsoLanguage iso = IsoLanguages.find(matchTitle.group("iso_code").value().toLowerCase(Locale.ROOT));
        List<String> tags = new ArrayList<>();
        for (String name : List.of("tags1", "tags2")) {
            matchTitle.group(name).captures().stream().map(NetCapture::value).filter(v -> !v.isEmpty())
                    .map(v -> v.toLowerCase(Locale.ROOT)).forEach(tags::add);
        }
        SubtitleTitleInfo info = new SubtitleTitleInfo();
        info.titleFirst = matchTitle.group("tags1").captures().isEmpty();
        info.languageTags = tags;
        info.rawTitle = matchTitle.group("title").value();
        info.language = iso == null ? Languages.UNKNOWN : iso.language();
        updateTitleAndCopyFromTitle(info);
        return info;
    }

    /**
     * A subtitle file's language and tags, without a title.
     *
     * @param fileName the file's name
     * @return what it says
     */
    public static SubtitleTitleInfo parseBasicSubtitle(String fileName) {
        SubtitleTitleInfo info = new SubtitleTitleInfo();
        info.titleFirst = false;
        info.languageTags = parseLanguageTags(fileName);
        info.language = parseSubtitleLanguage(fileName);
        return info;
    }

    /**
     * Splits a subtitle's raw title into its title and copy number ({@code Commentary - 2}).
     *
     * @param info the subtitle's information, changed
     */
    public static void updateTitleAndCopyFromTitle(SubtitleTitleInfo info) {
        if (info.rawTitle == null) {
            info.title = null;
            info.copy = 0;
            return;
        }
        NetMatch match = SUBTITLE_TITLE_REGEX.match(info.rawTitle);
        if (match.success()) {
            info.title = match.group("title").success() ? match.group("title").value() : null;
            info.copy = Integer.parseInt(match.group("copy").value());
        } else {
            info.title = info.rawTitle;
            info.copy = 0;
        }
    }

    /**
     * A subtitle file's tags ({@code forced}, {@code sdh}, …).
     *
     * @param fileName the file's name
     * @return the tags, in lower case
     */
    public static List<String> parseLanguageTags(String fileName) {
        try {
            NetMatch match = SUBTITLE_LANGUAGE_REGEX.match(ParserText.nameWithoutExtension(fileName));
            return match.group("tags").captures().stream().map(NetCapture::value).filter(v -> !v.isEmpty())
                    .map(v -> v.toLowerCase(Locale.ROOT)).toList();
        } catch (RuntimeException e) {
            return new ArrayList<>();
        }
    }

    private static List<Language> regexLanguage(String title) {
        List<Language> languages = new ArrayList<>();
        NetMatch caseSensitiveMatch = CASE_SENSITIVE_LANGUAGE_REGEX.match(title);
        for (Map.Entry<String, String> g : CASE_SENSITIVE_GROUPS) {
            if (caseSensitiveMatch.group(g.getKey()).success()) {
                languages.add(Languages.byName(g.getValue()));
            }
        }
        for (NetMatch match : LANGUAGE_REGEX.matches(title)) {
            for (Map.Entry<String, String> g : GROUPS) {
                if (match.group(g.getKey()).success()) {
                    languages.add(g.getValue().equals("Original") ? Languages.ORIGINAL : Languages.byName(g.getValue()));
                }
            }
        }
        return languages;
    }
}
