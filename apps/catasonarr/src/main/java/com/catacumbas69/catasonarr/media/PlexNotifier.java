package com.catacumbas69.catasonarr.media;

import com.catacumbas69.catasonarr.model.Series;
import com.catacumbas69.catasonarr.model.Settings;
import com.catacumbas69.catasonarr.service.BadRequestException;
import com.catacumbas69.catasonarr.service.SettingsService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Tells Plex Media Server about imported episodes (orch.md decision 122), as Sonarr's Plex connection: the TV
 * libraries whose folder holds the series are scanned for that folder only, or whole when none does. The address is
 * in Settings; the token is in the Secret {@code catasonarr-plex} ({@code CATASONARR_PLEX_TOKEN}), never in MongoDB.
 */
@Component
public class PlexNotifier {

    private static final Logger LOG = LoggerFactory.getLogger(PlexNotifier.class);

    private final SettingsService settings;
    private final String token;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final ObjectMapper json = new ObjectMapper();

    /**
     * Creates the notifier.
     *
     * @param settings where Plex is
     * @param token Plex's token, from the Secret
     */
    public PlexNotifier(SettingsService settings, @Value("${catasonarr.plex.token:}") String token) {
        this.settings = settings;
        this.token = token == null ? "" : token.strip();
    }

    /**
     * Whether Plex has a token (the page says so; the token itself is never shown).
     *
     * @return whether the Secret is set
     */
    public boolean hasToken() {
        return !token.isEmpty();
    }

    /**
     * Asks Plex, in the background, to scan a series' folder.
     *
     * @param series the series
     */
    public void seriesUpdated(Series series) {
        Settings s = settings.get();
        if (!s.plexEnabled() || s.plexUrl().isBlank() || token.isEmpty()) {
            return;
        }
        Thread.ofVirtual().name("plex-update").start(() -> {
            try {
                update(s, series.path());
            } catch (IOException | RuntimeException e) {
                LOG.warn("Unable to tell Plex about {}: {}", series.title(), e.getMessage());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
    }

    /**
     * Checks that Plex answers with the token.
     *
     * @param url Plex's address, e.g. {@code http://plex:32400}
     * @return the TV libraries' names
     */
    public List<String> test(String url) {
        if (token.isEmpty()) {
            throw new BadRequestException("plexUrl", "Plex's token is not set: run catasonarr-secret.sh plex");
        }
        try {
            List<String> names = new ArrayList<>();
            for (JsonNode d : sections(url)) {
                if ("show".equals(d.path("type").asText())) {
                    names.add(d.path("title").asText());
                }
            }
            return names;
        } catch (IOException | RuntimeException e) {
            throw new BadRequestException("plexUrl", "Unable to reach Plex: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BadRequestException("plexUrl", "Interrupted");
        }
    }

    private void update(Settings s, String seriesPath) throws IOException, InterruptedException {
        String path = seriesPath;
        if (!s.plexMapFrom().isBlank() && path.startsWith(s.plexMapFrom())) {
            path = s.plexMapTo() + path.substring(s.plexMapFrom().length());
        }
        List<String> shows = new ArrayList<>();
        List<String> holding = new ArrayList<>();
        for (JsonNode d : sections(s.plexUrl())) {
            if (!"show".equals(d.path("type").asText())) {
                continue;
            }
            String key = d.path("key").asText();
            shows.add(key);
            for (JsonNode location : d.path("Location")) {
                String root = location.path("path").asText();
                if (!root.isEmpty() && (path + "/").startsWith(root.endsWith("/") ? root : root + "/")) {
                    holding.add(key);
                }
            }
        }
        if (!holding.isEmpty()) {
            for (String key : holding) {
                get(s.plexUrl(), "/library/sections/" + key + "/refresh?path="
                        + URLEncoder.encode(path, StandardCharsets.UTF_8));
            }
            LOG.info("Asked Plex to scan {}", path);
        } else {
            for (String key : shows) {
                get(s.plexUrl(), "/library/sections/" + key + "/refresh");
            }
            LOG.info("Asked Plex to scan its TV libraries {} ({} is in none of them)", shows, path);
        }
    }

    private JsonNode sections(String url) throws IOException, InterruptedException {
        return json.readTree(get(url, "/library/sections")).path("MediaContainer").path("Directory");
    }

    private String get(String url, String path) throws IOException, InterruptedException {
        String base = url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
        HttpRequest request = HttpRequest.newBuilder(URI.create(base + path)).timeout(Duration.ofSeconds(20))
                .header("Accept", "application/json").header("X-Plex-Token", token)
                .header("X-Plex-Client-Identifier", "catasonarr").header("X-Plex-Product", "Catasonarr").GET().build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() == 401) {
            throw new IOException("Plex refused the token");
        }
        if (response.statusCode() >= 300) {
            throw new IOException("Plex answered " + response.statusCode());
        }
        return response.body();
    }
}
