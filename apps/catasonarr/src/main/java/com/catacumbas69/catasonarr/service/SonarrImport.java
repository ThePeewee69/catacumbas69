package com.catacumbas69.catasonarr.service;

import com.catacumbas69.catasonarr.model.CustomFormat;
import com.catacumbas69.catasonarr.model.FormatItem;
import com.catacumbas69.catasonarr.model.NamingConfig;
import com.catacumbas69.catasonarr.model.ProfileItem;
import com.catacumbas69.catasonarr.model.QualityDefinition;
import com.catacumbas69.catasonarr.model.QualityProfile;
import com.catacumbas69.catasonarr.model.Series;
import com.catacumbas69.catasonarr.model.Specification;
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
 * Imports what touches quality from Sonarr's database (orch.md decision 112): quality definitions, custom formats,
 * quality profiles with their scores, and the folder naming. The rows come from
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
                    orDefault(text(n, "customColonReplacementFormat"), "")));
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
