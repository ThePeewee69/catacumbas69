package com.catacumbas69.catasonarr.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.catacumbas69.catasonarr.model.Episode;
import com.catacumbas69.catasonarr.model.EpisodeFile;
import com.catacumbas69.catasonarr.model.MediaInfo;
import com.catacumbas69.catasonarr.model.NamingConfig;
import com.catacumbas69.catasonarr.quality.Qualities;
import com.catacumbas69.catasonarr.quality.QualityModel;
import com.catacumbas69.catasonarr.quality.Revision;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.api.Test;

class EpisodeFileNamesTest {

    private static final NamingConfig D = NamingConfig.DEFAULT;
    private static final FolderNames.Names SHOW = new FolderNames.Names("The Show", 2024, 1, 0, 0, null);
    private static final Episode E1 = episode(1, 1, "Pilot", "2024-01-02");
    private static final Episode E2 = episode(2, 2, "The Second", "2024-01-03");
    private static final Episode E3 = episode(3, 3, "The Third", "2024-01-04");

    private static Episode episode(int id, int number, String title, String airDate) {
        return new Episode(id, 1, id, 1, number, number, title, null, airDate, null, 45, true, List.of());
    }

    private static NamingConfig naming(boolean rename, String standard, String anime, int style) {
        return new NamingConfig(NamingConfig.ID, D.seriesFolderFormat(), D.seasonFolderFormat(),
                D.specialsFolderFormat(), true, 4, "", rename, standard, D.dailyEpisodeFormat(), anime, style);
    }

    private static EpisodeFile file(QualityModel quality, MediaInfo mediaInfo, String sceneName) {
        return new EpisodeFile(0, 1, 1, List.of(1), "Show.S01E01.1080p.WEB-DL-GRP.mkv", 1000, null, sceneName, "GRP",
                quality, List.of(), mediaInfo, null, 0, 1);
    }

    private static String name(NamingConfig n, String type, List<Episode> episodes, EpisodeFile f) {
        return EpisodeFileNames.fileName(n, new EpisodeFileNames.Input(SHOW, type, episodes, f, "WEBDL-1080p",
                List.of()), ".mkv");
    }

    @ParameterizedTest
    @CsvSource({
        "0, The Show - S01E01-02-03.mkv",
        "1, The Show - S01E01 - S01E02 - S01E03.mkv",
        "2, The Show - S01E01E02E03.mkv",
        "3, The Show - S01E01-E02-E03.mkv",
        "4, The Show - S01E01-03.mkv",
        "5, The Show - S01E01-E03.mkv"
    })
    void fileName_multiEpisode_followsSonarrsSixStyles(int style, String expected) {
        NamingConfig n = naming(true, "{Series Title} - S{season:00}E{episode:00}", D.animeEpisodeFormat(), style);

        assertThat(name(n, "standard", List.of(E3, E1, E2), file(new QualityModel(Qualities.WEBDL_1080P, null),
                null, null))).isEqualTo(expected);
    }

    @Test
    void fileName_standard_isSonarrsDefault() {
        assertThat(name(naming(true, D.standardEpisodeFormat(), D.animeEpisodeFormat(), 5), "standard", List.of(E1),
                file(new QualityModel(Qualities.WEBDL_1080P, null), null, null)))
                .isEqualTo("The Show - S01E01 - Pilot WEBDL-1080p.mkv");
    }

    @Test
    void fileName_daily_usesTheAirDate() {
        assertThat(name(naming(true, D.standardEpisodeFormat(), D.animeEpisodeFormat(), 5), "daily", List.of(E1),
                file(new QualityModel(Qualities.WEBDL_1080P, null), null, null)))
                .isEqualTo("The Show - 2024-01-02 - Pilot WEBDL-1080p.mkv");
    }

    @Test
    void fileName_renameOff_keepsTheOriginalName() {
        assertThat(name(naming(false, D.standardEpisodeFormat(), D.animeEpisodeFormat(), 5), "standard", List.of(E1),
                file(new QualityModel(Qualities.WEBDL_1080P, null), null, null)))
                .isEqualTo("Show.S01E01.1080p.WEB-DL-GRP.mkv");
    }

    @Test
    void fileName_anime_withAbsoluteNumberMediaInfoAndGroup() {
        String anime = "{Series Title} - S{season:00}E{episode:00} - {absolute:000} - {Episode Title} [{Quality Full}]"
                + "{[MediaInfo VideoDynamicRangeType]}{[Mediainfo AudioCodec}{ Mediainfo AudioChannels]}"
                + "{MediaInfo AudioLanguages}{-Release Group}";
        MediaInfo mi = new MediaInfo(11, "matroska", "hevc", "", null, 0, 10, 0, "bt2020", "smpte2084", "HDR10", 2160,
                3840, 23.976, "eac3", "ec+3", "", 0, 6, "5.1(side)", 2, List.of("eng", "por"), List.of("eng"), 2700,
                "Progressive");
        EpisodeFile f = file(new QualityModel(Qualities.WEBDL_1080P, new Revision(2, 0, false)), mi,
                "Show.S01E01.1080p.WEB-DL.DDP5.1.Atmos.x265-NTb");

        assertThat(name(naming(true, D.standardEpisodeFormat(), anime, 5), "anime", List.of(E1), f))
                .isEqualTo("The Show - S01E01 - 001 - Pilot [WEBDL-1080p v2][HDR10][EAC3 Atmos 5.1][EN+PT]-GRP.mkv");
    }

    @Test
    void fileName_longTitle_isShortenedTo255Bytes() {
        Episode longTitle = episode(4, 4, "X".repeat(300), "2024-01-02");

        String name = name(naming(true, D.standardEpisodeFormat(), D.animeEpisodeFormat(), 5), "standard",
                List.of(longTitle), file(new QualityModel(Qualities.WEBDL_1080P, null), null, null));

        assertThat(name.getBytes(StandardCharsets.UTF_8)).hasSize(255);
        assertThat(name).startsWith("The Show - S01E04 - XXX").endsWith("X... WEBDL-1080p.mkv");
    }
}
