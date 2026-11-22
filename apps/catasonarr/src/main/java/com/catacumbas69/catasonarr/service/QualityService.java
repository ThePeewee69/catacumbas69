package com.catacumbas69.catasonarr.service;

import com.catacumbas69.catasonarr.model.CustomFormat;
import com.catacumbas69.catasonarr.model.FormatItem;
import com.catacumbas69.catasonarr.model.ProfileItem;
import com.catacumbas69.catasonarr.model.QualityDefinition;
import com.catacumbas69.catasonarr.model.QualityProfile;
import com.catacumbas69.catasonarr.model.Series;
import com.catacumbas69.catasonarr.quality.Qualities;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;

/**
 * Quality profiles, quality definitions and custom formats, as Sonarr keeps them (orch.md decision 112). Sonarr's
 * defaults until the import from Sonarr's database replaces them with the user's own, keeping Sonarr's ids.
 */
@Service
public class QualityService {

    /** Sequence names. */
    public static final String PROFILE = "qualityprofile";
    /** Sequence names. */
    public static final String DEFINITION = "qualitydefinition";
    /** Sequence names. */
    public static final String FORMAT = "customformat";

    private static final int FIRST_GROUP_ID = 1000;

    private final MongoTemplate mongo;
    private final Sequences sequences;
    private final CatasonarrEvents events;

    /**
     * Creates the service.
     *
     * @param mongo MongoDB
     * @param sequences ids
     * @param events events
     */
    public QualityService(MongoTemplate mongo, Sequences sequences, CatasonarrEvents events) {
        this.mongo = mongo;
        this.sequences = sequences;
        this.events = events;
    }

    // ── Profiles ──────────────────────────────────────────────────────────────

    /**
     * Returns every quality profile.
     *
     * @return the profiles by id
     */
    public List<QualityProfile> profiles() {
        return mongo.find(new Query().with(Sort.by("id")), QualityProfile.class);
    }

    /**
     * Returns a quality profile.
     *
     * @param id the id
     * @return the profile
     * @throws NotFoundException when there is none
     */
    public QualityProfile profile(int id) {
        QualityProfile profile = mongo.findById(id, QualityProfile.class);
        if (profile == null) {
            throw new NotFoundException("Quality profile " + id);
        }
        return profile;
    }

    /**
     * Returns whether a quality profile exists.
     *
     * @param id the id
     * @return {@code true} if it does
     */
    public boolean profileExists(int id) {
        return mongo.exists(Query.query(Criteria.where("id").is(id)), QualityProfile.class);
    }

    /**
     * Adds a quality profile.
     *
     * @param profile the profile; its id is ignored
     * @param actor who did it
     * @return the profile with its id
     */
    public QualityProfile addProfile(QualityProfile profile, String actor) {
        validate(profile, 0);
        QualityProfile saved = withId(profile, sequences.next(PROFILE));
        mongo.insert(saved);
        events.change("qualityprofile", "added", subject(saved), actor, null, describe(saved));
        return saved;
    }

    /**
     * Replaces a quality profile.
     *
     * @param id the id
     * @param profile the new profile
     * @param actor who did it
     * @return the profile
     */
    public QualityProfile updateProfile(int id, QualityProfile profile, String actor) {
        QualityProfile before = profile(id);
        validate(profile, id);
        QualityProfile saved = withId(profile, id);
        mongo.save(saved);
        events.change("qualityprofile", "updated", subject(saved), actor, describe(before), describe(saved));
        return saved;
    }

    /**
     * Deletes a quality profile no series uses.
     *
     * @param id the id
     * @param actor who did it
     */
    public void deleteProfile(int id, String actor) {
        QualityProfile before = profile(id);
        if (mongo.exists(Query.query(Criteria.where("qualityProfileId").is(id)), Series.class)) {
            throw new BadRequestException(null, "QualityProfile [" + before.name() + "] is in use.");
        }
        mongo.remove(Query.query(Criteria.where("id").is(id)), QualityProfile.class);
        events.change("qualityprofile", "deleted", subject(before), actor, describe(before), null);
    }

    /**
     * Sonarr's {@code qualityprofile/schema}: a profile with every quality, none allowed.
     *
     * @return the template
     */
    public QualityProfile schema() {
        return defaultProfile("", -1);
    }

    /**
     * Sonarr's six default profiles: Any, SD, HD-720p, HD-1080p, Ultra-HD and HD - 720p/1080p.
     *
     * @return the profiles, without ids
     */
    public static List<QualityProfile> defaultProfiles() {
        return List.of(
                defaultProfile("Any", 1, 1, 12, 8, 2, 13, 22, 4, 9, 14, 5, 15, 3, 6, 7),
                defaultProfile("SD", 1, 1, 12, 8, 2, 13, 22),
                defaultProfile("HD-720p", 4, 4, 14, 5, 6),
                defaultProfile("HD-1080p", 9, 9, 15, 3, 7),
                defaultProfile("Ultra-HD", 16, 16, 17, 18, 19),
                defaultProfile("HD - 720p/1080p", 4, 4, 9, 14, 5, 15, 3, 6, 7));
    }

    // ── Definitions ───────────────────────────────────────────────────────────

    /**
     * Returns every quality definition, worst quality first.
     *
     * @return the definitions
     */
    public List<QualityDefinition> definitions() {
        List<QualityDefinition> all = new ArrayList<>(mongo.findAll(QualityDefinition.class));
        all.sort(Comparator.comparingInt(d -> weight(d.quality())));
        return all;
    }

    /**
     * Returns a quality definition.
     *
     * @param id the id
     * @return the definition
     */
    public QualityDefinition definition(int id) {
        QualityDefinition d = mongo.findById(id, QualityDefinition.class);
        if (d == null) {
            throw new NotFoundException("Quality definition " + id);
        }
        return d;
    }

    /**
     * Changes the title and sizes of quality definitions.
     *
     * @param changes the definitions, by id
     * @param actor who did it
     * @return the definitions as saved
     */
    public List<QualityDefinition> updateDefinitions(List<QualityDefinition> changes, String actor) {
        List<QualityDefinition> saved = new ArrayList<>();
        for (QualityDefinition change : changes) {
            QualityDefinition before = definition(change.id());
            if (change.minSize() != null && change.maxSize() != null && change.minSize() > change.maxSize()) {
                throw new BadRequestException("minSize", "Minimum size must be below the maximum size");
            }
            QualityDefinition after = new QualityDefinition(before.id(), before.quality(),
                    change.title() == null || change.title().isBlank() ? before.title() : change.title(),
                    change.minSize(), change.maxSize(), change.preferredSize());
            mongo.save(after);
            events.change("qualitydefinition", "updated", after.title(), actor, Map.of("definition", before),
                    Map.of("definition", after));
            saved.add(after);
        }
        return saved;
    }

    /**
     * Sonarr's quality order: the weight of a quality.
     *
     * @param quality Sonarr's quality id
     * @return its weight, or {@link Integer#MAX_VALUE} for an unknown id
     */
    public static int weight(int quality) {
        return Qualities.byId(quality).map(Qualities.Default::weight).orElse(Integer.MAX_VALUE);
    }

    // ── Custom formats ────────────────────────────────────────────────────────

    /**
     * Returns every custom format.
     *
     * @return the formats by id
     */
    public List<CustomFormat> formats() {
        return mongo.find(new Query().with(Sort.by("id")), CustomFormat.class);
    }

    /**
     * Returns a custom format.
     *
     * @param id the id
     * @return the format
     */
    public CustomFormat format(int id) {
        CustomFormat f = mongo.findById(id, CustomFormat.class);
        if (f == null) {
            throw new NotFoundException("Custom format " + id);
        }
        return f;
    }

    /**
     * Adds a custom format, with score 0 in every profile.
     *
     * @param format the format; its id is ignored
     * @param actor who did it
     * @return the format with its id
     */
    public CustomFormat addFormat(CustomFormat format, String actor) {
        validateFormat(format, 0);
        CustomFormat saved = new CustomFormat(sequences.next(FORMAT), format.name(),
                format.includeCustomFormatWhenRenaming(), format.specifications());
        mongo.insert(saved);
        for (QualityProfile p : profiles()) {
            List<FormatItem> items = new ArrayList<>(p.formatItems() == null ? List.of() : p.formatItems());
            items.add(new FormatItem(saved.id(), 0));
            mongo.save(withFormats(p, items));
        }
        events.change("customformat", "added", saved.name(), actor, null, Map.of("format", saved));
        return saved;
    }

    /**
     * Replaces a custom format.
     *
     * @param id the id
     * @param format the new format
     * @param actor who did it
     * @return the format
     */
    public CustomFormat updateFormat(int id, CustomFormat format, String actor) {
        CustomFormat before = format(id);
        validateFormat(format, id);
        CustomFormat saved = new CustomFormat(id, format.name(), format.includeCustomFormatWhenRenaming(),
                format.specifications());
        mongo.save(saved);
        events.change("customformat", "updated", saved.name(), actor, Map.of("format", before),
                Map.of("format", saved));
        return saved;
    }

    /**
     * Deletes a custom format and its scores.
     *
     * @param id the id
     * @param actor who did it
     */
    public void deleteFormat(int id, String actor) {
        CustomFormat before = format(id);
        mongo.remove(Query.query(Criteria.where("id").is(id)), CustomFormat.class);
        for (QualityProfile p : profiles()) {
            if (p.formatItems() != null && p.formatItems().stream().anyMatch(f -> f.format() == id)) {
                mongo.save(withFormats(p, p.formatItems().stream().filter(f -> f.format() != id).toList()));
            }
        }
        events.change("customformat", "deleted", before.name(), actor, Map.of("format", before), null);
    }

    // ── Shared ────────────────────────────────────────────────────────────────

    /**
     * Describes a profile for an event.
     *
     * @param p the profile
     * @return its values
     */
    public static Map<String, Object> describe(QualityProfile p) {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("id", p.id());
        d.put("name", p.name());
        d.put("upgradeAllowed", p.upgradeAllowed());
        d.put("cutoff", p.cutoff());
        d.put("allowed", allowedQualities(p.items()).stream().map(String::valueOf).collect(Collectors.joining(",")));
        d.put("minFormatScore", p.minFormatScore());
        d.put("cutoffFormatScore", p.cutoffFormatScore());
        return d;
    }

    private void validate(QualityProfile p, int id) {
        if (p.name() == null || p.name().isBlank()) {
            throw new BadRequestException("name", "'Name' must not be empty.");
        }
        Query sameName = Query.query(Criteria.where("name").is(p.name()).and("id").ne(id));
        if (mongo.exists(sameName, QualityProfile.class)) {
            throw new BadRequestException("name", "Must be unique");
        }
        if (p.items() == null || p.items().isEmpty()) {
            throw new BadRequestException("items", "Must contain all qualities");
        }
        Set<Integer> allowed = new HashSet<>(allowedQualities(p.items()));
        p.items().stream().filter(i -> i.allowed() && i.id() != null && i.quality() == null)
                .forEach(g -> allowed.add(g.id()));
        if (allowed.isEmpty()) {
            throw new BadRequestException("items", "Must contain at least one allowed quality");
        }
        if (!allowed.contains(p.cutoff())) {
            throw new BadRequestException("cutoff", "Cutoff must be an allowed quality or group");
        }
    }

    private void validateFormat(CustomFormat f, int id) {
        if (f.name() == null || f.name().isBlank()) {
            throw new BadRequestException("name", "'Name' must not be empty.");
        }
        Query sameName = Query.query(Criteria.where("name").is(f.name()).and("id").ne(id));
        if (mongo.exists(sameName, CustomFormat.class)) {
            throw new BadRequestException("name", "Must be unique");
        }
    }

    private static List<Integer> allowedQualities(List<ProfileItem> items) {
        List<Integer> allowed = new ArrayList<>();
        if (items == null) {
            return allowed;
        }
        for (ProfileItem item : items) {
            if (item.quality() != null && item.allowed()) {
                allowed.add(item.quality());
            }
            if (item.items() != null && item.allowed()) {
                item.items().stream().filter(i -> i.quality() != null).forEach(i -> allowed.add(i.quality()));
            }
        }
        return allowed;
    }

    private static String subject(QualityProfile p) {
        return p.name() + " (" + p.id() + ")";
    }

    private static QualityProfile withId(QualityProfile p, int id) {
        return new QualityProfile(id, p.name(), p.upgradeAllowed(), p.cutoff(), p.items(), p.minFormatScore(),
                p.cutoffFormatScore(), p.minUpgradeFormatScore(),
                p.formatItems() == null ? List.of() : p.formatItems());
    }

    private static QualityProfile withFormats(QualityProfile p, List<FormatItem> items) {
        return new QualityProfile(p.id(), p.name(), p.upgradeAllowed(), p.cutoff(), p.items(), p.minFormatScore(),
                p.cutoffFormatScore(), p.minUpgradeFormatScore(), items);
    }

    /**
     * Sonarr's {@code GetDefaultProfile}: the qualities grouped by weight (groups get ids from 1000), the given ones
     * allowed; the cutoff becomes its group's id when it is in a group.
     */
    private static QualityProfile defaultProfile(String name, int cutoff, int... allowed) {
        Set<Integer> allowedSet = Arrays.stream(allowed).boxed().collect(Collectors.toSet());
        Map<Integer, List<Qualities.Default>> byWeight = new LinkedHashMap<>();
        Qualities.DEFAULTS.forEach(d -> byWeight.computeIfAbsent(d.weight(), w -> new ArrayList<>()).add(d));
        List<ProfileItem> items = new ArrayList<>();
        int groupId = FIRST_GROUP_ID;
        int profileCutoff = cutoff < 0 ? 0 : cutoff;
        for (List<Qualities.Default> group : byWeight.values()) {
            if (group.size() == 1) {
                int q = group.get(0).quality().id();
                items.add(new ProfileItem(null, null, q, List.of(), allowedSet.contains(q)));
                continue;
            }
            boolean groupAllowed = group.stream().anyMatch(d -> allowedSet.contains(d.quality().id()));
            List<ProfileItem> members = group.stream()
                    .map(d -> new ProfileItem(null, null, d.quality().id(), List.of(), groupAllowed)).toList();
            items.add(new ProfileItem(groupId, group.get(0).groupName(), null, members, groupAllowed));
            final int cut = profileCutoff;
            if (group.stream().anyMatch(d -> d.quality().id() == cut)) {
                profileCutoff = groupId;
            }
            groupId++;
        }
        return new QualityProfile(0, name, false, profileCutoff, items, 0, 0, 1, List.of());
    }
}
