package com.catacumbas69.catasonarr.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.catacumbas69.catasonarr.model.Episode;
import com.catacumbas69.catasonarr.model.Season;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class MonitorRulesTest {

    private static final Instant NOW = Instant.parse("2026-11-21T12:00:00Z");
    private static final List<Season> SEASONS = List.of(new Season(0, false), new Season(1, true),
            new Season(2, true));
    private static final List<Episode> EPISODES = List.of(
            episode(1, 0, 1, "2025-01-01T00:00:00Z", false),
            episode(2, 1, 1, "2025-02-01T00:00:00Z", true),
            episode(3, 1, 2, "2025-02-08T00:00:00Z", true),
            episode(4, 2, 1, "2026-11-01T00:00:00Z", true),
            episode(5, 2, 2, "2026-12-01T00:00:00Z", true),
            episode(6, 2, 3, null, true));

    @Test
    void all_everyEpisodeButSpecials_andTheLastSeason() {
        MonitorRules.Result r = MonitorRules.apply("all", false, "continuing", EPISODES, SEASONS, NOW);
        assertThat(monitored(r)).containsExactly("1x1", "1x2", "2x1", "2x2", "2x3");
        assertThat(r.seasons()).containsExactly(new Season(0, false), new Season(1, true), new Season(2, true));
    }

    @Test
    void future_onlyWhatHasNotAired_andTheLastSeasonOfAContinuingSeries() {
        MonitorRules.Result r = MonitorRules.apply("future", false, "continuing", EPISODES, SEASONS, NOW);
        assertThat(monitored(r)).containsExactly("2x2", "2x3");
        assertThat(r.seasons()).containsExactly(new Season(0, false), new Season(1, false), new Season(2, true));
    }

    @Test
    void recent_theLast90DaysAndTheFuture() {
        MonitorRules.Result r = MonitorRules.apply("recent", false, "continuing", EPISODES, SEASONS, NOW);
        assertThat(monitored(r)).containsExactly("2x1", "2x2", "2x3");
    }

    @Test
    void pilot_onlyTheFirstEpisode_withItsSeasonUnmonitored() {
        MonitorRules.Result r = MonitorRules.apply("pilot", false, "ended", EPISODES, SEASONS, NOW);
        assertThat(monitored(r)).containsExactly("1x1");
        assertThat(r.seasons()).containsExactly(new Season(0, false), new Season(1, false), new Season(2, false));
    }

    @Test
    void firstAndLastSeason() {
        assertThat(monitored(MonitorRules.apply("firstSeason", false, "ended", EPISODES, SEASONS, NOW)))
                .containsExactly("1x1", "1x2");
        assertThat(monitored(MonitorRules.apply("lastSeason", false, "ended", EPISODES, SEASONS, NOW)))
                .containsExactly("2x1", "2x2", "2x3");
    }

    @Test
    void specials_onOrOff_leaveTheRest() {
        assertThat(monitored(MonitorRules.apply("monitorSpecials", false, "ended", EPISODES, SEASONS, NOW)))
                .containsExactly("0x1", "1x1", "1x2", "2x1", "2x2", "2x3");
    }

    @Test
    void unknown_asOverseerrSendsIt_theSeasonsDecide() {
        List<Season> onlySecond = List.of(new Season(0, false), new Season(1, false), new Season(2, true));
        MonitorRules.Result r = MonitorRules.apply(null, false, "continuing", EPISODES, onlySecond, NOW);
        assertThat(monitored(r)).containsExactly("2x1", "2x2", "2x3");
        assertThat(r.seasons()).containsExactly(new Season(0, false), new Season(1, false), new Season(2, true));
    }

    @Test
    void unknown_ignoringEpisodesWithoutFiles_leavesTheAiredOnesOut() {
        MonitorRules.Result r = MonitorRules.apply("unknown", true, "continuing", EPISODES, SEASONS, NOW);
        assertThat(monitored(r)).containsExactly("2x2", "2x3");
        assertThat(r.seasons()).as("season 1 has nothing monitored left").contains(new Season(1, false));
    }

    @Test
    void none_andSkip() {
        assertThat(monitored(MonitorRules.apply("none", false, "ended", EPISODES, SEASONS, NOW))).isEmpty();
        assertThat(MonitorRules.apply("skip", false, "ended", EPISODES, SEASONS, NOW).episodes())
                .isEqualTo(EPISODES);
    }

    @Test
    void anUnknownType_isRefused() {
        assertThatThrownBy(() -> MonitorRules.apply("sometimes", false, "ended", EPISODES, SEASONS, NOW))
                .isInstanceOf(BadRequestException.class);
    }

    private static List<String> monitored(MonitorRules.Result r) {
        return r.episodes().stream().filter(Episode::monitored).map(e -> e.seasonNumber() + "x" + e.episodeNumber())
                .toList();
    }

    private static Episode episode(int id, int season, int number, String air, boolean monitored) {
        return new Episode(id, 1, id, season, number, null, "E" + id, null, null,
                air == null ? null : Instant.parse(air), 45, monitored, List.of());
    }
}
