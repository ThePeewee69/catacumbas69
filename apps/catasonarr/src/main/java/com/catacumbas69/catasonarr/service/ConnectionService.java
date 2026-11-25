package com.catacumbas69.catasonarr.service;

import com.catacumbas69.catasonarr.config.CatasonarrProperties;
import com.catacumbas69.catasonarr.dto.ConnectionsResponse;
import com.catacumbas69.catasonarr.dto.HealthCheckResponse;
import com.mongodb.ConnectionString;
import com.mongodb.client.MongoClient;
import java.time.Clock;
import java.time.Instant;
import org.apache.pulsar.client.admin.PulsarAdmin;
import org.bson.Document;
import org.springframework.boot.autoconfigure.mongo.MongoProperties;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * Where Catasonarr connects and whether it gets there (orch.md decision 119): Pulsar's addresses and MongoDB's
 * connection string without its password, each with a health check. Read-only: they change in the ConfigMap and
 * the Secret.
 */
@Service
public class ConnectionService {

    /** What stands for the password. */
    public static final String MASK = "****";

    private final CatasonarrProperties.Pulsar pulsar;
    private final CatasonarrProperties.Outside outside;
    private final PulsarAdmin admin;
    private final MongoProperties mongoProperties;
    private final MongoTemplate mongo;
    private final MongoClient mongoClient;
    private final Clock clock;

    /**
     * Creates the service.
     *
     * @param properties Pulsar's addresses
     * @param admin Pulsar's admin client
     * @param mongoProperties MongoDB's connection string and database
     * @param mongo the database Catasonarr uses
     * @param mongoClient the client, for the server's version
     * @param clock time source
     */
    public ConnectionService(CatasonarrProperties properties, PulsarAdmin admin, MongoProperties mongoProperties,
                             MongoTemplate mongo, MongoClient mongoClient, Clock clock) {
        this.pulsar = properties.pulsar();
        this.outside = properties.outside();
        this.admin = admin;
        this.mongoProperties = mongoProperties;
        this.mongo = mongo;
        this.mongoClient = mongoClient;
        this.clock = clock;
    }

    /**
     * Returns where Catasonarr connects.
     *
     * @return Pulsar's addresses and MongoDB's connection, without its password
     */
    public ConnectionsResponse connections() {
        return new ConnectionsResponse(
                new ConnectionsResponse.Pulsar(pulsar.serviceUrl(), pulsar.brokerUrl(), pulsar.listenerName(),
                        outside.pulsarServiceUrl(), outside.pulsarBrokerUrl()),
                describe(mongoProperties.getUri(), mongo.getDb().getName(), outside.mongodbHosts()));
    }

    /**
     * Checks Pulsar: its admin API answers with the broker's version.
     *
     * @return {@code UP} with the version, or {@code DOWN} with why
     */
    public HealthCheckResponse checkPulsar() {
        long start = System.nanoTime();
        try {
            String version = admin.brokers().getVersion();
            return up(start, version);
        } catch (Exception e) {
            return down(start, e);
        }
    }

    /**
     * Checks MongoDB: the database answers {@code ping}, and the server's version.
     *
     * @return {@code UP} with the version, or {@code DOWN} with why
     */
    public HealthCheckResponse checkMongo() {
        long start = System.nanoTime();
        try {
            mongo.getDb().runCommand(new Document("ping", 1));
            String version = mongoClient.getDatabase("admin").runCommand(new Document("buildInfo", 1))
                    .getString("version");
            return up(start, version);
        } catch (RuntimeException e) {
            return down(start, e);
        }
    }

    /**
     * Describes a MongoDB connection string without its password.
     *
     * @param uri the connection string
     * @param database the database used
     * @param outsideHosts the NodePort from outside the cluster, e.g. {@code meloes:32017}
     * @return what it says, with {@code ****} for the password
     */
    static ConnectionsResponse.Mongo describe(String uri, String database, String outsideHosts) {
        if (!StringUtils.hasText(uri)) {
            return new ConnectionsResponse.Mongo(null, null, database, null, null, null, false, null, null);
        }
        ConnectionString cs = new ConnectionString(uri);
        String authSource = cs.getCredential() == null ? null : cs.getCredential().getSource();
        boolean tls = Boolean.TRUE.equals(cs.getSslEnabled());
        return new ConnectionsResponse.Mongo(mask(uri), cs.getHosts(), database, cs.getUsername(), authSource,
                cs.getRequiredReplicaSetName(), tls, cs.isDirectConnection(),
                outsideUri(cs.getUsername(), outsideHosts, database, authSource, tls));
    }

    /**
     * The connection string from outside the cluster: the NodePort reaches one member, and the replica set's members
     * are named for inside the cluster, so it connects directly ({@code directConnection=true}).
     */
    static String outsideUri(String username, String hosts, String database, String authSource, boolean tls) {
        StringBuilder uri = new StringBuilder("mongodb://");
        if (StringUtils.hasText(username)) {
            uri.append(username).append(':').append(MASK).append('@');
        }
        uri.append(hosts).append('/').append(database == null ? "" : database).append("?directConnection=true");
        if (StringUtils.hasText(authSource)) {
            uri.append("&authSource=").append(authSource);
        }
        if (tls) {
            uri.append("&tls=true");
        }
        return uri.toString();
    }

    /**
     * Hides the password of a connection string: {@code mongodb://user:secret@host} becomes
     * {@code mongodb://user:****@host}.
     *
     * @param uri the connection string
     * @return the same, without the password
     */
    static String mask(String uri) {
        int scheme = uri.indexOf("://");
        int at = uri.indexOf('@', scheme + 3);
        int slash = uri.indexOf('/', scheme + 3);
        int query = uri.indexOf('?', scheme + 3);
        if (scheme < 0 || at < 0 || (slash >= 0 && slash < at) || (query >= 0 && query < at)) {
            return uri;
        }
        String userInfo = uri.substring(scheme + 3, at);
        int colon = userInfo.indexOf(':');
        return colon < 0 ? uri
                : uri.substring(0, scheme + 3) + userInfo.substring(0, colon + 1) + MASK + uri.substring(at);
    }

    private HealthCheckResponse up(long start, String version) {
        return new HealthCheckResponse("UP", elapsed(start), version, null, Instant.now(clock).toString());
    }

    private HealthCheckResponse down(long start, Exception e) {
        String why = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        return new HealthCheckResponse("DOWN", elapsed(start), null, why, Instant.now(clock).toString());
    }

    private static long elapsed(long start) {
        return (System.nanoTime() - start) / 1_000_000;
    }
}
