package com.catacumbas69.catapulsar.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.RETURNS_SELF;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.catacumbas69.catapulsar.TestProperties;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.CompletableFuture;
import org.apache.pulsar.client.api.Producer;
import org.apache.pulsar.client.api.ProducerBuilder;
import org.apache.pulsar.client.api.PulsarClient;
import org.apache.pulsar.client.api.PulsarClientException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

@SuppressWarnings("unchecked")
class ProducerCacheTest {

    private final PulsarClient client = mock(PulsarClient.class);
    private final ProducerBuilder<byte[]> builder = mock(ProducerBuilder.class, RETURNS_SELF);
    private final MutableClock clock = new MutableClock(Instant.parse("2026-10-30T21:00:00Z"));
    private ProducerCache cache;

    @BeforeEach
    void setUp() {
        when(client.newProducer()).thenReturn(builder);
        cache = new ProducerCache(client, TestProperties.properties(2, 100), clock);
    }

    @Test
    void reusesProducerForSameTopic() throws PulsarClientException {
        Producer<byte[]> producer = connectedProducer();
        when(builder.create()).thenReturn(producer);

        Producer<byte[]> first = cache.get("persistent://a/b/c");
        Producer<byte[]> second = cache.get("persistent://a/b/c");

        assertThat(second).isSameAs(first);
        verify(builder, times(1)).create();
    }

    @Test
    void replacesProducerThatLostItsConnection() throws PulsarClientException {
        Producer<byte[]> broken = connectedProducer();
        Producer<byte[]> fresh = connectedProducer();
        when(builder.create()).thenReturn(broken, fresh);
        cache.get("persistent://a/b/c");
        when(broken.isConnected()).thenReturn(false);

        assertThat(cache.get("persistent://a/b/c")).isSameAs(fresh);
        verify(broken).closeAsync();
    }

    @Test
    void closesIdleProducers() throws PulsarClientException {
        Producer<byte[]> producer = connectedProducer();
        when(builder.create()).thenReturn(producer);
        cache.get("persistent://a/b/c");

        clock.advance(Duration.ofMinutes(4));
        cache.evictIdle();
        verify(producer, never()).closeAsync();

        clock.advance(Duration.ofMinutes(2));
        cache.evictIdle();
        verify(producer).closeAsync();
        assertThat(cache.size()).isZero();
    }

    @Test
    void evictsLeastRecentlyUsed_whenFull() throws PulsarClientException {
        Producer<byte[]> a = connectedProducer();
        Producer<byte[]> b = connectedProducer();
        Producer<byte[]> c = connectedProducer();
        when(builder.create()).thenReturn(a, b, c);

        cache.get("persistent://t/n/a");
        clock.advance(Duration.ofSeconds(1));
        cache.get("persistent://t/n/b");
        clock.advance(Duration.ofSeconds(1));
        cache.get("persistent://t/n/c");

        verify(a).closeAsync();
        verify(b, never()).closeAsync();
        assertThat(cache.size()).isEqualTo(2);
    }

    private static Producer<byte[]> connectedProducer() {
        Producer<byte[]> producer = mock(Producer.class);
        when(producer.isConnected()).thenReturn(true);
        when(producer.closeAsync()).thenReturn(CompletableFuture.completedFuture(null));
        return producer;
    }

    /** A clock tests can move forward. */
    private static final class MutableClock extends Clock {

        private Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
