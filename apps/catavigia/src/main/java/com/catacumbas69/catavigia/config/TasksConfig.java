package com.catacumbas69.catavigia.config;

import com.catacumbas69.catacommons.pulsar.CatacumbasTopics;
import com.catacumbas69.catacommons.pulsar.RetryingSender;
import com.catacumbas69.catacommons.pulsar.RetryingSenderFactory;
import com.catacumbas69.catacommons.pulsar.TopicSpec;
import com.catacumbas69.catavigia.model.CheckTask;
import org.apache.pulsar.client.api.Schema;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The check-task topic (orch.md decision 75), created at start-up with the namespace, and its sender.
 */
@Configuration
public class TasksConfig {

    /** Local name of the task topic in Catavigia's namespace. */
    public static final String TASKS = "check-tasks";

    /**
     * Returns the task topic.
     *
     * @return {@code persistent://catacumbas69/catavigia/check-tasks}
     */
    public static String tasksTopic() {
        return CatacumbasTopics.topic("catavigia", TASKS);
    }

    /**
     * Declares the task topic (not compacted: tasks are consumed and acknowledged).
     *
     * @return the topic spec
     */
    @Bean
    public TopicSpec checkTasksTopic() {
        return TopicSpec.of(TASKS, Schema.JSON(CheckTask.class));
    }

    /**
     * Sends check tasks, with catacommons' retries.
     *
     * @param factory sender factory from catacommons
     * @return the sender, closed on shutdown
     */
    @Bean(destroyMethod = "close")
    public RetryingSender<CheckTask> checkTaskSender(RetryingSenderFactory factory) {
        return factory.create(Schema.JSON(CheckTask.class), tasksTopic());
    }
}
