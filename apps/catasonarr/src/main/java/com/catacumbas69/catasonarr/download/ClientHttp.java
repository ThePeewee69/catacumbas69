package com.catacumbas69.catasonarr.download;

import com.catacumbas69.catasonarr.model.DownloadClient;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.stream.Collectors;

/** What qBittorrent's and Transmission's clients share: their address, settings and the HTTP calls. */
final class ClientHttp {

    static final Duration TIMEOUT = Duration.ofSeconds(30);

    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL).build();

    private ClientHttp() {
    }

    static String text(DownloadClient c, String name) {
        Object v = c.settings().get(name);
        return v == null ? "" : v.toString();
    }

    static int number(DownloadClient c, String name, int fallback) {
        Object v = c.settings().get(name);
        if (v instanceof Number n) {
            return n.intValue();
        }
        try {
            return v == null || v.toString().isBlank() ? fallback : Integer.parseInt(v.toString().strip());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    static boolean bool(DownloadClient c, String name) {
        Object v = c.settings().get(name);
        return v instanceof Boolean b ? b : v != null && Boolean.parseBoolean(v.toString());
    }

    /** The client's address with its URL base, without a trailing slash. */
    static String base(DownloadClient c, String defaultUrlBase) {
        String urlBase = text(c, "urlBase");
        if (urlBase.isBlank()) {
            urlBase = defaultUrlBase;
        }
        urlBase = urlBase.strip().replaceAll("^/*", "/").replaceAll("/+$", "");
        if (urlBase.equals("/")) {
            urlBase = "";
        }
        return (bool(c, "useSsl") ? "https://" : "http://") + text(c, "host").strip() + ":"
                + number(c, "port", 80) + urlBase;
    }

    static String form(Map<String, String> fields) {
        return fields.entrySet().stream().map(e -> URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8) + "="
                + URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8)).collect(Collectors.joining("&"));
    }

    static HttpRequest.Builder request(String url) {
        return HttpRequest.newBuilder(URI.create(url)).timeout(TIMEOUT).header("User-Agent", "Catasonarr");
    }

    static HttpResponse<String> send(HttpRequest request) {
        try {
            return HTTP.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new TorrentClient.ClientException("Unable to connect to the download client: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new TorrentClient.ClientException("Interrupted");
        }
    }
}
