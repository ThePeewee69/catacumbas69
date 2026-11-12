package com.catacumbas69.catacommons.test;

import com.catacumbas69.catacommons.pulsar.CatacumbasTopics;
import com.catacumbas69.catacommons.settings.SettingValue;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoDatabase;
import java.time.Duration;
import java.util.HashSet;
import org.apache.pulsar.client.admin.PulsarAdmin;
import org.apache.pulsar.client.admin.PulsarAdminException;
import org.apache.pulsar.client.api.Producer;
import org.apache.pulsar.client.api.PulsarClient;
import org.apache.pulsar.client.api.PulsarClientException;
import org.apache.pulsar.client.api.Schema;
import org.apache.pulsar.common.policies.data.TenantInfo;
import org.bson.Document;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PulsarContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.lifecycle.Startables;
import org.testcontainers.utility.DockerImageName;

/**
 * The real Pulsar and MongoDB the integration tests run against (orch.md TEST-3, decision 91): one of each per
 * test JVM, so per module, started on first use and shared by every test class. Each test uses topics and
 * names of its own, so tests do not see each other's data. They are removed when the JVM ends.
 *
 * <p>The images are those of the cluster on meloes: Pulsar 4.2.2 standalone and MongoDB 7.0 (a single-member
 * replica set, so change streams and transactions work). The replica set is initiated through the MongoDB driver,
 * not with {@code docker exec} as Testcontainers' MongoDBContainer does: exec fails through Podman's API on the
 * Mac ({@code NoHttpResponseException}).
 *
 * <pre>
 * &#64;DynamicPropertySource
 * static void containers(DynamicPropertyRegistry registry) {
 *     CatacumbasContainers.register(registry, "cataponte");
 * }
 * </pre>
 */
public final class CatacumbasContainers {

    /** Same as the broker on meloes. */
    public static final DockerImageName PULSAR_IMAGE = DockerImageName.parse("apachepulsar/pulsar:4.2.2");

    /** Same major and minor as the MongoDBCommunity on meloes. */
    public static final DockerImageName MONGO_IMAGE = DockerImageName.parse("mongo:7.0");

    private static final PulsarContainer PULSAR = new PulsarContainer(PULSAR_IMAGE)
            .withStartupTimeout(Duration.ofMinutes(3));

    private static final int MONGO_PORT = 27017;

    private static final GenericContainer<?> MONGO = new GenericContainer<>(MONGO_IMAGE)
            .withExposedPorts(MONGO_PORT)
            .withCommand("--replSet", "rs0", "--bind_ip_all")
            .waitingFor(Wait.forLogMessage(".*Waiting for connections.*", 1))
            .withStartupTimeout(Duration.ofMinutes(2));

    static {
        Startables.deepStart(PULSAR, MONGO).join();
        initiateReplicaSet();
        // Ryuk removes them when the JVM ends; this hook is a safety net for when Ryuk is not running
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            PULSAR.stop();
            MONGO.stop();
        }, "catacumbas-containers-stop"));
    }

    private CatacumbasContainers() {
    }

    /**
     * Points an app at the containers: its {@code <app>.pulsar.*} URLs, {@code spring.data.mongodb.uri} with a
     * database of its own ({@code it_<app>}), and an unreachable Cataponte for the audit bridge registration,
     * which only retries in the background.
     *
     * @param registry the test's dynamic properties
     * @param app the app's property prefix, e.g. {@code cataponte}
     */
    public static void register(DynamicPropertyRegistry registry, String app) {
        registry.add(app + ".pulsar.broker-url", PULSAR::getPulsarBrokerUrl);
        registry.add(app + ".pulsar.service-url", PULSAR::getHttpServiceUrl);
        registry.add(app + ".pulsar.listener-name", () -> "");
        registry.add("spring.data.mongodb.uri", () -> mongoUri("it_" + app));
        registry.add("catacommons.audit-bridge.cataponte-url", () -> "http://localhost:1");
    }

    /**
     * A Pulsar client of the test's own, to produce and consume around the app. Close it after use.
     *
     * @return a new client
     * @throws PulsarClientException if it cannot be built
     */
    public static PulsarClient pulsarClient() throws PulsarClientException {
        return PulsarClient.builder().serviceUrl(PULSAR.getPulsarBrokerUrl()).build();
    }

    /**
     * A Pulsar admin client of the test's own. Close it after use.
     *
     * @return a new admin client
     * @throws PulsarClientException if it cannot be built
     */
    public static PulsarAdmin pulsarAdmin() throws PulsarClientException {
        return PulsarAdmin.builder().serviceHttpUrl(PULSAR.getHttpServiceUrl()).build();
    }

    /**
     * Writes a shared setting on the settings topic (ADR 0011) as Catapulsar does, creating the tenant and
     * Catapulsar's namespace first if needed. Call it before the app starts (in {@code @BeforeAll}) so the app
     * reads it at start-up, e.g. to shorten Catavigia's rounds.
     *
     * @param key setting key, e.g. {@code alerts.check-interval}
     * @param value its value
     * @throws PulsarClientException if Pulsar cannot be reached
     * @throws PulsarAdminException if the tenant or the namespace cannot be created
     */
    public static void setting(String key, String value) throws PulsarClientException, PulsarAdminException {
        try (PulsarAdmin admin = pulsarAdmin(); PulsarClient client = pulsarClient()) {
            try {
                admin.tenants().createTenant(CatacumbasTopics.TENANT, TenantInfo.builder()
                        .allowedClusters(new HashSet<>(admin.clusters().getClusters())).build());
            } catch (PulsarAdminException.ConflictException alreadyThere) {
                // created by an earlier call or by the app
            }
            try {
                admin.namespaces().createNamespace(CatacumbasTopics.namespace(CatacumbasTopics.SETTINGS_OWNER));
            } catch (PulsarAdminException.ConflictException alreadyThere) {
                // created by an earlier call
            }
            try (Producer<SettingValue> producer = client.newProducer(Schema.JSON(SettingValue.class))
                    .topic(CatacumbasTopics.settings())
                    .create()) {
                producer.newMessage().key(key).value(new SettingValue(value)).send();
            }
        }
    }

    /**
     * The MongoDB connection string of a database, to inspect what the app stored.
     *
     * @param database database name
     * @return the URI
     */
    public static String mongoUri(String database) {
        // Direct to the one member: it is the primary, and its name inside the replica set is not reachable here
        return "mongodb://" + MONGO.getHost() + ":" + MONGO.getMappedPort(MONGO_PORT) + "/" + database
                + "?directConnection=true";
    }

    /** Makes the single member a replica set and waits until it is the primary. */
    private static void initiateReplicaSet() {
        try (MongoClient client = MongoClients.create(mongoUri("admin"))) {
            MongoDatabase admin = client.getDatabase("admin");
            admin.runCommand(new Document("replSetInitiate", new Document()));
            long deadline = System.nanoTime() + Duration.ofSeconds(60).toNanos();
            while (!Boolean.TRUE.equals(admin.runCommand(new Document("hello", 1)).getBoolean("isWritablePrimary"))) {
                if (System.nanoTime() > deadline) {
                    throw new IllegalStateException("MongoDB did not become the primary of its replica set in 60 s");
                }
                try {
                    Thread.sleep(200);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("Interrupted while waiting for the MongoDB primary", e);
                }
            }
        }
    }
}
