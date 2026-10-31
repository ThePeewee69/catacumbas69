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
 * Schedules {@link StatsCollector#collectIfLeader()} with a delay read from {@link CollectInterval} after
 * every run, so a new interval set in Catapulsar applies from the next collection without a restart.
 */
@Configuration
@EnableScheduling
public class CollectionScheduler implements SchedulingConfigurer {

    private final StatsCollector collector;
    private final CollectInterval interval;
    private final Clock clock;
    private final Duration initialDelay;

    /**
     * Creates the scheduler.
     *
     * @param collector the collector
     * @param interval collection interval
     * @param clock time source
     * @param properties application properties, for the initial delay
     */
    public CollectionScheduler(StatsCollector collector, CollectInterval interval, Clock clock,
                               CatametricasProperties properties) {
        this.collector = collector;
        this.interval = interval;
        this.clock = clock;
        this.initialDelay = properties.collect().initialDelay();
    }

    @Override
    public void configureTasks(ScheduledTaskRegistrar registrar) {
        registrar.addTriggerTask(collector::collectIfLeader, context -> {
            Instant last = context.lastCompletion();
            return last == null ? clock.instant().plus(initialDelay) : last.plusSeconds(interval.seconds());
        });
    }
}
