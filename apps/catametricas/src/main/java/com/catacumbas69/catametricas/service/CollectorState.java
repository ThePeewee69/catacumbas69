package com.catacumbas69.catametricas.service;

import com.catacumbas69.catacommons.events.EventData;
import com.catacumbas69.catacommons.events.EventPublisher;
import com.catacumbas69.catametricas.model.TaskRun;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * How collection goes in this instance: its identity, its last task, its last error, and the events that report
 * changes of state on {@code catacumbas69/catametricas/events} (ADR 0008). {@code collection.failed} is published
 * on the first failure (of scheduling, claiming or collecting) after a success or at start, and
 * {@code collection.recovered} on the first success after a failure, both with subject {@value #SUBJECT}.
 */
@Component
public class CollectorState {

    /** Subject of the collection events. */
    static final String SUBJECT = "collector";
    private static final Logger LOG = LoggerFactory.getLogger(CollectorState.class);
    private static final String ENTITY = "collection";
    private static final String ACTOR = "catametricas";

    private final EventPublisher events;
    private final String instance;
    private volatile TaskRun lastRun;
    private volatile String lastError;
    private volatile Boolean healthy;

    /**
     * Creates the state.
     *
     * @param events publishes failure and recovery events
     */
    public CollectorState(EventPublisher events) {
        this.events = events;
        this.instance = hostName() + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    /**
     * Returns this instance's identity, stored in the task claims.
     *
     * @return host name plus a random suffix
     */
    public String instance() {
        return instance;
    }

    /**
     * Records a successful task.
     *
     * @param run the finished run
     * @param intervalSeconds interval in use
     */
    public void succeeded(TaskRun run, int intervalSeconds) {
        lastRun = run;
        lastError = null;
        boolean wasFailing = Boolean.FALSE.equals(healthy);
        healthy = true;
        if (wasFailing) {
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("task", run.id());
            details.put("topics", run.topics());
            details.put("failedTopics", run.failedTopics());
            details.put("durationMillis", run.durationMillis());
            details.put("intervalSeconds", intervalSeconds);
            details.put("instance", instance);
            publish("recovered", details);
        }
    }

    /**
     * Records a failure.
     *
     * @param stage {@code schedule}, {@code claim} or {@code collect}
     * @param error what went wrong
     * @param intervalSeconds interval in use
     */
    public void failed(String stage, String error, int intervalSeconds) {
        lastError = stage + ": " + error;
        if (Boolean.FALSE.equals(healthy)) {
            return;
        }
        healthy = false;
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("stage", stage);
        details.put("error", error);
        details.put("intervalSeconds", intervalSeconds);
        details.put("instance", instance);
        publish("failed", details);
    }

    /**
     * Returns the last task this instance finished.
     *
     * @return the run, or {@code null} if none yet
     */
    public TaskRun lastRun() {
        return lastRun;
    }

    /**
     * Returns the error of the last failure since the last success.
     *
     * @return the message, or {@code null}
     */
    public String lastError() {
        return lastError;
    }

    private void publish(String action, Map<String, Object> details) {
        try {
            events.publish(ENTITY, action, SUBJECT, EventData.action(ACTOR, details));
        } catch (RuntimeException e) {
            LOG.warn("Event collection.{} not published: {}", action, e.getMessage());
        }
    }

    private static String hostName() {
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (UnknownHostException e) {
            return "catametricas";
        }
    }
}
