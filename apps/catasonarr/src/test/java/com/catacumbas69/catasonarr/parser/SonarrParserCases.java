package com.catacumbas69.catasonarr.parser;

import com.catacumbas69.catasonarr.quality.Qualities;
import com.catacumbas69.catasonarr.quality.Quality;
import com.catacumbas69.catasonarr.quality.QualityDetectionSource;
import com.catacumbas69.catasonarr.quality.QualityModel;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Sonarr 4.0.20's parser tests (GPL-3.0, orch.md decision 122): each test method of Sonarr's {@code ParserTests}
 * translated to a check, and run on the cases Sonarr gives it ({@code sonarr/parser-cases.json}, extracted from its
 * {@code [TestCase]} attributes). Kept free of JUnit so the same checks run anywhere.
 */
@SuppressWarnings("checkstyle:methodname")
public final class SonarrParserCases {

    /** A check of one case: its arguments, as Sonarr's {@code [TestCase]} gives them. */
    interface Check extends Consumer<List<Object>> {
    }

    static final Map<String, Check> CHECKS = new HashMap<>();

    private SonarrParserCases() {
    }

    static void check(String fixture, String method, List<Object> args) {
        Check check = CHECKS.get(fixture + "." + method);
        if (check == null) {
            throw new UnsupportedOperationException("no check for " + fixture + "." + method);
        }
        check.accept(args);
    }

    static boolean has(String fixture, String method) {
        return CHECKS.containsKey(fixture + "." + method);
    }

    // ── assertions ──────────────────────────────────────────────────────────

    static void eq(Object actual, Object expected, String what) {
        if (!Objects.equals(actual, expected)) {
            throw new AssertionError(what + ": expected <" + expected + "> but was <" + actual + ">");
        }
    }

    static void isTrue(boolean actual, String what) {
        if (!actual) {
            throw new AssertionError(what + " should be true");
        }
    }

    static void isFalse(boolean actual, String what) {
        if (actual) {
            throw new AssertionError(what + " should be false");
        }
    }

    static ParsedEpisodeInfo notNull(ParsedEpisodeInfo r, String title) {
        if (r == null) {
            throw new AssertionError("could not parse <" + title + ">");
        }
        return r;
    }

    static String s(List<Object> a, int i) {
        return (String) a.get(i);
    }

    static int i(List<Object> a, int i) {
        return ((Number) a.get(i)).intValue();
    }

    static int[] ints(List<Object> a, int i) {
        return ((List<?>) a.get(i)).stream().mapToInt(o -> ((Number) o).intValue()).toArray();
    }

    static String sorted(int[] values) {
        int[] copy = values.clone();
        Arrays.sort(copy);
        return Arrays.toString(copy);
    }

    static void add(String key, Check check) {
        CHECKS.put(key, check);
    }

    static {
        // ── SingleEpisodeParserFixture ─────────────────────────────────────
        Check single = a -> {
            ParsedEpisodeInfo r = notNull(Parser.parseTitle(s(a, 0)), s(a, 0));
            eq(r.episodeNumbers.length, 1, "episode count");
            eq(r.seasonNumber, i(a, 2), "season");
            eq(r.episodeNumbers[0], i(a, 3), "episode");
            eq(r.seriesTitle, s(a, 1), "title");
            eq(r.absoluteEpisodeNumbers.length, 0, "absolute count");
            isFalse(r.fullSeason, "full season");
        };
        add("SingleEpisodeParserFixture.should_parse_single_episode", single);
        add("SingleEpisodeParserFixture.should_parse_japanese_variety_show_format", single);
        add("SingleEpisodeParserFixture.should_parse_decimal_number_as_special", a -> {
            single.accept(a);
            isTrue(Parser.parseTitle(s(a, 0)).special, "special");
        });
        add("SingleEpisodeParserFixture.should_parse_split_episode", a -> {
            single.accept(a);
            isTrue(Parser.parseTitle(s(a, 0)).isSplitEpisode, "split episode");
        });
        add("MiniSeriesEpisodeParserFixture.should_parse_mini_series_episode", a -> {
            ParsedEpisodeInfo r = notNull(Parser.parseTitle(s(a, 0)), s(a, 0));
            eq(r.episodeNumbers.length, 1, "episode count");
            eq(r.seasonNumber, 1, "season");
            eq(r.episodeNumbers[0], i(a, 2), "episode");
            eq(r.seriesTitle, s(a, 1), "title");
            eq(r.absoluteEpisodeNumbers.length, 0, "absolute count");
            isFalse(r.fullSeason, "full season");
        });
        add("MiniSeriesEpisodeParserFixture.should_parse_korean_series_episode",
                CHECKS.get("MiniSeriesEpisodeParserFixture.should_parse_mini_series_episode"));

        // ── MultiEpisodeParserFixture ──────────────────────────────────────
        add("MultiEpisodeParserFixture.should_parse_multiple_episodes", a -> {
            ParsedEpisodeInfo r = notNull(Parser.parseTitle(s(a, 0)), s(a, 0));
            eq(r.seasonNumber, i(a, 2), "season");
            eq(sorted(r.episodeNumbers), sorted(ints(a, 3)), "episodes");
            eq(r.seriesTitle, s(a, 1), "title");
            eq(r.absoluteEpisodeNumbers.length, 0, "absolute count");
            isFalse(r.fullSeason, "full season");
        });

        // ── AbsoluteEpisodeNumberParserFixture ─────────────────────────────
        add("AbsoluteEpisodeNumberParserFixture.should_parse_absolute_numbers", a -> {
            ParsedEpisodeInfo r = notNull(Parser.parseTitle(s(a, 0)), s(a, 0));
            eq(r.absoluteEpisodeNumbers.length, 1, "absolute count");
            eq(r.absoluteEpisodeNumbers[0], i(a, 2), "absolute");
            eq(r.seasonNumber, i(a, 3), "season");
            isTrue(r.episodeNumbers.length <= 1, "at most one episode");
            eq(r.episodeNumbers.length == 0 ? 0 : r.episodeNumbers[0], i(a, 4), "episode");
            eq(r.seriesTitle, s(a, 1), "title");
            isFalse(r.fullSeason, "full season");
        });
        add("AbsoluteEpisodeNumberParserFixture.should_parse_absolute_specials", a -> {
            ParsedEpisodeInfo r = notNull(Parser.parseTitle(s(a, 0)), s(a, 0));
            eq(r.absoluteEpisodeNumbers.length, 1, "absolute count");
            eq(r.absoluteEpisodeNumbers[0], i(a, 2), "absolute");
            eq(r.seasonNumber, 0, "season");
            eq(r.episodeNumbers.length == 0 ? 0 : r.episodeNumbers[0], 0, "episode");
            eq(r.seriesTitle, s(a, 1), "title");
            isFalse(r.fullSeason, "full season");
            isTrue(r.special, "special");
        });
        add("AbsoluteEpisodeNumberParserFixture.should_parse_multi_episode_absolute_numbers", a -> {
            ParsedEpisodeInfo r = notNull(Parser.parseTitle(s(a, 0)), s(a, 0));
            int[] expected = java.util.stream.IntStream.rangeClosed(i(a, 2), i(a, 3)).toArray();
            eq(sorted(r.absoluteEpisodeNumbers), sorted(expected), "absolute");
            eq(r.seriesTitle, s(a, 1), "title");
            isFalse(r.fullSeason, "full season");
        });
        add("AbsoluteEpisodeNumberParserFixture.should_parse_anime_season_packs", a -> {
            ParsedEpisodeInfo r = notNull(Parser.parseTitle(s(a, 0)), s(a, 0));
            eq(r.absoluteEpisodeNumbers.length, 0, "absolute count");
            eq(r.seriesTitle, s(a, 1), "title");
            isTrue(r.fullSeason, "full season");
            eq(r.seasonNumber, i(a, 2), "season");
        });
        add("AbsoluteEpisodeNumberParserFixture.should_handle_anime_recap_numbering", a -> {
            ParsedEpisodeInfo r = notNull(Parser.parseTitle(s(a, 0)), s(a, 0));
            eq(r.seriesTitle, s(a, 1), "title");
            eq(r.absoluteEpisodeNumbers.length, 0, "absolute count");
            eq(r.specialAbsoluteEpisodeNumbers.length, 1, "special absolute count");
            eq(r.specialAbsoluteEpisodeNumbers[0].compareTo(new BigDecimal(a.get(2).toString())), 0, "special absolute");
            isFalse(r.fullSeason, "full season");
        });
        add("AbsoluteEpisodeNumberParserFixture.should_handle_ambiguously_named_anime_files_in_batch_release", a -> {
            ParsedEpisodeInfo r = notNull(Parser.parsePath("/Test/" + s(a, 0) + "/" + s(a, 1)), s(a, 1));
            eq(r.absoluteEpisodeNumbers.length, 1, "absolute count");
            eq(r.absoluteEpisodeNumbers[0], i(a, 3), "absolute");
            eq(r.seasonNumber, 0, "season");
            eq(r.episodeNumbers.length, 0, "episode count");
            eq(r.seriesTitle, s(a, 2), "title");
            isFalse(r.fullSeason, "full season");
        });
        add("AbsoluteEpisodeNumberParserFixture.should_parse_absolute_followed_by_standard_as_standard", a -> {
            ParsedEpisodeInfo r = notNull(Parser.parseTitle(s(a, 0)), s(a, 0));
            eq(r.episodeNumbers.length, 1, "episode count");
            eq(r.seasonNumber, i(a, 3), "season");
            eq(r.episodeNumbers[0], i(a, 4), "episode");
            eq(r.seriesTitle, s(a, 1), "title");
            eq(r.specialAbsoluteEpisodeNumbers.length, 1, "special absolute count");
            eq(r.specialAbsoluteEpisodeNumbers[0].compareTo(new BigDecimal(a.get(2).toString())), 0, "special absolute");
            eq(r.absoluteEpisodeNumbers.length, 0, "absolute count");
            isFalse(r.fullSeason, "full season");
        });

        // ── DailyEpisodeParserFixture ──────────────────────────────────────
        Check daily = a -> {
            ParsedEpisodeInfo r = notNull(Parser.parseTitle(s(a, 0)), s(a, 0));
            eq(r.seriesTitle, s(a, 1), "title");
            eq(r.airDate, LocalDate.of(i(a, 2), i(a, 3), i(a, 4)).toString(), "air date");
            eq(r.episodeNumbers.length, 0, "episode count");
            eq(r.absoluteEpisodeNumbers.length, 0, "absolute count");
            isFalse(r.fullSeason, "full season");
        };
        add("DailyEpisodeParserFixture.should_parse_daily_episode", daily);
        add("DailyEpisodeParserFixture.should_parse_daily_episode_with_multiple_parts", a -> {
            daily.accept(a);
            eq(Parser.parseTitle(s(a, 0)).dailyPart, i(a, 5), "part");
        });
        add("DailyEpisodeParserFixture.should_not_accept_ancient_daily_series", a ->
                eq(Parser.parseTitle(expand(s(a, 0), "1950", "10", "14")), null, "parse"));
        add("DailyEpisodeParserFixture.should_not_accept_future_dates", a -> {
            LocalDate d = LocalDate.now().plusDays(2);
            eq(Parser.parseTitle(expand(s(a, 0), String.valueOf(d.getYear()), String.format("%02d", d.getMonthValue()),
                    String.format("%02d", d.getDayOfMonth()))), null, "parse");
        });
        add("DailyEpisodeParserFixture.should_not_parse_ambiguous_daily_episode", a ->
                eq(Parser.parseTitle(s(a, 0)), null, "parse"));

        // ── SeasonParserFixture ────────────────────────────────────────────
        Check season = a -> {
            ParsedEpisodeInfo r = notNull(Parser.parseTitle(s(a, 0)), s(a, 0));
            eq(r.seasonNumber, i(a, 2), "season");
            eq(r.seriesTitle, s(a, 1), "title");
            eq(r.episodeNumbers.length, 0, "episode count");
            eq(r.absoluteEpisodeNumbers.length, 0, "absolute count");
        };
        add("SeasonParserFixture.should_parse_full_season_release", a -> {
            season.accept(a);
            isTrue(Parser.parseTitle(s(a, 0)).fullSeason, "full season");
        });
        Check extras = a -> {
            season.accept(a);
            ParsedEpisodeInfo r = Parser.parseTitle(s(a, 0));
            isTrue(r.fullSeason, "full season");
            isTrue(r.isSeasonExtra, "season extra");
        };
        add("SeasonParserFixture.should_parse_season_extras", extras);
        add("SeasonParserFixture.should_parse_season_subpack", extras);
        add("SeasonParserFixture.should_parse_partial_season_release", a -> {
            season.accept(a);
            ParsedEpisodeInfo r = Parser.parseTitle(s(a, 0));
            isFalse(r.fullSeason, "full season");
            isTrue(r.isPartialSeason, "partial season");
            eq(r.seasonPart, i(a, 3), "season part");
        });
        add("SeasonParserFixture.should_parse_multi_season_release", a -> {
            season.accept(a);
            ParsedEpisodeInfo r = Parser.parseTitle(s(a, 0));
            isTrue(r.fullSeason, "full season");
            isFalse(r.isPartialSeason, "partial season");
            isTrue(r.isMultiSeason, "multi season");
        });

        // ── AnimeMetadataParserFixture, AnimeVersionFixture, ExtendedQualityParserRegex ───
        add("AnimeMetadataParserFixture.should_parse_releasegroup_and_hash", a -> {
            ParsedEpisodeInfo r = notNull(Parser.parseTitle(s(a, 0)), s(a, 0));
            eq(r.releaseGroup, s(a, 1), "group");
            eq(r.releaseHash, s(a, 2), "hash");
        });
        add("AnimeMetadataParserFixture.should_parse_release_hashes_with_10b_or_8b", a ->
                eq(notNull(Parser.parseTitle(s(a, 0)), s(a, 0)).releaseHash, s(a, 1), "hash"));
        Check version = a -> eq(QualityParser.parseQuality(s(a, 0)).revision.version, i(a, 1), "version");
        add("AnimeVersionFixture.should_be_able_to_parse_repack", version);
        add("ExtendedQualityParserRegex.should_parse_version_from_title", version);
        add("ExtendedQualityParserRegex.should_parse_reality_from_title", a ->
                eq(QualityParser.parseQuality(s(a, 0)).revision.real, i(a, 1), "real"));

        // ── CrapParserFixture ──────────────────────────────────────────────
        Check none = a -> eq(Parser.parseTitle(s(a, 0)), null, "parse");
        add("CrapParserFixture.should_not_parse_crap", none);
        add("CrapParserFixture.should_not_parse_file_name_without_proper_spacing", none);
        add("CrapParserFixture.should_not_parse_invalid_release_name", none);
        add("CrapParserFixture.should_not_parse_special_with_part_number", none);
        add("CrapParserFixture.should_parse_unknown_formats_without_error", a -> notNull(Parser.parseTitle(s(a, 0)), s(a, 0)));
        add("CrapParserFixture.should_not_parse_random", a -> {
            String charset = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ";
            java.util.Random random = new java.util.Random(i(a, 0));
            for (int n = 0; n < 500; n++) {
                StringBuilder hash = new StringBuilder();
                for (int x = 0; x < i(a, 0); x++) {
                    hash.append(charset.charAt(random.nextInt(charset.length())));
                }
                eq(Parser.parseTitle(hash.toString()), null, "parse of " + hash);
            }
        });

        // ── IsPossibleSpecialEpisodeFixture ────────────────────────────────
        add("IsPossibleSpecialEpisodeFixture.IsPossibleSpecialEpisode_should_be_true", a ->
                isTrue(notNull(Parser.parseTitle(s(a, 0)), s(a, 0)).isPossibleSpecialEpisode(), "possible special"));
        add("IsPossibleSpecialEpisodeFixture.IsPossibleSpecialEpisode_should_be_true_if_e00_special", a ->
                isTrue(notNull(Parser.parseTitle(s(a, 0)), s(a, 0)).isPossibleSpecialEpisode(), "possible special"));
        add("IsPossibleSpecialEpisodeFixture.IsPossibleSpecialEpisode_should_be_false_for_Special_in_title", a ->
                isFalse(notNull(Parser.parseTitle(s(a, 0)), s(a, 0)).isPossibleSpecialEpisode(), "possible special"));

        // ── IsoLanguagesFixture ────────────────────────────────────────────
        add("IsoLanguagesFixture.should_return_iso_language_for_English", a ->
                eq(IsoLanguages.find(s(a, 0)).language().name(), "English", "language"));
        add("IsoLanguagesFixture.unknown_or_invalid_code_should_return_null", a ->
                eq(IsoLanguages.find(s(a, 0)), null, "language"));
        add("IsoLanguagesFixture.should_return_portuguese", a ->
                eq(IsoLanguages.find(s(a, 0)).language().name(), "Portuguese", "language"));
        add("IsoLanguagesFixture.should_not_return_portuguese", a -> eq(IsoLanguages.find(s(a, 0)), null, "language"));
        add("IsoLanguagesFixture.should_lookup_cze_via_alternate_iso_code_mapping", a ->
                eq(IsoLanguages.find(s(a, 0)).language().name(), "Czech", "language"));

        // ── NormalizeEpisodeTitleFixture, NormalizeSeriesTitleFixture ──────
        add("NormalizeEpisodeTitleFixture.should_normalize_episode_title", a ->
                eq(Parser.normalizeEpisodeTitle(s(a, 0)), s(a, 1), "normalized"));
        Check clean = a -> eq(Parser.cleanSeriesTitle(s(a, 0)), s(a, 1), "clean title");
        add("NormalizeSeriesTitleFixture.should_normalize_series_title", clean);
        add("NormalizeSeriesTitleFixture.should_remove_special_characters_and_casing", clean);
        add("NormalizeSeriesTitleFixture.should_not_remove_from_the_beginning_of_the_title", clean);
        add("NormalizeSeriesTitleFixture.should_replace_percent_sign_with_percent_following_numbers", clean);
        add("NormalizeSeriesTitleFixture.should_remove_common_words_from_middle_of_title", a -> {
            for (String f : List.of("word.%s.word", "word %s word", "word-%s-word")) {
                eq(Parser.cleanSeriesTitle(String.format(f, s(a, 0))), "wordword", "clean of " + f);
            }
        });
        add("NormalizeSeriesTitleFixture.should_not_remove_common_words_from_end_of_title", a -> {
            for (String f : List.of("word.word.%s", "word-word-%s", "word-word %s")) {
                eq(Parser.cleanSeriesTitle(String.format(f, s(a, 0))), "wordword" + s(a, 0).toLowerCase(), "clean of " + f);
            }
        });
        add("NormalizeSeriesTitleFixture.should_not_remove_common_words_in_the_middle_of_word", a -> {
            for (String f : List.of("word.%sword", "word %sword", "word-%sword", "word%s.word", "word%s-word")) {
                eq(Parser.cleanSeriesTitle(String.format(f, s(a, 0))), "word" + s(a, 0).toLowerCase() + "word",
                        "clean of " + f);
            }
        });
        add("NormalizeSeriesTitleFixture.should_not_clean_word_from_beginning_of_string", a -> {
            for (String f : List.of("%s.word.word", "%s-word-word", "%s word word")) {
                eq(Parser.cleanSeriesTitle(String.format(f, s(a, 0))), s(a, 0) + "wordword", "clean of " + f);
            }
        });

        // ── ParserFixture ──────────────────────────────────────────────────
        add("ParserFixture.should_parse_series_name", a ->
                eq(Parser.cleanSeriesTitle(Parser.parseSeriesName(s(a, 0))), Parser.cleanSeriesTitle(s(a, 1)), "name"));
        add("ParserFixture.should_parse_series_title_info", a -> {
            SeriesTitleInfo info = notNull(Parser.parseTitle(s(a, 0)), s(a, 0)).seriesTitleInfo;
            eq(info.titleWithoutYear, s(a, 1), "title without year");
            eq(info.year, a.size() > 2 ? i(a, 2) : 0, "year");
        });
        add("ParserFixture.should_clean_up_invalid_path_characters", a -> Parser.parseTitle(s(a, 0)));
        add("ParserFixture.should_remove_request_info_from_title", a ->
                eq(notNull(Parser.parseTitle(s(a, 0)), s(a, 0)).seriesTitle, s(a, 1), "title"));
        add("ParserFixture.should_parse_quality_from_extension", a -> {
            ParsedEpisodeInfo r = notNull(Parser.parseTitle(s(a, 0)), s(a, 0));
            isFalse(r.quality.quality.equals(Qualities.UNKNOWN), "unknown quality");
            eq(r.quality.sourceDetectionSource, QualityDetectionSource.EXTENSION,
                    "source detection");
            eq(r.quality.resolutionDetectionSource, QualityDetectionSource.EXTENSION,
                    "resolution detection");
        });
        add("ParserFixture.should_parse_releasetitle", a ->
                eq(notNull(Parser.parseTitle(s(a, 0)), s(a, 0)).releaseTitle, s(a, 1), "release title"));
        add("ParserFixture.should_parse_multiple_series_titles", a -> {
            SeriesTitleInfo info = notNull(Parser.parseTitle(s(a, 0)), s(a, 0)).seriesTitleInfo;
            List<String> expected = a.subList(1, a.size()).stream().map(Object::toString).sorted().toList();
            eq(info.allTitles == null ? null : Arrays.stream(info.allTitles).sorted().toList(), expected, "titles");
        });
        add("ParserFixture.should_parse_language_after_parsing_title", a -> {
            ParsedEpisodeInfo r = notNull(Parser.parseTitle(s(a, 0)), s(a, 0));
            eq(r.languages.size(), 1, "language count");
            eq(r.languages.get(0).name(), s(a, 1), "language");
        });

        // ── PathParserFixture ──────────────────────────────────────────────
        add("PathParserFixture.should_parse_from_path", a -> {
            ParsedEpisodeInfo r = notNull(Parser.parsePath(asOsAgnostic(s(a, 0))), s(a, 0));
            eq(r.episodeNumbers.length, 1, "episode count");
            eq(r.seasonNumber, i(a, 1), "season");
            eq(r.episodeNumbers[0], i(a, 2), "episode");
            eq(r.absoluteEpisodeNumbers.length, 0, "absolute count");
            isFalse(r.fullSeason, "full season");
        });
        add("PathParserFixture.should_parse_multi_episode_from_path", a -> {
            ParsedEpisodeInfo r = notNull(Parser.parsePath(asOsAgnostic(s(a, 0))), s(a, 0));
            eq(r.seriesTitle, s(a, 1), "title");
            eq(r.seasonNumber, i(a, 2), "season");
            eq(sorted(r.episodeNumbers), sorted(ints(a, 3)), "episodes");
            eq(r.absoluteEpisodeNumbers.length, 0, "absolute count");
            isFalse(r.fullSeason, "full season");
        });

        // ── SceneCheckerFixture ────────────────────────────────────────────
        add("SceneCheckerFixture.should_return_true_for_scene_names", a ->
                isTrue(SceneChecker.isSceneTitle(s(a, 0)), "scene title"));
        add("SceneCheckerFixture.should_return_false_for_non_scene_names", a ->
                isFalse(SceneChecker.isSceneTitle(s(a, 0)), "scene title"));

        // ── UnicodeReleaseParserFixture ────────────────────────────────────
        Check unicode = a -> {
            ParsedEpisodeInfo r = notNull(Parser.parseTitle(replaceUnicode(s(a, 0))), s(a, 0));
            eq(r.releaseGroup, s(a, 2), "group");
            eq(r.absoluteEpisodeNumbers.length, 1, "absolute count");
            eq(r.absoluteEpisodeNumbers[0], i(a, 3), "absolute");
            eq(r.seriesTitle, s(a, 1), "title");
            isFalse(r.fullSeason, "full season");
        };
        add("UnicodeReleaseParserFixture.should_parse_chinese_anime_releases", unicode);
        add("UnicodeReleaseParserFixture.should_parse_chinese_anime_season_episode_releases", unicode);
        add("UnicodeReleaseParserFixture.should_parse_unbracketed_chinese_anime_releases", unicode);
        add("UnicodeReleaseParserFixture.should_parse_false_positive_chinese_anime_releases", unicode);
        add("UnicodeReleaseParserFixture.should_parse_chinese_multiepisode_releases", a -> {
            ParsedEpisodeInfo r = notNull(Parser.parseTitle(replaceUnicode(s(a, 0))), s(a, 0));
            eq(r.releaseGroup, s(a, 2), "group");
            eq(sorted(r.absoluteEpisodeNumbers), sorted(ints(a, 3)), "absolute");
            eq(r.seriesTitle, s(a, 1), "title");
            isFalse(r.fullSeason, "full season");
        });
        add("UnicodeReleaseParserFixture.should_parse_gm_team_releases_and_files", a -> {
            ParsedEpisodeInfo r = notNull(Parser.parseTitle(s(a, 0)), s(a, 0));
            eq(r.absoluteEpisodeNumbers.length, 1, "absolute count");
            eq(r.absoluteEpisodeNumbers[0], i(a, 2), "absolute");
            eq(r.seriesTitle, s(a, 1), "title");
            isFalse(r.fullSeason, "full season");
            eq(r.releaseGroup, "GM-Team", "group");
        });
        add("UnicodeReleaseParserFixture.should_parse_unicode_digits", a -> {
            ParsedEpisodeInfo r = notNull(Parser.parseTitle(s(a, 0)), s(a, 0));
            eq(r.seriesTitle, s(a, 1), "title");
            eq(sorted(r.absoluteEpisodeNumbers), sorted(new int[] {i(a, 2)}), "absolute");
            eq(r.specialAbsoluteEpisodeNumbers.length, 0, "special absolute count");
            isFalse(r.fullSeason, "full season");
        });

        // ── UrlFixture, ReleaseGroupParserFixture ──────────────────────────
        add("UrlFixture.should_not_parse_url_in_name", CHECKS.get("ParserFixture.should_parse_series_name"));
        Check group = a -> eq(Parser.parseReleaseGroup(s(a, 0)), a.size() > 1 ? a.get(1) : null, "group");
        add("UrlFixture.should_not_parse_url_in_group", group);
        add("ReleaseGroupParserFixture.should_parse_release_group", group);
        add("ReleaseGroupParserFixture.should_parse_exception_release_group", group);
        add("ReleaseGroupParserFixture.should_not_include_language_in_release_group", group);
        add("ReleaseGroupParserFixture.should_not_include_repost_in_release_group", group);
        add("ReleaseGroupParserFixture.should_parse_anime_release_groups", group);
        add("ReleaseGroupParserFixture.should_not_parse_anime_hash_as_release_group", a ->
                eq(Parser.parseReleaseGroup(s(a, 0)), null, "group"));

        // ── QualityParserFixture ───────────────────────────────────────────
        add("QualityParserFixture.should_parse_sdtv_quality", quality(Qualities.SDTV));
        add("QualityParserFixture.should_parse_dvd_quality", quality(Qualities.DVD));
        add("QualityParserFixture.should_parse_webdl480p_quality", quality(Qualities.WEBDL_480P));
        add("QualityParserFixture.should_parse_bluray480p_quality", quality(Qualities.BLURAY_480P));
        add("QualityParserFixture.should_parse_webrip480p_quality", quality(Qualities.WEBRIP_480P));
        add("QualityParserFixture.should_parse_bluray576p_quality", quality(Qualities.BLURAY_576P));
        add("QualityParserFixture.should_parse_hdtv720p_quality", quality(Qualities.HDTV_720P));
        add("QualityParserFixture.should_parse_hdtv1080p_quality", quality(Qualities.HDTV_1080P));
        add("QualityParserFixture.should_parse_hdtv2160p_quality", quality(Qualities.HDTV_2160P));
        add("QualityParserFixture.should_parse_webdl720p_quality", quality(Qualities.WEBDL_720P));
        add("QualityParserFixture.should_parse_webrip720p_quality", quality(Qualities.WEBRIP_720P));
        add("QualityParserFixture.should_parse_webdl1080p_quality", quality(Qualities.WEBDL_1080P));
        add("QualityParserFixture.should_parse_webrip1080p_quality", quality(Qualities.WEBRIP_1080P));
        add("QualityParserFixture.should_parse_webdl2160p_quality", quality(Qualities.WEBDL_2160P));
        add("QualityParserFixture.should_parse_webrip2160p_quality", quality(Qualities.WEBRIP_2160P));
        add("QualityParserFixture.should_parse_bluray720p_quality", quality(Qualities.BLURAY_720P));
        add("QualityParserFixture.should_parse_bluray1080p_quality", quality(Qualities.BLURAY_1080P));
        add("QualityParserFixture.should_parse_bluray1080p_remux_quality", quality(Qualities.BLURAY_1080P_REMUX));
        add("QualityParserFixture.should_parse_bluray2160p_quality", quality(Qualities.BLURAY_2160P));
        add("QualityParserFixture.should_parse_bluray2160p_remux_quality", quality(Qualities.BLURAY_2160P_REMUX));
        add("QualityParserFixture.should_parse_raw_quality", quality(Qualities.RAWHD));
        add("QualityParserFixture.quality_parse", quality(Qualities.UNKNOWN));
        add("QualityParserFixture.should_parse_full_quality_from_name", detection(QualityDetectionSource.NAME,
                QualityDetectionSource.NAME));
        add("QualityParserFixture.should_parse_resolution_from_name", detection(QualityDetectionSource.UNKNOWN,
                QualityDetectionSource.NAME));
        add("QualityParserFixture.should_parse_source_from_name", detection(QualityDetectionSource.NAME,
                QualityDetectionSource.UNKNOWN));
        add("QualityParserFixture.should_parse_quality_from_extension", detection(QualityDetectionSource.EXTENSION,
                QualityDetectionSource.EXTENSION));
        add("QualityParserFixture.should_parse_resolution_from_name_and_source_from_extension",
                detection(QualityDetectionSource.EXTENSION, QualityDetectionSource.NAME));
        add("QualityParserFixture.should_be_able_to_parse_repack", a -> {
            QualityModel r = QualityParser.parseQuality(s(a, 0));
            eq(r.revision.version, i(a, 2), "version");
            eq(r.revision.isRepack, a.get(1), "repack");
        });
        add("QualityParserFixture.should_be_able_to_parse_anime_version", version);

        // ── HashedReleaseFixture ───────────────────────────────────────────
        add("HashedReleaseFixture.should_properly_parse_hashed_releases", a -> {
            ParsedEpisodeInfo r = notNull(Parser.parsePath(asOsAgnostic(s(a, 0))), s(a, 0));
            eq(r.seriesTitle, s(a, 1), "title");
            eq(r.quality.quality.name().replace("-", "").replace(" ", "").toLowerCase(),
                    s(a, 2).toLowerCase(), "quality");
            eq(r.releaseGroup, a.get(3), "group");
        });

        // ── LanguageParserFixture ──────────────────────────────────────────
        add("LanguageParserFixture.should_parse_language_unknown", containsLanguage("Unknown"));
        add("LanguageParserFixture.should_parse_language_english", containsLanguage("English"));
        add("LanguageParserFixture.should_parse_language_french", containsLanguage("French"));
        add("LanguageParserFixture.should_parse_language_spanish", containsLanguage("Spanish"));
        add("LanguageParserFixture.should_parse_language_german", containsLanguage("German"));
        add("LanguageParserFixture.should_parse_language_italian", containsLanguage("Italian"));
        add("LanguageParserFixture.should_parse_language_danish", containsLanguage("Danish"));
        add("LanguageParserFixture.should_parse_language_dutch", containsLanguage("Dutch"));
        add("LanguageParserFixture.should_parse_language_japanese", containsLanguage("Japanese"));
        add("LanguageParserFixture.should_parse_language_icelandic", containsLanguage("Icelandic"));
        add("LanguageParserFixture.should_parse_language_chinese", containsLanguage("Chinese"));
        add("LanguageParserFixture.should_parse_language_korean", containsLanguage("Korean"));
        add("LanguageParserFixture.should_parse_language_latvian", containsLanguage("Latvian"));
        add("LanguageParserFixture.should_parse_language_russian", containsLanguage("Russian"));
        add("LanguageParserFixture.should_parse_language_polish", containsLanguage("Polish"));
        add("LanguageParserFixture.should_parse_language_vietnamese", containsLanguage("Vietnamese"));
        add("LanguageParserFixture.should_parse_language_swedish", containsLanguage("Swedish"));
        add("LanguageParserFixture.should_parse_language_norwegian", containsLanguage("Norwegian"));
        add("LanguageParserFixture.should_parse_language_finnish", containsLanguage("Finnish"));
        add("LanguageParserFixture.should_parse_language_turkish", containsLanguage("Turkish"));
        add("LanguageParserFixture.should_parse_language_portuguese", containsLanguage("Portuguese"));
        add("LanguageParserFixture.should_parse_language_flemish", containsLanguage("Flemish"));
        add("LanguageParserFixture.should_parse_language_greek", containsLanguage("Greek"));
        add("LanguageParserFixture.should_parse_language_hungarian", containsLanguage("Hungarian"));
        add("LanguageParserFixture.should_parse_language_hebrew", containsLanguage("Hebrew"));
        add("LanguageParserFixture.should_parse_language_lithuanian", containsLanguage("Lithuanian"));
        add("LanguageParserFixture.should_parse_language_czech", containsLanguage("Czech"));
        add("LanguageParserFixture.should_parse_language_arabic", containsLanguage("Arabic"));
        add("LanguageParserFixture.should_parse_language_hindi", containsLanguage("Hindi"));
        add("LanguageParserFixture.should_parse_language_bulgarian", containsLanguage("Bulgarian"));
        add("LanguageParserFixture.should_parse_language_malayalam", containsLanguage("Malayalam"));
        add("LanguageParserFixture.should_parse_language_ukrainian", containsLanguage("Ukrainian"));
        add("LanguageParserFixture.should_parse_language_slovak", containsLanguage("Slovak"));
        add("LanguageParserFixture.should_parse_language_thai", containsLanguage("Thai"));
        add("LanguageParserFixture.should_parse_language_portuguese_brazil", containsLanguage("Portuguese (Brazil)"));
        add("LanguageParserFixture.should_parse_language_spanish_latino", containsLanguage("Spanish (Latino)"));
        add("LanguageParserFixture.should_parse_language_romanian", containsLanguage("Romanian"));
        add("LanguageParserFixture.should_parse_language_catalan", containsLanguage("Catalan"));
        add("LanguageParserFixture.should_parse_subtitle_language_unknown", a ->
                eq(LanguageParser.parseSubtitleLanguage(s(a, 0)).name(), "Unknown", "language"));
        add("LanguageParserFixture.should_parse_subtitle_language_english", a ->
                eq(LanguageParser.parseSubtitleLanguage(s(a, 0)).name(), "English", "language"));
        add("LanguageParserFixture.should_parse_hindi_and_english", exactLanguages("Hindi", "English"));
        add("LanguageParserFixture.should_parse_spanish_and_catalan", exactLanguages("Spanish", "Catalan"));
        add("LanguageParserFixture.should_parse_english_spanish_and_catalan",
                exactLanguages("English", "Spanish", "Catalan"));
        add("LanguageParserFixture.should_add_original_language_to_german_release_with_dl_tag",
                titleLanguages("German", "Original"));
        add("LanguageParserFixture.should_not_add_original_language_to_german_release_when_title_contains_web_dl",
                titleLanguages("German"));
        add("LanguageParserFixture.should_add_original_language_and_english_to_german_release_with_ml_tag",
                titleLanguages("German", "Original", "English"));
        add("LanguageParserFixture.should_parse_original_title_from_release_name", titleLanguages("Original"));
        add("LanguageParserFixture.should_parse_english_and_ukranian", titleLanguages("Ukrainian", "English"));
        add("LanguageParserFixture.should_parse_turkish_and_ukranian", titleLanguages("Ukrainian", "Turkish"));
        add("LanguageParserFixture.should_parse_title_and_tags", a -> {
            SubtitleTitleInfo info = LanguageParser.parseSubtitleLanguageInformation(s(a, 0));
            eq(info.languageTags.stream().sorted().toList(),
                    ((List<?>) a.get(1)).stream().map(Object::toString).sorted().toList(), "tags");
            eq(info.title == null ? null : info.title.toLowerCase(),
                    a.get(2) == null ? null : s(a, 2).toLowerCase(), "title");
            eq(info.language.name(), s(a, 3), "language");
        });
        add("LanguageParserFixture.should_not_parse_false_title", a -> {
            SubtitleTitleInfo info = LanguageParser.parseSubtitleLanguageInformation(s(a, 0));
            eq(info.language.name(), "Unknown", "language");
            eq(info.languageTags.size(), 0, "tags");
            eq(info.rawTitle, null, "raw title");
        });
    }

    static Check containsLanguage(String name) {
        return a -> isTrue(LanguageParser.parseLanguages(s(a, 0)).stream().anyMatch(l -> l.name().equals(name)),
                "contains " + name + " in " + LanguageParser.parseLanguages(s(a, 0)));
    }

    static Check exactLanguages(String... names) {
        return a -> eq(LanguageParser.parseLanguages(s(a, 0)).stream().map(l -> l.name()).sorted().toList(),
                Arrays.stream(names).sorted().toList(), "languages");
    }

    static Check titleLanguages(String... names) {
        return a -> eq(notNull(Parser.parseTitle(s(a, 0)), s(a, 0)).languages.stream().map(l -> l.name()).sorted()
                .toList(), Arrays.stream(names).sorted().toList(), "languages");
    }

    /** Sonarr's {@code ParseAndVerifyQuality}: the quality, and version 2 when it is a PROPER. */
    static Check quality(Quality expected) {
        return a -> {
            QualityModel r = QualityParser.parseQuality(s(a, 0));
            eq(r.quality, expected, "quality");
            eq(r.revision.version, Boolean.TRUE.equals(a.get(1)) ? 2 : 1, "version");
        };
    }

    static Check detection(QualityDetectionSource source, QualityDetectionSource resolution) {
        return a -> {
            QualityModel r = QualityParser.parseQuality(s(a, 0));
            eq(r.sourceDetectionSource, source, "source detection");
            eq(r.resolutionDetectionSource, resolution, "resolution detection");
        };
    }

    /** Sonarr's test helper {@code AsOsAgnostic}: {@code z:\\a\\b} becomes {@code /z/a/b}. */
    static String asOsAgnostic(String path) {
        if (path.length() > 2 && path.charAt(1) == ':') {
            path = "/" + path.replace(":", "");
        }
        return path.replace('\\', '/');
    }

    /** Sonarr's {@code XmlCleaner.ReplaceUnicode}: characters XML cannot hold go. */
    static String replaceUnicode(String text) {
        return text.replaceAll("[^\\x09\\x0A\\x0D\\u0020-\\uD7FF\\uE000-\\uFFFD]", "");
    }

    /** Sonarr's {@code Expand}: {year}, {month} and {day} in a title. */
    static String expand(String title, String year, String month, String day) {
        return title.replace("{year}", year).replace("{month}", month).replace("{day}", day);
    }
}
