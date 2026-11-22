package com.catacumbas69.catasonarr.metadata;

import static org.assertj.core.api.Assertions.assertThat;

import com.catacumbas69.catasonarr.model.AlternateTitle;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class TvMazeMapperTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void show_mapsTvmazesFieldsToSonarrs() throws IOException {
        SeriesInfo info = TvMazeMapper.show(fixture("show.json"));

        assertThat(info.tvdbId()).isEqualTo(393189);
        assertThat(info.tvMazeId()).isEqualTo(60574);
        assertThat(info.imdbId()).isEqualTo("tt9253284");
        assertThat(info.title()).isEqualTo("Andor");
        assertThat(info.status()).isEqualTo("ended");
        assertThat(info.overview()).isEqualTo("Andor follows Cassian & friends.");
        assertThat(info.network()).as("the web channel when there is no network").isEqualTo("Disney+");
        assertThat(info.airTime()).as("an empty time is no time").isNull();
        assertThat(info.runtime()).as("the average when runtime is null").isEqualTo(47);
        assertThat(info.year()).isEqualTo(2022);
        assertThat(info.firstAired()).isEqualTo(Instant.parse("2022-09-21T00:00:00Z"));
        assertThat(info.lastAired()).isEqualTo(Instant.parse("2025-05-13T00:00:00Z"));
        assertThat(info.originalLanguage().id()).isEqualTo(1);
        assertThat(info.ratings().value()).isEqualTo(8.4);
        assertThat(info.images()).singleElement().satisfies(i -> {
            assertThat(i.coverType()).isEqualTo("poster");
            assertThat(i.remoteUrl()).isEqualTo("https://static.tvmaze.com/o.jpg");
        });
    }

    @Test
    void episodes_regularBySeason_thenSpecialsInSeasonZeroByAirDate() throws IOException {
        List<EpisodeInfo> episodes = TvMazeMapper.episodes(fixture("episodes.json"));

        assertThat(episodes).extracting(e -> e.seasonNumber() + "x" + e.episodeNumber())
                .containsExactly("1x1", "1x2", "2x1", "0x1", "0x2");
        assertThat(episodes).extracting(EpisodeInfo::absoluteEpisodeNumber).containsExactly(1, 2, 3, null, null);
        assertThat(episodes.get(3).title()).as("the oldest special first").isEqualTo("Trailer");
        assertThat(episodes.get(0).airDateUtc()).isEqualTo(Instant.parse("2022-09-21T07:00:00Z"));
        assertThat(episodes.get(0).images()).hasSize(1);
        assertThat(episodes.get(1).overview()).isNull();
    }

    @Test
    void akas_withoutRepeats() throws IOException {
        assertThat(TvMazeMapper.akas(fixture("akas.json"))).extracting(AlternateTitle::title)
                .containsExactly("Star Wars: Andor", "Андор");
    }

    @Test
    void status_mapsToSonarrsThree() {
        assertThat(TvMazeMapper.status("Running")).isEqualTo("continuing");
        assertThat(TvMazeMapper.status("To Be Determined")).isEqualTo("continuing");
        assertThat(TvMazeMapper.status("In Development")).isEqualTo("upcoming");
        assertThat(TvMazeMapper.status("Ended")).isEqualTo("ended");
        assertThat(TvMazeMapper.status(null)).isEqualTo("continuing");
    }

    @Test
    void plain_dropsTagsAndEntities() {
        assertThat(TvMazeMapper.plain("<p>A <i>show</i>&nbsp;&amp; more</p>")).isEqualTo("A show & more");
        assertThat(TvMazeMapper.plain("<p></p>")).isNull();
        assertThat(TvMazeMapper.plain(null)).isNull();
    }

    private static JsonNode fixture(String name) throws IOException {
        try (InputStream in = TvMazeMapperTest.class.getResourceAsStream("/tvmaze/" + name)) {
            return JSON.readTree(in);
        }
    }
}
