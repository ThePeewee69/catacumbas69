package com.catacumbas69.catasonarr.service;

import com.catacumbas69.catasonarr.model.Series;
import com.catacumbas69.catasonarr.model.Tag;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;

/**
 * Tags, in lower case as Sonarr keeps them. Adding a label that exists returns the existing tag, as Sonarr does, so
 * Overseerr can add its tags again without failing.
 */
@Service
public class TagService {

    /** Sequence name. */
    public static final String SEQUENCE = "tag";

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
    public TagService(MongoTemplate mongo, Sequences sequences, CatasonarrEvents events) {
        this.mongo = mongo;
        this.sequences = sequences;
        this.events = events;
    }

    /**
     * Returns every tag.
     *
     * @return the tags by id
     */
    public List<Tag> all() {
        return mongo.find(new Query().with(Sort.by("id")), Tag.class);
    }

    /**
     * Returns a tag.
     *
     * @param id the id
     * @return the tag
     */
    public Tag get(int id) {
        Tag tag = mongo.findById(id, Tag.class);
        if (tag == null) {
            throw new NotFoundException("Tag " + id);
        }
        return tag;
    }

    /**
     * Adds a tag, or returns the one with that label.
     *
     * @param label the label
     * @param actor who did it
     * @return the tag
     */
    public Tag add(String label, String actor) {
        String l = clean(label);
        Tag existing = mongo.findOne(Query.query(Criteria.where("label").is(l)), Tag.class);
        if (existing != null) {
            return existing;
        }
        Tag tag = new Tag(sequences.next(SEQUENCE), l);
        mongo.insert(tag);
        events.change("tag", "added", l, actor, null, Map.of("id", tag.id(), "label", l));
        return tag;
    }

    /**
     * Renames a tag.
     *
     * @param id the id
     * @param label the new label
     * @param actor who did it
     * @return the tag
     */
    public Tag update(int id, String label, String actor) {
        Tag before = get(id);
        String l = clean(label);
        if (mongo.exists(Query.query(Criteria.where("label").is(l).and("id").ne(id)), Tag.class)) {
            throw new BadRequestException("label", "Must be unique");
        }
        Tag after = new Tag(id, l);
        mongo.save(after);
        events.change("tag", "updated", l, actor, Map.of("label", before.label()), Map.of("label", l));
        return after;
    }

    /**
     * Deletes a tag no series uses, as Sonarr.
     *
     * @param id the id
     * @param actor who did it
     */
    public void delete(int id, String actor) {
        Tag tag = get(id);
        if (mongo.exists(Query.query(Criteria.where("tags").is(id)), Series.class)) {
            throw new BadRequestException(null, "Unable to delete tag that is in use");
        }
        mongo.remove(Query.query(Criteria.where("id").is(id)), Tag.class);
        events.change("tag", "deleted", tag.label(), actor, Map.of("id", id, "label", tag.label()), null);
    }

    private static String clean(String label) {
        if (label == null || label.isBlank()) {
            throw new BadRequestException("label", "'Label' must not be empty.");
        }
        return label.strip().toLowerCase(Locale.ROOT);
    }
}
