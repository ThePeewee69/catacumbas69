package com.catacumbas69.catametricas.service;

import com.catacumbas69.catametricas.config.CatametricasProperties;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;

/**
 * Schedules {@link CollectTaskPublisher#publish()}: first after the initial delay, then just after the start of
 * every interval (aligned to the epoch, so every instance publishes the same task ids). The interval is read
 * before every run, so a new value set in Catapulsar applies from the next interval without a restart.
 */
@Configuration
@EnableScheduling
public class CollectionScheduler implements SchedulingConfigurer {

    /** Wait after the interval start, so a clock running slightly early still lands in the new interval. */
    static final Duration AFTER_START = Duration.ofSeconds(1);

    private final CollectTaskPublisher publisher;
    private final CollectInterval interval;
    private final Clock clock;
    private final Duration initialDelay;

    /**
     * Creates the scheduler.
     *
     * @param publisher publishes the tasks
     * @param interval collection interval
     * @param clock time source
     * @param properties application properties, for the initial delay
     */
    public CollectionScheduler(CollectTaskPublisher publisher, CollectInterval interval, Clock clock,
                               CatametricasProperties properties) {
        this.publisher = publisher;
        this.interval = interval;
        this.clock = clock;
        this.initialDelay = properties.collect().initialDelay();
    }

    @Override
    public void configureTasks(ScheduledTaskRegistrar registrar) {
        registrar.addTriggerTask(publisher::publish, context -> {
            Instant last = context.lastCompletion();
            return last == null ? clock.instant().plus(initialDelay) : next(last, interval.seconds());
        });
    }

    /**
     * Returns when to publish next.
     *
     * @param after the last run
     * @param seconds interval length
     * @return just after the start of the interval following {@code after}
     */
    static Instant next(Instant after, int seconds) {
        return CollectTaskPublisher.bucketOf(after, seconds).plusSeconds(seconds).plus(AFTER_START);
    }
}
