package com.catacumbas69.catasonarr.service;

import com.catacumbas69.catasonarr.model.NamingConfig;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Service;

/**
 * How series and season folders are named (orch.md decision 112): imported from Sonarr's database, Sonarr's
 * defaults until then.
 */
@Service
public class NamingService {

    private final MongoTemplate mongo;

    /**
     * Creates the service.
     *
     * @param mongo MongoDB
     */
    public NamingService(MongoTemplate mongo) {
        this.mongo = mongo;
    }

    /**
     * Returns the naming in use.
     *
     * @return the imported naming, or Sonarr's defaults
     */
    public NamingConfig get() {
        NamingConfig naming = mongo.findById(NamingConfig.ID, NamingConfig.class);
        return naming == null ? NamingConfig.DEFAULT : naming;
    }

    /**
     * Saves the naming.
     *
     * @param naming the naming
     */
    public void save(NamingConfig naming) {
        mongo.save(naming);
    }
}
