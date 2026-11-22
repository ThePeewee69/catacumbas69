package com.catacumbas69.catasonarr.service;

import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.FindOneAndUpdateOptions;
import com.mongodb.client.model.ReturnDocument;
import com.mongodb.client.model.UpdateOptions;
import com.mongodb.client.model.Updates;
import org.bson.Document;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Component;

/**
 * Sonarr-style integer ids (orch.md decision 112): one counter per kind in {@code catasonarr_sequences}, increased
 * atomically, so ids are never reused.
 */
@Component
public class Sequences {

    /** The collection of counters. */
    public static final String COLLECTION = "catasonarr_sequences";

    private final MongoTemplate mongo;

    /**
     * Creates the counters.
     *
     * @param mongo MongoDB
     */
    public Sequences(MongoTemplate mongo) {
        this.mongo = mongo;
    }

    /**
     * Returns the next id of a kind.
     *
     * @param kind e.g. {@code series}
     * @return 1 the first time, then one more each time
     */
    public int next(String kind) {
        Document counter = counters().findOneAndUpdate(Filters.eq("_id", kind), Updates.inc("seq", 1),
                new FindOneAndUpdateOptions().upsert(true).returnDocument(ReturnDocument.AFTER));
        return counter.getInteger("seq");
    }

    /**
     * Reserves a block of ids of a kind, for many new items at once (the episodes of a series).
     *
     * @param kind e.g. {@code episode}
     * @param count how many, at least 1
     * @return the first id of the block; the others follow it
     */
    public int reserve(String kind, int count) {
        Document counter = counters().findOneAndUpdate(Filters.eq("_id", kind), Updates.inc("seq", count),
                new FindOneAndUpdateOptions().upsert(true).returnDocument(ReturnDocument.AFTER));
        return counter.getInteger("seq") - count + 1;
    }

    /**
     * Makes sure the next id of a kind is above a value: after ids were imported from Sonarr.
     *
     * @param kind e.g. {@code qualityprofile}
     * @param value the highest id in use
     */
    public void atLeast(String kind, int value) {
        counters().updateOne(Filters.eq("_id", kind), Updates.max("seq", value), new UpdateOptions().upsert(true));
    }

    private MongoCollection<Document> counters() {
        return mongo.getCollection(COLLECTION);
    }
}
