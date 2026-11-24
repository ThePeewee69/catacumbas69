package com.catacumbas69.catasonarr;

import static org.assertj.core.api.Assertions.assertThat;

import com.catacumbas69.catacommons.test.CatacumbasContainers;
import com.catacumbas69.catasonarr.service.CatasonarrSetup;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Sonarr's API v3 for series, against real MongoDB and Pulsar (TEST-3, orch.md decision 112), with TVmaze replaced
 * by a JDK {@link HttpServer} serving recorded answers (src/test/resources/tvmaze). It answers the lookup by TheTVDB's
 * id with a redirect, as TVmaze does. A series is added as Overseerr adds it, changed, and deleted with its folder.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SeriesApiIT {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int TVDB = 393189;
    private static final HttpServer TVMAZE = startTvmaze();

    @Autowired
    private TestRestTemplate http;

    @Autowired
    private CatasonarrSetup setup;

    @DynamicPropertySource
    static void containers(DynamicPropertyRegistry registry) {
        CatacumbasContainers.register(registry, "catasonarr");
        registry.add("catasonarr.metadata.tvmaze-url",
                () -> "http://" + TVMAZE.getAddress().getHostString() + ":" + TVMAZE.getAddress().getPort());
        registry.add("catasonarr.metadata.tvmaze-spacing", () -> "0ms");
    }

    @AfterAll
    static void stopTvmaze() {
        TVMAZE.stop(0);
    }

    @Test
    void browseFolders_asSonarrsFileSystemApi() throws IOException {
        Path root = Files.createTempDirectory("catasonarr-browse");
        Files.createDirectories(root.resolve("Series"));
        Files.createDirectories(root.resolve("#recycle"));

        JsonNode contents = http.getForObject("/api/v3/filesystem?path={path}", JsonNode.class, root + "/");

        assertThat(contents.path("parent").asText()).isEqualTo(root.getParent() + "/");
        assertThat(contents.path("directories").findValuesAsText("name")).containsExactly("Series");
        assertThat(contents.path("directories").get(0).path("type").asText()).isEqualTo("folder");
        assertThat(contents.path("directories").get(0).path("path").asText()).isEqualTo(root.resolve("Series") + "/");
        assertThat(http.getForObject("/api/v3/filesystem/type?path={path}", JsonNode.class, root.toString())
                .path("type").asText()).isEqualTo("folder");
    }

    @Test
    void addAsOverseerr_changeSeasons_thenDeleteWithItsFolder() throws IOException {
        setup.ensure();
        Path root = Files.createTempDirectory("catasonarr-tv");
        assertThat(http.postForEntity("/api/v3/rootfolder", Map.of("path", root.toString()), JsonNode.class)
                .getStatusCode()).isEqualTo(HttpStatus.CREATED);
        JsonNode profiles = http.getForObject("/api/v3/qualityprofile", JsonNode.class);
        assertThat(profiles.size()).as("Sonarr's six defaults").isGreaterThanOrEqualTo(1);
        assertThat(http.getForObject("/api/v3/qualityProfile", JsonNode.class).size())
                .as("Seerr's spelling, as Sonarr answers it").isEqualTo(profiles.size());

        JsonNode found = http.getForObject("/api/v3/series/lookup?term=tvdb:" + TVDB, JsonNode.class);
        assertThat(found.size()).isEqualTo(1);
        assertThat(found.get(0).path("id").asInt()).as("not in the library yet").isZero();
        assertThat(found.get(0).path("title").asText()).isEqualTo("Andor");
        assertThat(seasonNumbers(found.get(0))).containsExactly(0, 1, 2);
        // The folder follows the naming in use: Sonarr's default, or the one the other test imports
        Path folder = root.resolve(found.get(0).path("folder").asText());

        ObjectNode add = JSON.createObjectNode();
        add.put("tvdbId", TVDB).put("title", "Andor").put("qualityProfileId", profiles.get(0).path("id").asInt())
                .put("rootFolderPath", root.toString()).put("monitored", true).put("seasonFolder", true);
        add.putArray("seasons").add(season(1, false)).add(season(2, true));
        add.putObject("addOptions").put("ignoreEpisodesWithFiles", true).put("searchForMissingEpisodes", true);
        ResponseEntity<JsonNode> added = http.postForEntity("/api/v3/series", add, JsonNode.class);
        assertThat(added.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        int id = added.getBody().path("id").asInt();
        assertThat(id).isPositive();
        assertThat(added.getBody().path("path").asText()).isEqualTo(folder.toString());
        assertThat(added.getBody().path("statistics").path("totalEpisodeCount").asInt()).isEqualTo(5);
        assertThat(added.getBody().path("statistics").path("episodeCount").asInt()).as("2x1 aired").isEqualTo(1);

        assertThat(monitored(id)).containsExactly("2x1");
        assertThat(http.getForObject("/api/v3/command", JsonNode.class).findValuesAsText("name"))
                .as("the search asked is kept").contains("SeriesSearch");
        assertThat(http.getForObject("/api/v3/series/lookup?term=tvdb:" + TVDB, JsonNode.class).get(0).path("id")
                .asInt()).as("now in the library").isEqualTo(id);
        assertThat(http.postForEntity("/api/v3/series", add, JsonNode.class).getStatusCode())
                .as("added twice").isEqualTo(HttpStatus.BAD_REQUEST);

        ObjectNode change = (ObjectNode) http.getForObject("/api/v3/series/" + id, JsonNode.class);
        change.putArray("seasons").add(season(0, false)).add(season(1, true)).add(season(2, true));
        assertThat(http.exchange("/api/v3/series", HttpMethod.PUT, new HttpEntity<>(change), JsonNode.class)
                .getStatusCode()).as("Overseerr's PUT, the id in the body").isEqualTo(HttpStatus.ACCEPTED);
        assertThat(monitored(id)).containsExactly("1x1", "1x2", "2x1");

        Files.createDirectories(folder.resolve("Season 1"));
        Files.writeString(folder.resolve("Season 1").resolve("episode.mkv"), "x");
        assertThat(http.exchange("/api/v3/series/" + id + "?deleteFiles=true", HttpMethod.DELETE, null,
                JsonNode.class).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(folder).doesNotExist();
        assertThat(root).exists();
        assertThat(http.getForEntity("/api/v3/series/" + id, JsonNode.class).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void importFromSonarrsDatabase_keepsSonarrsIdsAndNaming() throws IOException {
        setup.ensure();
        JsonNode rows;
        try (InputStream in = SeriesApiIT.class.getResourceAsStream("/sonarr/quality-rows.json")) {
            rows = JSON.readTree(in);
        }
        ResponseEntity<JsonNode> result = http.postForEntity("/api/v3/catasonarr/import/sonarr-quality", rows,
                JsonNode.class);
        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(result.getBody().path("qualityProfiles").asInt()).isEqualTo(1);

        JsonNode profile = http.getForObject("/api/v3/qualityprofile/7", JsonNode.class);
        assertThat(profile.path("name").asText()).isEqualTo("Mine 1080p");
        assertThat(profile.path("cutoff").asInt()).isEqualTo(1002);
        assertThat(profile.path("formatItems").get(0).path("score").asInt()).isEqualTo(-100);
        assertThat(profile.path("formatItems").get(0).path("name").asText()).isEqualTo("x265");
        JsonNode format = http.getForObject("/api/v3/customformat/4", JsonNode.class);
        assertThat(format.path("specifications").get(0).path("implementation").asText())
                .isEqualTo("ReleaseTitleSpecification");
        assertThat(format.path("specifications").get(0).path("fields").get(0).path("value").asText())
                .isEqualTo("[xh][ .]?265|hevc");
        JsonNode definitions = http.getForObject("/api/v3/qualitydefinition", JsonNode.class);
        assertThat(definitions.size()).isEqualTo(3);

        JsonNode lookup = http.getForObject("/api/v3/series/lookup?term=tvdb:" + TVDB, JsonNode.class);
        assertThat(lookup.get(0).path("folder").asText()).as("the naming imported")
                .isEqualTo("Andor (2022) [tvdbid-393189]");
    }

    private List<String> monitored(int seriesId) {
        JsonNode episodes = http.getForObject("/api/v3/episode?seriesId=" + seriesId, JsonNode.class);
        List<String> monitored = new ArrayList<>();
        episodes.forEach(e -> {
            if (e.path("monitored").asBoolean()) {
                monitored.add(e.path("seasonNumber").asInt() + "x" + e.path("episodeNumber").asInt());
            }
        });
        return monitored;
    }

    private static List<Integer> seasonNumbers(JsonNode series) {
        List<Integer> numbers = new ArrayList<>();
        series.path("seasons").forEach(s -> numbers.add(s.path("seasonNumber").asInt()));
        return numbers;
    }

    private static ObjectNode season(int number, boolean monitored) {
        ObjectNode season = JSON.createObjectNode();
        season.put("seasonNumber", number).put("monitored", monitored);
        return season;
    }

    /** TVmaze, from recorded answers: the lookup redirects to the show, as TVmaze does. */
    private static HttpServer startTvmaze() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
            server.createContext("/", SeriesApiIT::tvmaze);
            server.start();
            return server;
        } catch (IOException e) {
            throw new UncheckedIOException("Fake TVmaze not started", e);
        }
    }

    private static void tvmaze(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        String query = exchange.getRequestURI().getQuery();
        if ("/lookup/shows".equals(path) && ("thetvdb=" + TVDB).equals(query)) {
            exchange.getResponseHeaders().set("Location", "/shows/60574");
            exchange.sendResponseHeaders(301, -1);
            exchange.close();
            return;
        }
        String file = switch (path) {
            case "/shows/60574" -> "show.json";
            case "/shows/60574/episodes" -> "episodes.json";
            case "/shows/60574/akas" -> "akas.json";
            default -> null;
        };
        byte[] body;
        if (file != null) {
            try (InputStream in = SeriesApiIT.class.getResourceAsStream("/tvmaze/" + file)) {
                body = in.readAllBytes();
            }
        } else {
            body = "{\"name\":\"Not Found\",\"status\":404}".getBytes(StandardCharsets.UTF_8);
        }
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(file != null ? 200 : 404, body.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
        }
    }
}
