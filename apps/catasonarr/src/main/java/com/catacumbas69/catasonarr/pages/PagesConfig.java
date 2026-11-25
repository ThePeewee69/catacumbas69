package com.catacumbas69.catasonarr.pages;

import com.catacumbas69.catacommons.CatacommonsProperties;
import com.catacumbas69.catacommons.CatacommonsScheduler;
import com.catacumbas69.catacommons.audit.SystemBridgeRegistry;
import com.catacumbas69.catacommons.consumers.ConsumerControl;
import com.catacumbas69.catacommons.events.CloudEvent;
import com.catacumbas69.catacommons.pulsar.TopicSpec;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import org.apache.pulsar.client.admin.PulsarAdmin;
import org.apache.pulsar.client.api.PulsarClient;
import org.apache.pulsar.client.api.Schema;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

/**
 * The pages that update themselves (orch.md decision 118): the topic {@code pages}, the system bridge that feeds it,
 * this replica's consumer, and the WebSocket {@code /api/v1/updates}, behind the Dex session like the rest of
 * {@code /api/}.
 */
@Configuration
@EnableWebSocket
public class PagesConfig implements WebSocketConfigurer {

    /** Where the pages connect. */
    public static final String PATH = "/api/v1/updates";

    private final PageUpdates pageUpdates = new PageUpdates();

    /**
     * The open pages.
     *
     * @return the WebSocket handler
     */
    @Bean
    public PageUpdates pageUpdates() {
        return pageUpdates;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(pageUpdates, PATH); // same origin only: Spring's default
    }

    /**
     * Declares {@code catacumbas69/catasonarr/pages}, created at start-up with the others; the bridge writes
     * Catasonarr's events into it as they are.
     *
     * @return the topic spec
     */
    @Bean
    public TopicSpec pagesTopic() {
        return TopicSpec.of(PagesTopics.LOCAL_NAME, Schema.JSON(CloudEvent.class));
    }

    /**
     * Registers the bridge {@code pages-catasonarr} in Cataponte, with the audit bridge's retry settings.
     *
     * @param registry Cataponte's system bridges
     * @param properties catacommons configuration
     * @param scheduler runs the attempts
     * @return the registrar
     */
    @Bean
    public PagesBridgeRegistrar pagesBridgeRegistrar(SystemBridgeRegistry registry, CatacommonsProperties properties,
                                                     CatacommonsScheduler scheduler) {
        CatacommonsProperties.AuditBridge settings = properties.auditBridge();
        return new PagesBridgeRegistrar(registry, properties.app(), scheduler.executor(), settings.retryInterval(),
                settings.maxRetryInterval());
    }

    /**
     * This replica's consumer of the pages' topic.
     *
     * @param client Pulsar client
     * @param admin Pulsar admin client
     * @param objectMapper reads the events
     * @param scheduler retries and pings
     * @param control consumer control
     * @param properties catacommons configuration
     * @param clock the app's clock, if any
     * @return the consumer, closed on shutdown
     */
    @Bean
    public PagesConsumer pagesConsumer(PulsarClient client, PulsarAdmin admin, ObjectMapper objectMapper,
                                       CatacommonsScheduler scheduler, ConsumerControl control,
                                       CatacommonsProperties properties, ObjectProvider<Clock> clock) {
        return new PagesConsumer(client, admin, objectMapper, scheduler, control, pageUpdates, properties.app(),
                clock.getIfAvailable(Clock::systemUTC));
    }
}
