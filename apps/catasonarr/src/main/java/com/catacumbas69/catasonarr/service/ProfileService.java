package com.catacumbas69.catasonarr.service;

import com.catacumbas69.catasonarr.model.DelayProfile;
import com.catacumbas69.catasonarr.model.ReleaseProfile;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;

/**
 * Release profiles and delay profiles, as Sonarr's (orch.md decision 122). The delay profile without tags (id 1) is
 * always there and always last.
 */
@Service
public class ProfileService {

    /** Sequence of release profiles. */
    public static final String RELEASE = "releaseprofile";
    /** Sequence of delay profiles. */
    public static final String DELAY = "delayprofile";

    /** Sonarr's default delay profile: torrents, no delay, every series. */
    public static final DelayProfile DEFAULT_DELAY = new DelayProfile(1, false, true, "torrent", 0, 0,
            Integer.MAX_VALUE, false, false, 0, Set.of());

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
    public ProfileService(MongoTemplate mongo, Sequences sequences, CatasonarrEvents events) {
        this.mongo = mongo;
        this.sequences = sequences;
        this.events = events;
    }

    /**
     * Every release profile.
     *
     * @return the profiles by id
     */
    public List<ReleaseProfile> releaseProfiles() {
        return mongo.find(new Query().with(Sort.by("id")), ReleaseProfile.class);
    }

    /**
     * A release profile.
     *
     * @param id the id
     * @return the profile
     */
    public ReleaseProfile releaseProfile(int id) {
        ReleaseProfile p = mongo.findById(id, ReleaseProfile.class);
        if (p == null) {
            throw new NotFoundException("Release profile " + id);
        }
        return p;
    }

    /**
     * The enabled release profiles of a series' tags and an indexer, as Sonarr's {@code EnabledForTags}: those
     * without tags or with one of the series', for every indexer or this one.
     *
     * @param tags the series' tags
     * @param indexerId the release's indexer
     * @return the profiles
     */
    public List<ReleaseProfile> enabledForTags(Set<Integer> tags, int indexerId) {
        return releaseProfiles().stream().filter(ReleaseProfile::enabled)
                .filter(r -> r.tags() == null || r.tags().isEmpty() || r.tags().stream().anyMatch(tags::contains))
                .filter(r -> r.indexerId() == 0 || r.indexerId() == indexerId).toList();
    }

    /**
     * Adds or replaces a release profile; it needs a required or an ignored term.
     *
     * @param id the id, or 0 to add
     * @param p the profile
     * @param actor who did it
     * @return the profile saved
     */
    public ReleaseProfile saveReleaseProfile(int id, ReleaseProfile p, String actor) {
        List<String> required = p.required() == null ? List.of()
                : p.required().stream().filter(t -> !t.isBlank()).toList();
        List<String> ignored = p.ignored() == null ? List.of()
                : p.ignored().stream().filter(t -> !t.isBlank()).toList();
        if (required.isEmpty() && ignored.isEmpty()) {
            throw new BadRequestException("required", "'Must contain' and 'Must not contain' cannot both be empty");
        }
        ReleaseProfile before = id == 0 ? null : releaseProfile(id);
        ReleaseProfile saved = new ReleaseProfile(id == 0 ? sequences.next(RELEASE) : id, p.name(), p.enabled(),
                required, ignored, p.indexerId(), p.tags() == null ? Set.of() : p.tags());
        mongo.save(saved);
        events.change("releaseprofile", id == 0 ? "added" : "updated", name(saved), actor,
                before == null ? null : Map.of("profile", before), Map.of("profile", saved));
        return saved;
    }

    /**
     * Deletes a release profile.
     *
     * @param id the id
     * @param actor who did it
     */
    public void deleteReleaseProfile(int id, String actor) {
        ReleaseProfile before = releaseProfile(id);
        mongo.remove(Query.query(Criteria.where("id").is(id)), ReleaseProfile.class);
        events.change("releaseprofile", "deleted", name(before), actor, Map.of("profile", before), null);
    }

    /**
     * Every delay profile, in order; the default one last.
     *
     * @return the profiles
     */
    public List<DelayProfile> delayProfiles() {
        List<DelayProfile> all = new java.util.ArrayList<>(mongo.findAll(DelayProfile.class));
        if (all.stream().noneMatch(p -> p.id() == 1)) {
            all.add(DEFAULT_DELAY);
        }
        all.sort(Comparator.comparingInt(DelayProfile::order));
        return all;
    }

    /**
     * A delay profile.
     *
     * @param id the id
     * @return the profile
     */
    public DelayProfile delayProfile(int id) {
        return delayProfiles().stream().filter(p -> p.id() == id).findFirst()
                .orElseThrow(() -> new NotFoundException("Delay profile " + id));
    }

    /**
     * The delay profile of a series, as Sonarr's {@code BestForTags}: the first, in order, with one of its tags,
     * else the default one.
     *
     * @param tags the series' tags
     * @return the profile
     */
    public DelayProfile bestForTags(Set<Integer> tags) {
        return delayProfiles().stream()
                .filter(p -> p.id() == 1 || p.tags() != null && p.tags().stream().anyMatch(tags::contains))
                .findFirst().orElse(DEFAULT_DELAY);
    }

    /**
     * Adds or replaces a delay profile. Only torrents: usenet is off. The default one keeps no tags and stays last.
     *
     * @param id the id, or 0 to add
     * @param p the profile
     * @param actor who did it
     * @return the profile saved
     */
    public DelayProfile saveDelayProfile(int id, DelayProfile p, String actor) {
        if (id != 1 && (p.tags() == null || p.tags().isEmpty())) {
            throw new BadRequestException("tags", "Only the default delay profile may have no tags");
        }
        if (p.torrentDelay() < 0) {
            throw new BadRequestException("torrentDelay", "The delay cannot be negative");
        }
        DelayProfile before = id == 0 ? null : delayProfile(id);
        if (id == 0) {
            sequences.atLeast(DELAY, 1);
        }
        int newId = id == 0 ? sequences.next(DELAY) : id;
        int order = id == 1 ? Integer.MAX_VALUE : before != null ? before.order()
                : delayProfiles().stream().filter(d -> d.id() != 1).mapToInt(DelayProfile::order).max().orElse(0) + 1;
        DelayProfile saved = new DelayProfile(newId, false, true, "torrent", 0, p.torrentDelay(), order,
                p.bypassIfHighestQuality(), p.bypassIfAboveCustomFormatScore(), p.minimumCustomFormatScore(),
                id == 1 ? Set.of() : p.tags());
        mongo.save(saved);
        events.change("delayprofile", id == 0 ? "added" : "updated", "delay profile " + newId, actor,
                before == null ? null : Map.of("profile", before), Map.of("profile", saved));
        return saved;
    }

    /**
     * Deletes a delay profile; not the default one.
     *
     * @param id the id
     * @param actor who did it
     */
    public void deleteDelayProfile(int id, String actor) {
        if (id == 1) {
            throw new BadRequestException("id", "The default delay profile cannot be deleted");
        }
        DelayProfile before = delayProfile(id);
        mongo.remove(Query.query(Criteria.where("id").is(id)), DelayProfile.class);
        events.change("delayprofile", "deleted", "delay profile " + id, actor, Map.of("profile", before), null);
    }

    /**
     * Moves a delay profile before another, as Sonarr's reorder.
     *
     * @param id the profile to move
     * @param after the profile it goes after, or 0 for first
     * @param actor who did it
     * @return the profiles in their new order
     */
    public List<DelayProfile> reorderDelayProfile(int id, int after, String actor) {
        List<DelayProfile> ordered = new java.util.ArrayList<>(delayProfiles().stream().filter(p -> p.id() != 1)
                .toList());
        DelayProfile moving = ordered.stream().filter(p -> p.id() == id).findFirst()
                .orElseThrow(() -> new NotFoundException("Delay profile " + id));
        ordered.remove(moving);
        int at = 0;
        for (int i = 0; i < ordered.size(); i++) {
            if (ordered.get(i).id() == after) {
                at = i + 1;
            }
        }
        ordered.add(at, moving);
        for (int i = 0; i < ordered.size(); i++) {
            DelayProfile p = ordered.get(i);
            mongo.save(new DelayProfile(p.id(), false, true, "torrent", 0, p.torrentDelay(), i + 1,
                    p.bypassIfHighestQuality(), p.bypassIfAboveCustomFormatScore(), p.minimumCustomFormatScore(),
                    p.tags()));
        }
        events.action("delayprofile", "reordered", "delay profile " + id, actor, Map.of("after", after));
        return delayProfiles();
    }

    private static String name(ReleaseProfile p) {
        return (p.name() == null || p.name().isBlank() ? "release profile" : p.name()) + " (" + p.id() + ")";
    }
}
