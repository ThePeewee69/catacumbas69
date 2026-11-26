package com.catacumbas69.catasonarr.download;

import com.catacumbas69.catasonarr.model.DownloadClient;
import com.catacumbas69.catasonarr.service.RemotePathMappingService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * Transmission through its RPC, as Sonarr's {@code TransmissionProxy} (GPL-3.0, orch.md decision 122): Catasonarr's
 * torrents go to the folder of its category (Transmission's download folder plus {@code catasonarr}, or the
 * directory set), which is how they are told apart.
 */
@Component
public class TransmissionClient implements TorrentClient {

    private final ObjectMapper json = new ObjectMapper();
    private final Map<Integer, String> sessions = new ConcurrentHashMap<>();
    private final RemotePathMappingService paths;

    /**
     * Creates the client.
     *
     * @param paths remote path mappings
     */
    public TransmissionClient(RemotePathMappingService paths) {
        this.paths = paths;
    }

    @Override
    public String implementation() {
        return "Transmission";
    }

    @Override
    public void test(DownloadClient client) {
        JsonNode session = rpc(client, "session-get", json.createObjectNode());
        String version = session.path("version").asText("0");
        if (version.compareTo("2.40") < 0) {
            throw new ClientException("Transmission " + version + " is too old; Catasonarr needs 2.40 or later");
        }
    }

    @Override
    public void add(DownloadClient client, AddRequest add) {
        ObjectNode args = json.createObjectNode();
        if (add.magnet() != null) {
            args.put("filename", add.magnet());
        } else {
            args.put("metainfo", Base64.getEncoder().encodeToString(add.torrent()));
        }
        args.put("download-dir", directory(client));
        args.put("paused", ClientHttp.bool(client, "addPaused"));
        JsonNode result = rpc(client, "torrent-add", args);
        if (result.has("torrent-duplicate") || result.has("torrent-added")) {
            if (add.seedRatio() != null || add.seedTimeMinutes() != null) {
                ObjectNode limits = json.createObjectNode();
                limits.putArray("ids").add(add.hash().toLowerCase());
                if (add.seedRatio() != null) {
                    limits.put("seedRatioLimit", add.seedRatio());
                    limits.put("seedRatioMode", 1);
                }
                if (add.seedTimeMinutes() != null) {
                    limits.put("seedIdleLimit", add.seedTimeMinutes());
                    limits.put("seedIdleMode", 1);
                }
                rpc(client, "torrent-set", limits);
            }
            return;
        }
        throw new ClientException("Transmission refused the torrent");
    }

    @Override
    public List<ClientItem> items(DownloadClient client) {
        String dir = directory(client).replaceAll("/+$", "");
        ObjectNode args = json.createObjectNode();
        ArrayNode fields = args.putArray("fields");
        for (String f : List.of("hashString", "name", "downloadDir", "totalSize", "leftUntilDone", "isFinished",
                "eta", "status", "errorString", "uploadRatio", "error")) {
            fields.add(f);
        }
        JsonNode torrents = rpc(client, "torrent-get", args).path("torrents");
        List<ClientItem> items = new ArrayList<>();
        for (JsonNode t : torrents) {
            String downloadDir = t.path("downloadDir").asText().replaceAll("/+$", "");
            if (!downloadDir.equals(dir)) {
                continue;
            }
            long left = t.path("leftUntilDone").asLong();
            int code = t.path("status").asInt();
            String status;
            String message = null;
            if (t.path("error").asInt() > 0) {
                status = "warning";
                message = t.path("errorString").asText();
            } else if (left == 0 && (code == 0 || code == 5 || code == 6 || t.path("isFinished").asBoolean())) {
                status = "completed";
            } else if (code == 0) {
                status = "paused";
            } else if (code == 1 || code == 2 || code == 3) {
                status = "queued";
            } else {
                status = "downloading";
            }
            String output = "completed".equals(status) ? paths.remoteToLocal(ClientHttp.text(client, "host"),
                    downloadDir + "/" + t.path("name").asText()) : null;
            long eta = t.path("eta").asLong();
            items.add(new ClientItem(client.id(), client.name(), t.path("hashString").asText().toUpperCase(),
                    t.path("name").asText(), ClientHttp.text(client, "tvCategory"), t.path("totalSize").asLong(),
                    left, eta < 0 ? null : eta, status, message, output, t.path("uploadRatio").asDouble()));
        }
        return items;
    }

    @Override
    public void remove(DownloadClient client, String hash, boolean deleteData) {
        ObjectNode args = json.createObjectNode();
        args.putArray("ids").add(hash.toLowerCase());
        args.put("delete-local-data", deleteData);
        rpc(client, "torrent-remove", args);
    }

    /** Where Catasonarr's torrents go: the directory set, or Transmission's download folder plus the category. */
    private String directory(DownloadClient client) {
        String dir = ClientHttp.text(client, "tvDirectory");
        if (!dir.isBlank()) {
            return dir;
        }
        String base = rpc(client, "session-get", json.createObjectNode()).path("download-dir").asText();
        return base.replaceAll("/+$", "") + "/" + ClientHttp.text(client, "tvCategory");
    }

    private JsonNode rpc(DownloadClient client, String method, ObjectNode arguments) {
        ObjectNode body = json.createObjectNode();
        body.put("method", method);
        body.set("arguments", arguments);
        HttpResponse<String> response = once(client, body.toString());
        if (response.statusCode() == 409) {
            sessions.put(client.id(), response.headers().firstValue("X-Transmission-Session-Id").orElse(""));
            response = once(client, body.toString());
        }
        if (response.statusCode() == 401 || response.statusCode() == 403) {
            throw new ClientException("Transmission refused the user and password");
        }
        if (response.statusCode() >= 400) {
            throw new ClientException("Transmission answered HTTP " + response.statusCode());
        }
        try {
            JsonNode reply = json.readTree(response.body());
            if (!"success".equals(reply.path("result").asText())) {
                throw new ClientException("Transmission: " + reply.path("result").asText());
            }
            return reply.path("arguments");
        } catch (IOException e) {
            throw new ClientException("Transmission did not answer with JSON");
        }
    }

    private HttpResponse<String> once(DownloadClient client, String body) {
        HttpRequest.Builder b = ClientHttp.request(ClientHttp.base(client, "/transmission/") + "/rpc")
                .header("Content-Type", "application/json")
                .header("X-Transmission-Session-Id", sessions.getOrDefault(client.id(), ""))
                .POST(HttpRequest.BodyPublishers.ofString(body));
        String user = ClientHttp.text(client, "username");
        if (!user.isBlank()) {
            String auth = user + ":" + ClientHttp.text(client, "password");
            b.header("Authorization", "Basic " + Base64.getEncoder()
                    .encodeToString(auth.getBytes(StandardCharsets.UTF_8)));
        }
        return ClientHttp.send(b.build());
    }
}
