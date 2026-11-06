package com.catacumbas69.catacommons;

import com.catacumbas69.catacommons.audit.AuditBridgeRegistrar;
import com.catacumbas69.catacommons.audit.CataponteLocation;
import com.catacumbas69.catacommons.audit.HttpSystemBridgeRegistry;
import com.catacumbas69.catacommons.audit.SystemBridgeRegistry;
import com.catacumbas69.catacommons.consumers.ConnectionProbe;
import com.catacumbas69.catacommons.consumers.ConsumerCommand;
import com.catacumbas69.catacommons.consumers.ConsumerControl;
import com.catacumbas69.catacommons.consumers.ConsumerStatus;
import com.catacumbas69.catacommons.consumers.DeadLetterWriter;
import com.catacumbas69.catacommons.consumers.InstanceId;
import com.catacumbas69.catacommons.consumers.PulsarAdminProbe;
import com.catacumbas69.catacommons.events.CloudEvent;
import com.catacumbas69.catacommons.events.EventPublisher;
import com.catacumbas69.catacommons.pulsar.CatacumbasTopics;
import com.catacumbas69.catacommons.pulsar.PulsarProvisioner;
import com.catacumbas69.catacommons.pulsar.RetryingSender;
import com.catacumbas69.catacommons.pulsar.RetryingSenderFactory;
import com.catacumbas69.catacommons.pulsar.TopicSpec;
import com.catacumbas69.catacommons.settings.SharedSettings;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import org.apache.pulsar.client.admin.PulsarAdmin;
import org.apache.pulsar.client.api.PulsarClient;
import org.apache.pulsar.client.api.Schema;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * Wires catacommons into an app that has it on the classpath (ADR 0008). The app must provide a
 * {@link PulsarClient}, a {@link PulsarAdmin} and {@code catacommons.app}; it gets:
 *
 * <ul>
 *   <li>{@link EventPublisher} for its events topic;</li>
 *   <li>{@link RetryingSenderFactory} for any other topic it writes to;</li>
 *   <li>{@link PulsarProvisioner}, which creates the tenant, its namespace and every {@link TopicSpec} bean
 *       (the events topic is always one) once the app has started;</li>
 *   <li>{@link AuditBridgeRegistrar}, which registers the app's {@code audit-<app>} bridge in Cataponte
 *       (ADR 0009) through a {@link SystemBridgeRegistry}; an app can provide its own registry (Cataponte
 *       does, in-process) or its own {@link CataponteLocation} (Catapulsar does);</li>
 *   <li>{@link SharedSettings}, the shared settings read from the compacted settings topic (ADR 0011);</li>
 *   <li>with {@code catacommons.consumers.enabled=true}, {@link ConsumerControl} for the app's own consumers, its
 *       status and commands topics, a {@link PulsarAdminProbe} and a {@link DeadLetterWriter} (ADR 0015).</li>
 * </ul>
 */
@AutoConfiguration
@EnableConfigurationProperties(CatacommonsProperties.class)
public class CatacommonsAutoConfiguration {

    /**
     * Background thread for retries and provisioning.
     *
     * @return the scheduler, stopped on shutdown
     */
    @Bean(destroyMethod = "close")
    public CatacommonsScheduler catacommonsScheduler() {
        return new CatacommonsScheduler();
    }

    /**
     * Creates senders with the configured retries.
     *
     * @param client the app's Pulsar client
     * @param properties catacommons configuration
     * @param scheduler retry scheduler
     * @param objectMapper the app's mapper, if any
     * @param meterRegistry the app's metrics registry, if any
     * @return the factory
     */
    @Bean
    public RetryingSenderFactory catacommonsSenderFactory(PulsarClient client, CatacommonsProperties properties,
                                                          CatacommonsScheduler scheduler,
                                                          ObjectProvider<ObjectMapper> objectMapper,
                                                          ObjectProvider<MeterRegistry> meterRegistry) {
        return new RetryingSenderFactory(client, properties, scheduler.executor(),
                objectMapper.getIfAvailable(ObjectMapper::new), meterRegistry.getIfAvailable(SimpleMeterRegistry::new));
    }

    /**
     * Declares the app's events topic, so it is provisioned with the CloudEvent schema.
     *
     * @return the topic spec
     */
    @Bean
    public TopicSpec catacommonsEventsTopic() {
        return TopicSpec.of(CatacumbasTopics.EVENTS, Schema.JSON(CloudEvent.class));
    }

    /**
     * Sends to the app's events topic.
     *
     * @param factory sender factory
     * @param properties catacommons configuration
     * @return the sender, closed on shutdown
     */
    @Bean(destroyMethod = "close")
    @ConditionalOnProperty(prefix = "catacommons.events", name = "enabled", havingValue = "true",
            matchIfMissing = true)
    public RetryingSender<CloudEvent> catacommonsEventSender(RetryingSenderFactory factory,
                                                             CatacommonsProperties properties) {
        return factory.create(Schema.JSON(CloudEvent.class), CatacumbasTopics.events(properties.app()));
    }

    /**
     * Publishes the app's events; drops them when {@code catacommons.events.enabled=false}.
     *
     * @param properties catacommons configuration
     * @param sender event sender, absent when publishing is off
     * @param clock the app's clock, if any
     * @return the publisher
     */
    @Bean
    public EventPublisher eventPublisher(CatacommonsProperties properties,
                                         ObjectProvider<RetryingSender<CloudEvent>> sender,
                                         ObjectProvider<Clock> clock) {
        return new EventPublisher(properties.app(), sender.getIfAvailable(),
                clock.getIfAvailable(Clock::systemUTC));
    }

    /**
     * Creates the tenant, namespace and topics once the app has started.
     *
     * @param admin the app's Pulsar admin client
     * @param properties catacommons configuration
     * @param topics every declared topic
     * @param scheduler runs the attempts
     * @return the provisioner
     */
    @Bean
    @ConditionalOnProperty(prefix = "catacommons.provisioning", name = "enabled", havingValue = "true",
            matchIfMissing = true)
    public PulsarProvisioner pulsarProvisioner(PulsarAdmin admin, CatacommonsProperties properties,
                                               ObjectProvider<TopicSpec> topics, CatacommonsScheduler scheduler) {
        return new PulsarProvisioner(admin, properties, topics.orderedStream().toList(), scheduler.executor());
    }

    /**
     * Cataponte's URL from {@code catacommons.audit-bridge.cataponte-url}, unless the app provides one.
     *
     * @param properties catacommons configuration
     * @return the location
     */
    @Bean
    @ConditionalOnMissingBean
    public CataponteLocation cataponteLocation(CatacommonsProperties properties) {
        String url = properties.auditBridge().cataponteUrl().strip();
        String base = url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
        return () -> base;
    }

    /**
     * Registers system bridges through Cataponte's API, unless the app provides another registry.
     *
     * @param location Cataponte's URL
     * @param properties catacommons configuration
     * @return the registry
     */
    @Bean
    @ConditionalOnMissingBean
    public SystemBridgeRegistry systemBridgeRegistry(CataponteLocation location, CatacommonsProperties properties) {
        return new HttpSystemBridgeRegistry(location, properties.app(), properties.auditBridge().timeout());
    }

    /**
     * Registers the app's audit bridge once the app has started.
     *
     * @param registry where bridges are registered
     * @param properties catacommons configuration
     * @param scheduler runs the attempts
     * @return the registrar
     */
    @Bean
    @ConditionalOnProperty(prefix = "catacommons.audit-bridge", name = "enabled", havingValue = "true",
            matchIfMissing = true)
    public AuditBridgeRegistrar auditBridgeRegistrar(SystemBridgeRegistry registry, CatacommonsProperties properties,
                                                     CatacommonsScheduler scheduler) {
        CatacommonsProperties.AuditBridge settings = properties.auditBridge();
        return new AuditBridgeRegistrar(registry, properties.app(), scheduler.executor(), settings.retryInterval(),
                settings.maxRetryInterval());
    }

    /**
     * Reads the shared settings topic, unless {@code catacommons.settings.enabled=false}.
     *
     * @param client the app's Pulsar client
     * @param properties catacommons configuration
     * @param scheduler runs retries
     * @return the reader, closed on shutdown
     */
    @Bean(destroyMethod = "close")
    @ConditionalOnProperty(prefix = "catacommons.settings", name = "enabled", havingValue = "true",
            matchIfMissing = true)
    public SharedSettings sharedSettings(PulsarClient client, CatacommonsProperties properties,
                                         CatacommonsScheduler scheduler) {
        return new SharedSettings(client, scheduler.executor(), properties.settings().retryInterval());
    }

    /**
     * This replica's identity.
     *
     * @param properties catacommons configuration
     * @return the instance id
     */
    @Bean
    @ConditionalOnMissingBean
    public InstanceId catacommonsInstanceId(CatacommonsProperties properties) {
        return new InstanceId(properties.app());
    }

    /**
     * Declares the compacted consumer status topic.
     *
     * @return the topic spec
     */
    @Bean
    @ConditionalOnProperty(prefix = "catacommons.consumers", name = "enabled", havingValue = "true")
    public TopicSpec catacommonsConsumersTopic() {
        return TopicSpec.compacted(CatacumbasTopics.CONSUMERS, Schema.JSON(ConsumerStatus.class));
    }

    /**
     * Declares the consumer commands topic.
     *
     * @return the topic spec
     */
    @Bean
    @ConditionalOnProperty(prefix = "catacommons.consumers", name = "enabled", havingValue = "true")
    public TopicSpec catacommonsConsumerCommandsTopic() {
        return TopicSpec.of(CatacumbasTopics.CONSUMER_COMMANDS, Schema.JSON(ConsumerCommand.class));
    }

    /**
     * Checks Pulsar's admin API for the consumers.
     *
     * @param admin the app's admin client
     * @return the probe
     */
    @Bean
    @ConditionalOnProperty(prefix = "catacommons.consumers", name = "enabled", havingValue = "true")
    public PulsarAdminProbe pulsarAdminProbe(PulsarAdmin admin) {
        return new PulsarAdminProbe(admin);
    }

    /**
     * Sends messages straight to their DLQ.
     *
     * @param client the app's Pulsar client
     * @param admin creates the DLQs
     * @return the writer, closed on shutdown
     */
    @Bean(destroyMethod = "close")
    @ConditionalOnProperty(prefix = "catacommons.consumers", name = "enabled", havingValue = "true")
    public DeadLetterWriter deadLetterWriter(PulsarClient client, PulsarAdmin admin) {
        return new DeadLetterWriter(client, admin);
    }

    /**
     * Controls the app's own consumers.
     *
     * @param client the app's Pulsar client
     * @param properties catacommons configuration
     * @param instance this replica
     * @param probes the connection checks (MongoDB from the app, Pulsar from here)
     * @param events publishes the changes
     * @param scheduler runs the periodic work
     * @param clock the app's clock, if any
     * @return the control, closed on shutdown
     */
    @Bean(destroyMethod = "close")
    @ConditionalOnProperty(prefix = "catacommons.consumers", name = "enabled", havingValue = "true")
    public ConsumerControl consumerControl(PulsarClient client, CatacommonsProperties properties, InstanceId instance,
                                           ObjectProvider<ConnectionProbe> probes, EventPublisher events,
                                           CatacommonsScheduler scheduler, ObjectProvider<Clock> clock) {
        return new ConsumerControl(client, properties.app(), instance, probes.orderedStream().toList(), events,
                scheduler.executor(), properties.consumers(), clock.getIfAvailable(Clock::systemUTC));
    }
}
