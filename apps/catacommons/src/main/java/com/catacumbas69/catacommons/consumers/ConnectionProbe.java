package com.catacumbas69.catacommons.consumers;

/**
 * Checks one dependency of the app's consumers now, e.g. MongoDB or Pulsar's admin API. Apps declare their probes
 * as beans; {@link ConsumerControl} runs them for <em>Check connections</em> and before an automatic resume.
 */
public interface ConnectionProbe {

    /** Name of the MongoDB probe. */
    String MONGODB = "mongodb";

    /** Name of the Pulsar admin API probe. */
    String PULSAR = "pulsar";

    /**
     * Returns the dependency's name.
     *
     * @return e.g. {@link #MONGODB}
     */
    String name();

    /**
     * Checks the dependency; never throws.
     *
     * @return the result
     */
    ProbeResult probe();
}
