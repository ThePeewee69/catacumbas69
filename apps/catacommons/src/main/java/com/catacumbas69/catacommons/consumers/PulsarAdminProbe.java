package com.catacumbas69.catacommons.consumers;

import org.apache.pulsar.client.admin.PulsarAdmin;

/**
 * Checks Pulsar's admin API by listing the clusters.
 */
public class PulsarAdminProbe implements ConnectionProbe {

    private final PulsarAdmin admin;

    /**
     * Creates the probe.
     *
     * @param admin the app's admin client
     */
    public PulsarAdminProbe(PulsarAdmin admin) {
        this.admin = admin;
    }

    @Override
    public String name() {
        return PULSAR;
    }

    @Override
    public ProbeResult probe() {
        long start = System.nanoTime();
        try {
            admin.clusters().getClusters();
            return ProbeResult.up(elapsed(start));
        } catch (Exception e) {
            return ProbeResult.down(elapsed(start), e.getMessage());
        }
    }

    private static long elapsed(long start) {
        return (System.nanoTime() - start) / 1_000_000;
    }
}
