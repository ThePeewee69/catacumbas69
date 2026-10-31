package com.catacumbas69.catacommons;

import com.catacumbas69.catacommons.events.CloudEvent;
import com.catacumbas69.catacommons.events.EventPublisher;
import com.catacumbas69.catacommons.pulsar.CatacumbasTopics;
import com.catacumbas69.catacommons.pulsar.PulsarProvisioner;
import com.catacumbas69.catacommons.pulsar.RetryingSender;
import com.catacumbas69.catacommons.pulsar.RetryingSenderFactory;
import com.catacumbas69.catacommons.pulsar.TopicSpec;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import org.apache.pulsar.client.admin.PulsarAdmin;
import org.apache.pulsar.client.api.PulsarClient;
import org.apache.pulsar.client.api.Schema;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
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
 *       (the events topic is always one) once the app has started.</li>
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
}
