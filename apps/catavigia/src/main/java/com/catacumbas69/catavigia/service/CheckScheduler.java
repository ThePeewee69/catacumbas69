package com.catacumbas69.catavigia.service;

import com.catacumbas69.catavigia.config.CatavigiaProperties;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;

/**
 * Starts every round just after its start: publishes the round's check tasks, and checks this replica's own
 * MongoDB ({@code mongodb.down}, which no task can report). The interval is read before every round, so a new
 * {@code alerts.check-interval} applies from the next one.
 */
@Configuration
@EnableScheduling
public class CheckScheduler implements SchedulingConfigurer {

    /** Wait after the round start, so a clock running slightly early still lands in the new round. */
    static final Duration AFTER_START = Duration.ofSeconds(1);

    private final CheckTaskPublisher publisher;
    private final MongoWatch mongoWatch;
    private final Clock clock;
    private final Duration initialDelay;

    /**
     * Creates the scheduler.
     *
     * @param publisher publishes the tasks
     * @param mongoWatch watches this replica's MongoDB
     * @param clock time source
     * @param properties application properties, for the initial delay
     */
    public CheckScheduler(CheckTaskPublisher publisher, MongoWatch mongoWatch, Clock clock,
                          CatavigiaProperties properties) {
        this.publisher = publisher;
        this.mongoWatch = mongoWatch;
        this.clock = clock;
        this.initialDelay = properties.checks().initialDelay();
    }

    @Override
    public void configureTasks(ScheduledTaskRegistrar registrar) {
        registrar.addTriggerTask(publisher::publish, context -> nextRun(context.lastCompletion()));
        registrar.addTriggerTask(mongoWatch::tick, context -> nextRun(context.lastCompletion()));
    }

    private Instant nextRun(Instant last) {
        return last == null ? clock.instant().plus(initialDelay) : next(last, publisher.intervalSeconds());
    }

    /**
     * Returns when to run next.
     *
     * @param after the last run
     * @param seconds round length
     * @return just after the start of the round following {@code after}
     */
    static Instant next(Instant after, int seconds) {
        return CheckTaskPublisher.roundOf(after, seconds).plusSeconds(seconds).plus(AFTER_START);
    }
}
