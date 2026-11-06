package com.catacumbas69.catacommons.consumers;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.UUID;

/**
 * This replica's identity: the host name (the pod name in the cluster) plus a random suffix, so a restarted pod is a
 * new instance. Used in consumer names ({@code <app>@<instance>}) and in the consumer status.
 */
public final class InstanceId {

    private final String id;

    /**
     * Creates the identity.
     *
     * @param app app name, used when the host name is unknown
     */
    public InstanceId(String app) {
        this.id = hostName(app) + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    /**
     * Returns the identity.
     *
     * @return host name plus a random suffix
     */
    public String id() {
        return id;
    }

    @Override
    public String toString() {
        return id;
    }

    private static String hostName(String app) {
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (UnknownHostException e) {
            return app;
        }
    }
}
