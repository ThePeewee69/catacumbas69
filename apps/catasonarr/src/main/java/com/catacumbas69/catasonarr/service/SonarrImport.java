package com.catacumbas69.catasonarr.service;

import com.catacumbas69.catasonarr.download.DownloadClientService;
import com.catacumbas69.catasonarr.model.CustomFormat;
import com.catacumbas69.catasonarr.model.DelayProfile;
import com.catacumbas69.catasonarr.model.DownloadClient;
import com.catacumbas69.catasonarr.model.FormatItem;
import com.catacumbas69.catasonarr.model.NamingConfig;
import com.catacumbas69.catasonarr.model.ProfileItem;
import com.catacumbas69.catasonarr.model.QualityDefinition;
import com.catacumbas69.catasonarr.model.QualityProfile;
import com.catacumbas69.catasonarr.model.ReleaseProfile;
import com.catacumbas69.catasonarr.model.RemotePathMapping;
import com.catacumbas69.catasonarr.model.Series;
import com.catacumbas69.catasonarr.model.Settings;
import com.catacumbas69.catasonarr.model.Specification;
import com.catacumbas69.catasonarr.model.Tag;
import com.catacumbas69.catasonarr.providers.ProviderSchema;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;

/**
 * Imports Sonarr's settings from its database (orch.md decisions 112 and 122): quality definitions, custom formats,
 * quality profiles with their scores and the naming ({@link #importQuality}); the general settings, download clients,
 * release and delay profiles, remote path mappings and their tags ({@link #importSettings}). The rows come from
 * {@code microshift/apps/catacumbas69-apps/catasonarr/catasonarr-import-sonarr.sh}, which reads a copy of
 * {@code sonarr.db} on the Mac; Sonarr's columns, its JSON in text columns as Sonarr stores it. Sonarr's ids are
 * kept, so the series imported later point at the same profiles. Everything is replaced; a Catasonarr profile the
 * import does not have is kept only while a series uses it.
 */
@Service
public class SonarrImport {

    /**
     * What was imported.
     *
     * @param qualityDefinitions definitions imported
     * @param customFormats custom formats imported
     * @param qualityProfiles profiles imported
     * @param profilesKept profiles not in Sonarr's database kept because a series uses them
     * @param naming whether the naming was imported
     */
    public record Result(int qualityDefinitions, int customFormats, int qualityProfiles, List<String> profilesKept,
                         boolean naming) {
    }

    private static final Logger LOG = LoggerFactory.getLogger(SonarrImport.class);
    private static final Set<String> NOT_VALUES = Set.of("name", "negate", "required", "implementationname",
            "infolink", "order");

    private final MongoTemplate mongo;
    private final Sequences sequences;
    private final NamingService naming;
    private final CatasonarrEvents events;
    private final ObjectMapper json;

    /**
     * Creates the importer.
     *
     * @param mongo MongoDB
     * @param sequences ids
     * @param naming folder naming
     * @param events events
     * @param json Spring Boot's JSON mapper, for Sonarr's JSON columns
     */
    public SonarrImport(MongoTemplate mongo, Sequences sequences, NamingService naming, CatasonarrEvents events,
                        ObjectMapper json) {
        this.mongo = mongo;
        this.sequences = sequences;
        this.naming = naming;
        this.events = events;
        this.json = json;
    }

    /**
     * Imports the rows.
     *
     * @param rows {@code qualityDefinitions}, {@code customFormats}, {@code qualityProfiles} and {@code naming},
     *             each an array of rows of Sonarr's tables
     * @param actor who did it
     * @return what was imported
     */
    public Result importQuality(JsonNode rows, String actor) {
        List<QualityDefinition> definitions = new ArrayList<>();
        for (JsonNode r : rows.path("qualityDefinitions")) {
            definitions.add(new QualityDefinition(integer(r, "id"), integer(r, "quality"), text(r, "title"),
                    decimal(r, "minSize"), decimal(r, "maxSize"), decimal(r, "preferredSize")));
        }
        List<CustomFormat> formats = new ArrayList<>();
        for (JsonNode r : rows.path("customFormats")) {
            formats.add(new CustomFormat(integer(r, "id"), text(r, "name"),
                    bool(r, "includeCustomFormatWhenRenaming"), specifications(parse(r, "specifications"))));
        }
        List<QualityProfile> profiles = new ArrayList<>();
        for (JsonNode r : rows.path("qualityProfiles")) {
            profiles.add(new QualityProfile(integer(r, "id"), text(r, "name"), bool(r, "upgradeAllowed"),
                    integer(r, "cutoff"), items(parse(r, "items")), integer(r, "minFormatScore"),
                    integer(r, "cutoffFormatScore"), integer(r, "minUpgradeFormatScore"),
                    formatItems(parse(r, "formatItems"))));
        }
        if (definitions.isEmpty() || profiles.isEmpty()) {
            throw new BadRequestException(null, "Sonarr's database has no quality definitions or profiles: "
                    + "is it a copy of sonarr.db?");
        }

        mongo.remove(new Query(), QualityDefinition.class);
        mongo.insert(definitions, QualityDefinition.class);
        sequences.atLeast(QualityService.DEFINITION, definitions.stream().mapToInt(QualityDefinition::id).max()
                .orElse(0));

        mongo.remove(new Query(), CustomFormat.class);
        if (!formats.isEmpty()) {
            mongo.insert(formats, CustomFormat.class);
        }
        sequences.atLeast(QualityService.FORMAT, formats.stream().mapToInt(CustomFormat::id).max().orElse(0));

        Set<Integer> imported = profiles.stream().map(QualityProfile::id).collect(Collectors.toSet());
        Set<String> importedNames = profiles.stream().map(QualityProfile::name).collect(Collectors.toSet());
        Set<Integer> used = mongo.findDistinct(new Query(), "qualityProfileId", Series.class, Integer.class)
                .stream().collect(Collectors.toSet());
        List<String> kept = new ArrayList<>();
        for (QualityProfile old : mongo.findAll(QualityProfile.class)) {
            if (!imported.contains(old.id()) && used.contains(old.id()) && !importedNames.contains(old.name())) {
                kept.add(old.name() + " (" + old.id() + ")");
            } else {
                mongo.remove(Query.query(Criteria.where("id").is(old.id())), QualityProfile.class);
            }
        }
        mongo.insert(profiles, QualityProfile.class);
        sequences.atLeast(QualityService.PROFILE, profiles.stream().mapToInt(QualityProfile::id).max().orElse(0));

        boolean namingImported = false;
        JsonNode n = rows.path("naming").path(0);
        if (!n.isMissingNode() && text(n, "seriesFolderFormat") != null) {
            naming.save(new NamingConfig(NamingConfig.ID, text(n, "seriesFolderFormat"),
                    orDefault(text(n, "seasonFolderFormat"), NamingConfig.DEFAULT.seasonFolderFormat()),
                    orDefault(text(n, "specialsFolderFormat"), NamingConfig.DEFAULT.specialsFolderFormat()),
                    n.has("replaceIllegalCharacters") ? bool(n, "replaceIllegalCharacters") : true,
                    n.has("colonReplacementFormat") ? integer(n, "colonReplacementFormat") : 4,
                    orDefault(text(n, "customColonReplacementFormat"), ""),
                    n.has("renameEpisodes") && bool(n, "renameEpisodes"),
                    orDefault(text(n, "standardEpisodeFormat"), NamingConfig.DEFAULT.standardEpisodeFormat()),
                    orDefault(text(n, "dailyEpisodeFormat"), NamingConfig.DEFAULT.dailyEpisodeFormat()),
                    orDefault(text(n, "animeEpisodeFormat"), NamingConfig.DEFAULT.animeEpisodeFormat()),
                    n.has("multiEpisodeStyle") ? integer(n, "multiEpisodeStyle") : 5));
            namingImported = true;
        }

        Result result = new Result(definitions.size(), formats.size(), profiles.size(), kept, namingImported);
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("qualityDefinitions", result.qualityDefinitions());
        details.put("customFormats", result.customFormats());
        details.put("qualityProfiles", profiles.stream().map(p -> p.name() + " (" + p.id() + ")").toList());
        details.put("profilesKept", kept);
        details.put("naming", namingImported ? naming.get().seriesFolderFormat() : "not imported");
        events.action("import", "sonarr-quality", "sonarr.db", actor, details);
        LOG.info("Imported from Sonarr's database: {} definitions, {} custom formats, {} profiles, naming {} (by {})",
                result.qualityDefinitions(), result.customFormats(), result.qualityProfiles(), namingImported, actor);
        return result;
    }

    /**
     * What the second part of the import brought (orch.md decision 122).
     *
     * @param settings the settings imported, by name
     * @param downloadClients the download clients imported (with their category set to {@code catasonarr})
     * @param downloadClientsSkipped the ones Catasonarr cannot use (only qBittorrent and Transmission)
     * @param hostsChanged clients whose cluster-internal host was replaced by its name in the domain
     * @param releaseProfiles release profiles imported
     * @param delayProfiles delay profiles imported
     * @param remotePathMappings remote path mappings imported
     * @param tags tags created for them
     */
    public record SettingsResult(List<String> settings, List<String> downloadClients,
                                 List<String> downloadClientsSkipped, List<String> hostsChanged, int releaseProfiles,
                                 int delayProfiles, int remotePathMappings, List<String> tags) {
    }

    /** Sonarr's {@code Config} keys (lower case, as Sonarr keeps them) and the settings they fill. */
    private static final Map<String, String> CONFIG_KEYS = Map.ofEntries(
            Map.entry("rsssyncinterval", "rssSyncInterval"), Map.entry("minimumage", "minimumAge"),
            Map.entry("maximumsize", "maximumSize"),
            Map.entry("enablecompleteddownloadhandling", "enableCompletedDownloadHandling"),
            Map.entry("autoredownloadfailed", "autoRedownloadFailed"),
            Map.entry("autoredownloadfailedfrominteractivesearch", "autoRedownloadFailedFromInteractiveSearch"),
            Map.entry("downloadpropersandrepacks", "downloadPropersAndRepacks"),
            Map.entry("autounmonitorpreviouslydownloadedepisodes", "autoUnmonitorPreviouslyDownloadedEpisodes"),
            Map.entry("createemptyseriesfolders", "createEmptySeriesFolders"),
            Map.entry("deleteemptyfolders", "deleteEmptyFolders"),
            Map.entry("skipfreespacecheckwhenimporting", "skipFreeSpaceCheckWhenImporting"),
            Map.entry("minimumfreespacewhenimporting", "minimumFreeSpaceWhenImporting"),
            Map.entry("importextrafiles", "importExtraFiles"), Map.entry("extrafileextensions", "extraFileExtensions"),
            Map.entry("episodetitlerequired", "episodeTitleRequired"));

    /**
     * Imports Sonarr's general settings (not the recycle bin, which stays {@code /data/.recycle}), its qBittorrent
     * and Transmission clients (passwords included; the category becomes {@code catasonarr}, so Sonarr's downloads
     * are never touched, and a host inside K3s becomes its name in the domain, reached over http), its release and
     * delay profiles, its remote path mappings and the tags they use.
     *
     * @param rows {@code config}, {@code downloadClients}, {@code releaseProfiles}, {@code delayProfiles},
     *             {@code remotePathMappings} and {@code tags}, each an array of rows of Sonarr's tables
     * @param settingsService where the settings go
     * @param actor who did it
     * @return what was imported
     */
    public SettingsResult importSettings(JsonNode rows, SettingsService settingsService, String actor) {
        List<String> createdTags = new ArrayList<>();
        Map<Integer, Integer> tagIds = tags(rows.path("tags"), createdTags);

        List<String> settingNames = new ArrayList<>();
        if (rows.path("config").size() > 0) {
            Map<String, Object> values = new LinkedHashMap<>(json.convertValue(settingsService.get(), Map.class));
            for (JsonNode r : rows.path("config")) {
                String key = text(r, "key");
                String target = key == null ? null : CONFIG_KEYS.get(key.toLowerCase(Locale.ROOT));
                if (target == null) {
                    continue;
                }
                Object current = values.get(target);
                String raw = text(r, "value") == null ? "" : text(r, "value").strip();
                Object value;
                if (current instanceof Boolean) {
                    value = "true".equalsIgnoreCase(raw);
                } else if (current instanceof Number) {
                    Integer n = com.catacumbas69.catasonarr.parser.ParserText.tryParseInt(raw);
                    if (n == null) {
                        continue;
                    }
                    value = n;
                } else {
                    value = "extraFileExtensions".equals(target) ? raw : lowerFirst(raw);
                }
                values.put(target, value);
                settingNames.add(target);
            }
            settingsService.save(json.convertValue(values, Settings.class), actor);
        }

        List<String> clients = new ArrayList<>();
        List<String> skipped = new ArrayList<>();
        List<String> hostsChanged = new ArrayList<>();
        List<DownloadClient> newClients = new ArrayList<>();
        for (JsonNode r : rows.path("downloadClients")) {
            String implementation = text(r, "implementation");
            ProviderSchema.Implementation schema = ProviderSchema.byName(implementation);
            if (schema == null || ProviderSchema.TORZNAB.equals(schema)) {
                skipped.add(text(r, "name") + " (" + implementation + ")");
                continue;
            }
            JsonNode settings = parseObject(r, "settings");
            Map<String, Object> values = new LinkedHashMap<>();
            for (ProviderSchema.Field f : schema.fields()) {
                JsonNode v = field(settings, f.name());
                values.put(f.name(), v.isMissingNode() || v.isNull() ? f.defaultValue() : json.convertValue(v,
                        Object.class));
            }
            values.put("tvCategory", "catasonarr");
            values.put("tvImportedCategory", "");
            String host = String.valueOf(values.get("host"));
            if (!host.contains(".") || host.endsWith(".svc") || host.endsWith(".svc.cluster.local")) {
                String name = host.contains(".") ? host.substring(0, host.indexOf('.')) : host;
                values.put("host", name + ".catacumbas69.com");
                values.put("port", 80);
                values.put("useSsl", false);
                hostsChanged.add(text(r, "name") + ": " + host + " → " + name + ".catacumbas69.com:80");
            }
            newClients.add(new DownloadClient(integer(r, "id"), text(r, "name"), schema.implementation(), values,
                    bool(r, "enable"), Math.max(1, integer(r, "priority")), !field(r, "removeCompletedDownloads")
                    .isMissingNode() ? bool(r, "removeCompletedDownloads") : true,
                    !field(r, "removeFailedDownloads").isMissingNode() ? bool(r, "removeFailedDownloads") : true,
                    remap(parse(r, "tags"), tagIds)));
            clients.add(text(r, "name"));
        }
        if (!newClients.isEmpty()) {
            mongo.remove(new Query(), DownloadClient.class);
            mongo.insert(newClients, DownloadClient.class);
            sequences.atLeast(DownloadClientService.SEQUENCE, newClients.stream().mapToInt(DownloadClient::id).max()
                    .orElse(0));
        }

        List<ReleaseProfile> release = new ArrayList<>();
        for (JsonNode r : rows.path("releaseProfiles")) {
            release.add(new ReleaseProfile(integer(r, "id"), orDefault(text(r, "name"), ""), bool(r, "enabled"),
                    strings(parse(r, "required")), strings(parse(r, "ignored")), integer(r, "indexerId"),
                    remap(parse(r, "tags"), tagIds)));
        }
        if (rows.has("releaseProfiles")) {
            mongo.remove(new Query(), ReleaseProfile.class);
            if (!release.isEmpty()) {
                mongo.insert(release, ReleaseProfile.class);
            }
            sequences.atLeast(ProfileService.RELEASE, release.stream().mapToInt(ReleaseProfile::id).max().orElse(0));
        }

        List<DelayProfile> delay = new ArrayList<>();
        for (JsonNode r : rows.path("delayProfiles")) {
            JsonNode protocol = field(r, "preferredProtocol");
            String preferred = protocol.isTextual() ? protocol.asText().toLowerCase(Locale.ROOT)
                    : protocol.asInt(2) == 1 ? "usenet" : "torrent";
            delay.add(new DelayProfile(integer(r, "id"), bool(r, "enableUsenet"), bool(r, "enableTorrent"),
                    preferred, integer(r, "usenetDelay"), integer(r, "torrentDelay"), integer(r, "order"),
                    bool(r, "bypassIfHighestQuality"), bool(r, "bypassIfAboveCustomFormatScore"),
                    integer(r, "minimumCustomFormatScore"), remap(parse(r, "tags"), tagIds)));
        }
        if (!delay.isEmpty()) {
            mongo.remove(new Query(), DelayProfile.class);
            mongo.insert(delay, DelayProfile.class);
            sequences.atLeast(ProfileService.DELAY, delay.stream().mapToInt(DelayProfile::id).max().orElse(1));
        }

        List<RemotePathMapping> mappings = new ArrayList<>();
        for (JsonNode r : rows.path("remotePathMappings")) {
            mappings.add(new RemotePathMapping(integer(r, "id"), text(r, "host"), text(r, "remotePath"),
                    text(r, "localPath")));
        }
        if (rows.has("remotePathMappings")) {
            mongo.remove(new Query(), RemotePathMapping.class);
            if (!mappings.isEmpty()) {
                mongo.insert(mappings, RemotePathMapping.class);
            }
            sequences.atLeast(RemotePathMappingService.SEQUENCE, mappings.stream().mapToInt(RemotePathMapping::id)
                    .max().orElse(0));
        }

        SettingsResult result = new SettingsResult(settingNames, clients, skipped, hostsChanged, release.size(),
                delay.size(), mappings.size(), createdTags);
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("settings", settingNames);
        details.put("downloadClients", clients);
        details.put("downloadClientsSkipped", skipped);
        details.put("hostsChanged", hostsChanged);
        details.put("releaseProfiles", release.size());
        details.put("delayProfiles", delay.size());
        details.put("remotePathMappings", mappings.size());
        details.put("tags", createdTags);
        events.action("import", "sonarr-settings", "sonarr.db", actor, details);
        LOG.info("Imported Sonarr's settings: {} settings, clients {} (skipped {}), {} release and {} delay profiles,"
                + " {} mappings (by {})", settingNames.size(), clients, skipped, release.size(), delay.size(),
                mappings.size(), actor);
        return result;
    }

    /** Sonarr's tags by label: an existing tag is reused, a new one created. Sonarr's id → Catasonarr's. */
    private Map<Integer, Integer> tags(JsonNode rows, List<String> created) {
        Map<Integer, Integer> ids = new java.util.HashMap<>();
        Map<String, Integer> existing = new java.util.HashMap<>();
        mongo.findAll(Tag.class).forEach(t -> existing.put(t.label().toLowerCase(Locale.ROOT), t.id()));
        for (JsonNode r : rows) {
            String label = text(r, "label");
            if (label == null || label.isBlank()) {
                continue;
            }
            Integer id = existing.get(label.toLowerCase(Locale.ROOT));
            if (id == null) {
                id = sequences.next(TagService.SEQUENCE);
                mongo.insert(new Tag(id, label.toLowerCase(Locale.ROOT)));
                existing.put(label.toLowerCase(Locale.ROOT), id);
                created.add(label);
            }
            ids.put(integer(r, "id"), id);
        }
        return ids;
    }

    private static Set<Integer> remap(JsonNode array, Map<Integer, Integer> tagIds) {
        Set<Integer> out = new java.util.LinkedHashSet<>();
        for (JsonNode t : array) {
            Integer mapped = tagIds.get(t.asInt());
            if (mapped != null) {
                out.add(mapped);
            }
        }
        return out;
    }

    private static List<String> strings(JsonNode array) {
        List<String> out = new ArrayList<>();
        for (JsonNode s : array) {
            if (!s.asText().isBlank()) {
                out.add(s.asText());
            }
        }
        return out;
    }

    private JsonNode parseObject(JsonNode row, String column) {
        JsonNode value = field(row, column);
        if (value.isObject()) {
            return value;
        }
        if (value.isMissingNode() || value.isNull() || value.asText().isBlank()) {
            return json.createObjectNode();
        }
        try {
            return json.readTree(value.asText());
        } catch (JsonProcessingException e) {
            throw new BadRequestException(column, "Not JSON in Sonarr's column " + column + ": " + e.getMessage());
        }
    }

    private List<ProfileItem> items(JsonNode array) {
        List<ProfileItem> items = new ArrayList<>();
        for (JsonNode i : array) {
            JsonNode quality = field(i, "quality");
            Integer q = quality.isMissingNode() || quality.isNull() ? null
                    : quality.isObject() ? field(quality, "id").asInt() : quality.asInt();
            JsonNode id = field(i, "id");
            items.add(new ProfileItem(q == null && !id.isMissingNode() && !id.isNull() ? id.asInt() : null,
                    q == null ? text(i, "name") : null, q, items(field(i, "items")), field(i, "allowed").asBoolean()));
        }
        return items;
    }

    private static List<FormatItem> formatItems(JsonNode array) {
        List<FormatItem> items = new ArrayList<>();
        for (JsonNode i : array) {
            JsonNode format = field(i, "format");
            int id = format.isObject() ? field(format, "id").asInt() : format.asInt();
            items.add(new FormatItem(id, field(i, "score").asInt()));
        }
        return items;
    }

    private static List<Specification> specifications(JsonNode array) {
        List<Specification> specs = new ArrayList<>();
        for (JsonNode wrapper : array) {
            String type = text(wrapper, "type");
            JsonNode body = field(wrapper, "body");
            Map<String, Object> values = new LinkedHashMap<>();
            Iterator<Map.Entry<String, JsonNode>> fields = body.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> f = fields.next();
                if (!NOT_VALUES.contains(f.getKey().toLowerCase(Locale.ROOT))) {
                    values.put(lowerFirst(f.getKey()), value(f.getValue()));
                }
            }
            specs.add(new Specification(type, text(body, "name"), field(body, "negate").asBoolean(),
                    field(body, "required").asBoolean(), values));
        }
        return specs;
    }

    private JsonNode parse(JsonNode row, String column) {
        JsonNode value = field(row, column);
        if (value.isArray()) {
            return value;
        }
        if (value.isMissingNode() || value.isNull() || value.asText().isBlank()) {
            return json.createArrayNode();
        }
        try {
            return json.readTree(value.asText());
        } catch (JsonProcessingException e) {
            throw new BadRequestException(column, "Not JSON in Sonarr's column " + column + ": " + e.getMessage());
        }
    }

    private static Object value(JsonNode v) {
        if (v.isIntegralNumber()) {
            return v.asLong();
        }
        if (v.isNumber()) {
            return v.asDouble();
        }
        if (v.isBoolean()) {
            return v.asBoolean();
        }
        return v.isNull() ? null : v.asText();
    }

    /** A field by name, whatever its case: Sonarr writes camel case, SQLite's columns are Pascal case. */
    private static JsonNode field(JsonNode node, String name) {
        JsonNode v = node.path(name);
        if (!v.isMissingNode()) {
            return v;
        }
        Iterator<String> names = node.fieldNames();
        while (names.hasNext()) {
            String n = names.next();
            if (n.equalsIgnoreCase(name)) {
                return node.path(n);
            }
        }
        return v;
    }

    private static int integer(JsonNode node, String name) {
        return field(node, name).asInt(0);
    }

    private static boolean bool(JsonNode node, String name) {
        JsonNode v = field(node, name);
        return v.isBoolean() ? v.asBoolean() : v.asInt(0) != 0;
    }

    private static Double decimal(JsonNode node, String name) {
        JsonNode v = field(node, name);
        return v.isMissingNode() || v.isNull() ? null : v.asDouble();
    }

    private static String text(JsonNode node, String name) {
        JsonNode v = field(node, name);
        return v.isMissingNode() || v.isNull() ? null : v.asText();
    }

    private static String orDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private static String lowerFirst(String s) {
        return s.isEmpty() ? s : Character.toLowerCase(s.charAt(0)) + s.substring(1);
    }
}
