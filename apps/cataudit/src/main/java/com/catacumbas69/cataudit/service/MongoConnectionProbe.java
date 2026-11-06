package com.catacumbas69.cataudit.service;

import com.catacumbas69.catacommons.consumers.ConnectionProbe;
import com.catacumbas69.catacommons.consumers.ProbeResult;
import org.bson.Document;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Component;

/**
 * Checks MongoDB with a {@code ping}, for the audit consumer's automatic resume and for <em>Check connections</em>
 * (orch.md decisions 66, 67).
 */
@Component
public class MongoConnectionProbe implements ConnectionProbe {

    private final MongoTemplate mongoTemplate;

    /**
     * Creates the probe.
     *
     * @param mongoTemplate the app's template
     */
    public MongoConnectionProbe(MongoTemplate mongoTemplate) {
        this.mongoTemplate = mongoTemplate;
    }

    @Override
    public String name() {
        return MONGODB;
    }

    @Override
    public ProbeResult probe() {
        long start = System.nanoTime();
        try {
            mongoTemplate.executeCommand(new Document("ping", 1));
            return ProbeResult.up((System.nanoTime() - start) / 1_000_000);
        } catch (RuntimeException e) {
            return ProbeResult.down((System.nanoTime() - start) / 1_000_000, e.getMessage());
        }
    }
}
