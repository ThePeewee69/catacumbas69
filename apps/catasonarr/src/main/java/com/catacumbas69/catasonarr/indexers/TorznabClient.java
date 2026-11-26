package com.catacumbas69.catasonarr.indexers;

import com.catacumbas69.catasonarr.model.Indexer;
import com.catacumbas69.catasonarr.model.ReleaseInfo;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import org.springframework.stereotype.Component;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

/**
 * Reads a Torznab feed (Prowlarr's, one per tracker), as Sonarr's {@code TorznabRssParser} and
 * {@code NewznabRequestGenerator} (GPL-3.0, orch.md decision 122): its capabilities, the RSS (newest releases) and
 * the searches by TheTVDB's or IMDb's id or by title, 100 releases a page.
 */
@Component
public class TorznabClient {

    /** Releases per page, as Sonarr asks. */
    public static final int PAGE_SIZE = 100;

    private static final String TORZNAB = "http://torznab.com/schemas/2015/feed";
    private static final Duration TIMEOUT = Duration.ofSeconds(100);
    private static final Duration CAPS_KEPT = Duration.ofHours(24);

    /** What a feed can search by, from its {@code t=caps}. */
    public record Capabilities(boolean tvSearch, Set<String> tvSearchParameters, Instant read) {
    }

    /** A feed's error ({@code <error code=… description=…/>}) or a failed request. */
    public static class TorznabException extends RuntimeException {

        private static final long serialVersionUID = 1L;

        /**
         * An error.
         *
         * @param message what went wrong
         */
        public TorznabException(String message) {
            super(message);
        }
    }

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15))
            .followRedirects(HttpClient.Redirect.NORMAL).build();
    private final Map<Integer, Capabilities> caps = new ConcurrentHashMap<>();

    /**
     * A feed's capabilities, read once a day.
     *
     * @param indexer the indexer
     * @return what it can search by
     */
    public Capabilities capabilities(Indexer indexer) {
        Capabilities known = caps.get(indexer.id());
        if (known != null && known.read().plus(CAPS_KEPT).isAfter(Instant.now())) {
            return known;
        }
        Document doc = get(base(indexer) + "?t=caps" + key(indexer));
        NodeList tv = doc.getElementsByTagName("tv-search");
        boolean available = tv.getLength() > 0
                && !"no".equalsIgnoreCase(((Element) tv.item(0)).getAttribute("available"));
        Set<String> params = new HashSet<>();
        if (available) {
            for (String p : ((Element) tv.item(0)).getAttribute("supportedParams").split(",")) {
                if (!p.isBlank()) {
                    params.add(p.strip().toLowerCase());
                }
            }
        }
        Capabilities c = new Capabilities(available, params, Instant.now());
        if (indexer.id() > 0) {
            caps.put(indexer.id(), c);
        }
        return c;
    }

    /**
     * The newest releases, for the RSS sync: one page of the TV categories.
     *
     * @param indexer the indexer
     * @return the releases
     */
    public List<ReleaseInfo> rss(Indexer indexer) {
        return fetch(indexer, "tvsearch", "", categories(indexer, true), 1);
    }

    /**
     * A search.
     *
     * @param indexer the indexer
     * @param parameters e.g. {@code &tvdbid=81189&season=1&ep=2}
     * @param anime whether to search the anime categories too
     * @param maxPages pages at most
     * @return the releases
     */
    public List<ReleaseInfo> search(Indexer indexer, String parameters, boolean anime, int maxPages) {
        return fetch(indexer, "tvsearch", parameters, categories(indexer, anime), maxPages);
    }

    /**
     * Forgets a feed's capabilities, when it changes.
     *
     * @param indexerId the indexer
     */
    public void forget(int indexerId) {
        caps.remove(indexerId);
    }

    private List<ReleaseInfo> fetch(Indexer indexer, String type, String parameters, String categories,
                                    int maxPages) {
        List<ReleaseInfo> all = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (int page = 0; page < maxPages; page++) {
            String url = base(indexer) + "?t=" + type + "&cat=" + categories + "&extended=1"
                    + text(indexer.settings().get("additionalParameters")) + key(indexer)
                    + "&offset=" + page * PAGE_SIZE + "&limit=" + PAGE_SIZE + parameters;
            List<ReleaseInfo> releases = parse(indexer, get(url));
            int before = all.size();
            releases.stream().filter(r -> seen.add(r.guid())).forEach(all::add);
            if (releases.size() < PAGE_SIZE || all.size() == before) {
                break;
            }
        }
        return all;
    }

    private Document get(String url) {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url)).timeout(TIMEOUT)
                .header("Accept", "application/rss+xml, text/rss+xml, application/xml, text/xml")
                .header("User-Agent", "Catasonarr").GET().build();
        HttpResponse<byte[]> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
        } catch (IOException e) {
            throw new TorznabException("Unable to connect to the indexer: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new TorznabException("Interrupted");
        }
        String type = response.headers().firstValue("Content-Type").orElse("");
        if (response.statusCode() >= 400 && !type.contains("xml")) {
            throw new TorznabException("The indexer answered HTTP " + response.statusCode());
        }
        Document doc = xml(response.body());
        NodeList error = doc.getElementsByTagName("error");
        if (error.getLength() > 0) {
            Element e = (Element) error.item(0);
            int code = parseInt(e.getAttribute("code"), 0);
            String description = e.getAttribute("description");
            if (code >= 100 && code <= 199) {
                throw new TorznabException("Invalid API key");
            }
            throw new TorznabException("Torznab error detected: " + description);
        }
        return doc;
    }

    private static List<ReleaseInfo> parse(Indexer indexer, Document doc) {
        List<ReleaseInfo> releases = new ArrayList<>();
        NodeList items = doc.getElementsByTagName("item");
        int priority = indexer.priority();
        for (int i = 0; i < items.getLength(); i++) {
            Element item = (Element) items.item(i);
            String title = child(item, "title");
            if (title == null || title.isBlank()) {
                continue;
            }
            Map<String, List<String>> attrs = attributes(item);
            Element enclosure = first(item, "enclosure");
            String link = child(item, "link");
            String downloadUrl = enclosure != null && !enclosure.getAttribute("url").isBlank()
                    ? enclosure.getAttribute("url") : link;
            String guid = child(item, "guid");
            String comments = child(item, "comments");
            long size = parseLong(one(attrs, "size"), enclosure == null ? 0
                    : parseLong(enclosure.getAttribute("length"), 0));
            Integer seeders = one(attrs, "seeders") == null ? null : parseInt(one(attrs, "seeders"), 0);
            Integer peers = one(attrs, "peers") != null ? Integer.valueOf(parseInt(one(attrs, "peers"), 0))
                    : seeders != null && one(attrs, "leechers") != null
                    ? Integer.valueOf(seeders + parseInt(one(attrs, "leechers"), 0)) : null;
            int imdb = parseInt(one(attrs, "imdb"), 0);
            releases.add(new ReleaseInfo(guid == null || guid.isBlank() ? downloadUrl : guid, title.strip(), size,
                    downloadUrl, one(attrs, "magneturl"), upper(one(attrs, "infohash")),
                    comments == null ? null : comments.replaceAll("#comments$", ""), indexer.id(), indexer.name(),
                    priority, parseInt(one(attrs, "tvdbid"), 0),
                    imdb > 0 ? String.format("tt%07d", imdb) : null, date(child(item, "pubDate")), seeders, peers,
                    flags(attrs)));
        }
        return releases;
    }

    /** Sonarr's indexer flags from the download and upload factors and the tags. */
    private static int flags(Map<String, List<String>> attrs) {
        int flags = 0;
        double download = parseDouble(one(attrs, "downloadvolumefactor"), 1);
        double upload = parseDouble(one(attrs, "uploadvolumefactor"), 1);
        if (download == 0.5) {
            flags |= 2;
        }
        if (download == 0.75) {
            flags |= 64;
        }
        if (download == 0.25) {
            flags |= 32;
        }
        if (download == 0.0) {
            flags |= 1;
        }
        if (upload == 2.0) {
            flags |= 4;
        }
        List<String> tags = attrs.getOrDefault("tag", List.of());
        if (tags.stream().anyMatch("internal"::equalsIgnoreCase)) {
            flags |= 8;
        }
        if (tags.stream().anyMatch("scene"::equalsIgnoreCase)) {
            flags |= 16;
        }
        if (tags.stream().anyMatch("nuked"::equalsIgnoreCase)) {
            flags |= 128;
        }
        return flags;
    }

    private static String base(Indexer indexer) {
        String baseUrl = text(indexer.settings().get("baseUrl")).replaceAll("/+$", "");
        String apiPath = text(indexer.settings().get("apiPath"));
        if (apiPath.isEmpty()) {
            apiPath = "/api";
        }
        return baseUrl + apiPath.replaceAll("/+$", "");
    }

    private static String key(Indexer indexer) {
        String key = text(indexer.settings().get("apiKey"));
        return key.isEmpty() ? "" : "&apikey=" + URLEncoder.encode(key, StandardCharsets.UTF_8);
    }

    private static String categories(Indexer indexer, boolean anime) {
        Set<Object> cats = new java.util.LinkedHashSet<>(list(indexer.settings().get("categories")));
        if (anime) {
            cats.addAll(list(indexer.settings().get("animeCategories")));
        }
        return cats.stream().map(String::valueOf).collect(Collectors.joining(","));
    }

    private static List<Object> list(Object value) {
        if (value instanceof List<?> l) {
            return new ArrayList<>(l);
        }
        return value == null || value.toString().isBlank() ? List.of() : List.of(value);
    }

    private static Document xml(byte[] body) {
        try {
            DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
            f.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            f.setExpandEntityReferences(false);
            f.setNamespaceAware(true);
            DocumentBuilder b = f.newDocumentBuilder();
            return b.parse(new ByteArrayInputStream(body));
        } catch (ParserConfigurationException | SAXException | IOException e) {
            throw new TorznabException("The indexer did not answer with XML: " + e.getMessage());
        }
    }

    private static Map<String, List<String>> attributes(Element item) {
        Map<String, List<String>> attrs = new java.util.HashMap<>();
        NodeList list = item.getElementsByTagNameNS(TORZNAB, "attr");
        if (list.getLength() == 0) {
            list = item.getElementsByTagName("torznab:attr");
        }
        for (int i = 0; i < list.getLength(); i++) {
            Element a = (Element) list.item(i);
            attrs.computeIfAbsent(a.getAttribute("name").toLowerCase(), k -> new ArrayList<>())
                    .add(a.getAttribute("value"));
        }
        return attrs;
    }

    private static String one(Map<String, List<String>> attrs, String name) {
        List<String> values = attrs.get(name);
        return values == null || values.isEmpty() || values.get(0).isBlank() ? null : values.get(0);
    }

    private static Element first(Element parent, String name) {
        NodeList list = parent.getElementsByTagName(name);
        return list.getLength() == 0 ? null : (Element) list.item(0);
    }

    private static String child(Element parent, String name) {
        Element e = first(parent, name);
        return e == null ? null : e.getTextContent();
    }

    private static Instant date(String text) {
        if (text == null || text.isBlank()) {
            return Instant.now();
        }
        try {
            return ZonedDateTime.parse(text.strip(), DateTimeFormatter.RFC_1123_DATE_TIME).toInstant();
        } catch (DateTimeParseException e) {
            try {
                return Instant.parse(text.strip());
            } catch (DateTimeParseException e2) {
                return Instant.now();
            }
        }
    }

    private static String text(Object o) {
        return o == null ? "" : o.toString();
    }

    private static String upper(String s) {
        return s == null ? null : s.toUpperCase();
    }

    private static int parseInt(String s, int fallback) {
        try {
            return s == null ? fallback : Integer.parseInt(s.strip());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static long parseLong(String s, long fallback) {
        try {
            return s == null ? fallback : Long.parseLong(s.strip());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static double parseDouble(String s, double fallback) {
        try {
            return s == null ? fallback : Double.parseDouble(s.strip());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
