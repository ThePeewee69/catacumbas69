package com.catacumbas69.catasonarr.download;

import com.catacumbas69.catasonarr.model.DownloadClient;
import com.catacumbas69.catasonarr.service.RemotePathMappingService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * qBittorrent through its Web API v2, as Sonarr's {@code QBittorrentProxyV2} (GPL-3.0, orch.md decision 122): it
 * signs in with user and password (or an API key), adds torrents in Catasonarr's category, lists that category and
 * removes torrents. The torrent states map to the queue's as Sonarr maps them.
 */
@Component
public class QBittorrentClient implements TorrentClient {

    private final ObjectMapper json = new ObjectMapper();
    private final Map<Integer, String> cookies = new ConcurrentHashMap<>();
    private final RemotePathMappingService paths;

    /**
     * Creates the client.
     *
     * @param paths remote path mappings
     */
    public QBittorrentClient(RemotePathMappingService paths) {
        this.paths = paths;
    }

    @Override
    public String implementation() {
        return "QBittorrent";
    }

    @Override
    public void test(DownloadClient client) {
        String version = call(client, "GET", "/api/v2/app/webapiVersion", null, null).body();
        if (version == null || version.isBlank() || version.compareTo("2.0") < 0) {
            throw new ClientException("qBittorrent's Web API is too old (" + version + "); Catasonarr needs v2");
        }
        ensureCategory(client);
    }

    @Override
    public void add(DownloadClient client, AddRequest add) {
        ensureCategory(client);
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("category", ClientHttp.text(client, "tvCategory"));
        int initialState = ClientHttp.number(client, "initialState", 0);
        if (initialState == 2) {
            fields.put("paused", "true");
            fields.put("stopped", "true");
        }
        if (ClientHttp.bool(client, "sequentialOrder")) {
            fields.put("sequentialDownload", "true");
        }
        if (ClientHttp.bool(client, "firstAndLast")) {
            fields.put("firstLastPiecePrio", "true");
        }
        int layout = ClientHttp.number(client, "contentLayout", 0);
        if (layout == 1) {
            fields.put("contentLayout", "Original");
        } else if (layout == 2) {
            fields.put("contentLayout", "Subfolder");
        }
        if (add.seedRatio() != null) {
            fields.put("ratioLimit", String.valueOf(add.seedRatio()));
        }
        if (add.seedTimeMinutes() != null) {
            fields.put("seedingTimeLimit", String.valueOf(add.seedTimeMinutes()));
        }
        HttpResponse<String> response;
        if (add.magnet() != null) {
            fields.put("urls", add.magnet());
            response = call(client, "POST", "/api/v2/torrents/add", ClientHttp.form(fields),
                    "application/x-www-form-urlencoded");
        } else {
            String boundary = "----catasonarr" + UUID.randomUUID();
            response = call(client, "POST", "/api/v2/torrents/add", multipart(boundary, fields, add),
                    "multipart/form-data; boundary=" + boundary);
        }
        if (response.statusCode() >= 400 || "Fails.".equals(response.body().strip())) {
            throw new ClientException("qBittorrent refused the torrent: " + response.body());
        }
        if (initialState == 1) {
            call(client, "POST", "/api/v2/torrents/setForceStart", ClientHttp.form(Map.of("hashes",
                    add.hash().toLowerCase(), "value", "true")), "application/x-www-form-urlencoded");
        }
    }

    @Override
    public List<ClientItem> items(DownloadClient client) {
        String category = ClientHttp.text(client, "tvCategory");
        String body = call(client, "GET", "/api/v2/torrents/info?category="
                + java.net.URLEncoder.encode(category, StandardCharsets.UTF_8), null, null).body();
        List<ClientItem> items = new ArrayList<>();
        JsonNode torrents = read(body);
        for (JsonNode t : torrents) {
            long size = t.path("size").asLong();
            double progress = t.path("progress").asDouble();
            String state = t.path("state").asText();
            String status;
            String message = null;
            Long eta = t.path("eta").asLong() >= 8640000 ? null : t.path("eta").asLong();
            switch (state) {
                case "error":
                    status = "warning";
                    message = "qBittorrent is reporting an error";
                    break;
                case "stoppedDL", "pausedDL":
                    status = "paused";
                    break;
                case "queuedDL", "checkingDL", "checkingUP", "checkingResumeData", "metaDL", "forcedMetaDL":
                    status = "queued";
                    break;
                case "pausedUP", "stoppedUP", "uploading", "stalledUP", "queuedUP", "forcedUP":
                    status = "completed";
                    eta = 0L;
                    break;
                case "stalledDL":
                    status = "warning";
                    message = "The download is stalled with no connections";
                    break;
                case "missingFiles":
                    status = "warning";
                    message = "The download is missing files";
                    break;
                default:
                    status = "downloading";
                    break;
            }
            String contentPath = t.path("content_path").asText(null);
            String savePath = t.path("save_path").asText(null);
            String output = null;
            if ("completed".equals(status)) {
                if (contentPath != null && !contentPath.equals(savePath)) {
                    output = paths.remoteToLocal(ClientHttp.text(client, "host"), contentPath);
                } else {
                    status = "warning";
                    message = "The download's content path is its save path; Catasonarr cannot import from there";
                }
            }
            items.add(new ClientItem(client.id(), client.name(), t.path("hash").asText().toUpperCase(),
                    t.path("name").asText(), t.path("category").asText(), size, (long) (size * (1.0 - progress)), eta,
                    status, message, output, t.path("ratio").asDouble()));
        }
        return items;
    }

    @Override
    public void remove(DownloadClient client, String hash, boolean deleteData) {
        call(client, "POST", "/api/v2/torrents/delete", ClientHttp.form(Map.of("hashes", hash.toLowerCase(),
                "deleteFiles", String.valueOf(deleteData))), "application/x-www-form-urlencoded");
    }

    private void ensureCategory(DownloadClient client) {
        String category = ClientHttp.text(client, "tvCategory");
        JsonNode categories = read(call(client, "GET", "/api/v2/torrents/categories", null, null).body());
        if (!categories.has(category)) {
            call(client, "POST", "/api/v2/torrents/createCategory", ClientHttp.form(Map.of("category", category)),
                    "application/x-www-form-urlencoded");
        }
    }

    private HttpResponse<String> call(DownloadClient client, String method, String path, Object body,
                                      String contentType) {
        HttpResponse<String> response = once(client, method, path, body, contentType);
        if (response.statusCode() == 403 && ClientHttp.text(client, "apiKey").isBlank()) {
            cookies.remove(client.id());
            response = once(client, method, path, body, contentType);
        }
        if (response.statusCode() == 403 || response.statusCode() == 401) {
            throw new ClientException("qBittorrent refused the user and password (or API key)");
        }
        if (response.statusCode() >= 400) {
            throw new ClientException("qBittorrent answered HTTP " + response.statusCode() + " to " + path);
        }
        return response;
    }

    private HttpResponse<String> once(DownloadClient client, String method, String path, Object body,
                                      String contentType) {
        String base = ClientHttp.base(client, "");
        HttpRequest.Builder b = ClientHttp.request(base + path).header("Referer", base);
        String apiKey = ClientHttp.text(client, "apiKey");
        if (!apiKey.isBlank()) {
            b.header("Authorization", "Bearer " + apiKey);
        } else {
            b.header("Cookie", "SID=" + cookies.computeIfAbsent(client.id(), id -> login(client, base)));
        }
        if ("POST".equals(method)) {
            b.header("Content-Type", contentType);
            b.POST(body instanceof byte[] bytes ? HttpRequest.BodyPublishers.ofByteArray(bytes)
                    : HttpRequest.BodyPublishers.ofString(body == null ? "" : body.toString()));
        } else {
            b.GET();
        }
        return ClientHttp.send(b.build());
    }

    private String login(DownloadClient client, String base) {
        HttpRequest request = ClientHttp.request(base + "/api/v2/auth/login").header("Referer", base)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(ClientHttp.form(Map.of("username",
                        ClientHttp.text(client, "username"), "password", ClientHttp.text(client, "password")))))
                .build();
        HttpResponse<String> response = ClientHttp.send(request);
        if (response.statusCode() == 403 || !response.body().strip().startsWith("Ok")) {
            throw new ClientException("qBittorrent refused the user and password");
        }
        for (String cookie : response.headers().allValues("Set-Cookie")) {
            if (cookie.startsWith("SID=")) {
                return cookie.substring(4, cookie.contains(";") ? cookie.indexOf(';') : cookie.length());
            }
        }
        return "";
    }

    private JsonNode read(String body) {
        try {
            return json.readTree(body);
        } catch (IOException e) {
            throw new ClientException("qBittorrent did not answer with JSON");
        }
    }

    private static byte[] multipart(String boundary, Map<String, String> fields, AddRequest add) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            for (Map.Entry<String, String> f : fields.entrySet()) {
                out.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"" + f.getKey()
                        + "\"\r\n\r\n" + f.getValue() + "\r\n").getBytes(StandardCharsets.UTF_8));
            }
            out.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"torrents\"; filename=\""
                    + add.hash() + ".torrent\"\r\nContent-Type: application/x-bittorrent\r\n\r\n")
                    .getBytes(StandardCharsets.UTF_8));
            out.write(add.torrent());
            out.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new ClientException(e.getMessage());
        }
        return out.toByteArray();
    }
}
