package com.catacumbas69.catametricas.config;

import com.catacumbas69.catacommons.pulsar.CatacumbasTopics;
import com.catacumbas69.catacommons.pulsar.RetryingSender;
import com.catacumbas69.catacommons.pulsar.RetryingSenderFactory;
import com.catacumbas69.catacommons.pulsar.TopicSpec;
import com.catacumbas69.catametricas.model.CollectTask;
import org.apache.pulsar.client.api.Schema;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The collection-task topic {@code catacumbas69/catametricas/collect-tasks} (ADR 0012): declared so catacommons
 * creates it at start-up with the {@link CollectTask} schema, and the sender every instance publishes tasks with.
 */
@Configuration
public class TasksConfig {

    /** Local name of the task topic in Catametricas' namespace. */
    public static final String TASKS = "collect-tasks";

    /**
     * Returns the task topic.
     *
     * @return {@code persistent://catacumbas69/catametricas/collect-tasks}
     */
    public static String tasksTopic() {
        return CatacumbasTopics.topic("catametricas", TASKS);
    }

    /**
     * Declares the task topic (not compacted: tasks are consumed and acknowledged).
     *
     * @return the topic spec
     */
    @Bean
    public TopicSpec collectTasksTopic() {
        return TopicSpec.of(TASKS, Schema.JSON(CollectTask.class));
    }

    /**
     * Sends collection tasks, with catacommons' retries.
     *
     * @param factory sender factory from catacommons
     * @return the sender, closed on shutdown
     */
    @Bean(destroyMethod = "close")
    public RetryingSender<CollectTask> collectTaskSender(RetryingSenderFactory factory) {
        return factory.create(Schema.JSON(CollectTask.class), tasksTopic());
    }
}
