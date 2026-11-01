package com.catacumbas69.cataponte.service;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * This replica's identity in the worker status reports (ADR 0012): the host name (the pod name in the cluster) plus
 * a random suffix, so a restarted pod is a new instance.
 */
@Component
public class CataponteInstance {

    private final String id;

    /** Creates the identity. */
    public CataponteInstance() {
        this(hostName() + "-" + UUID.randomUUID().toString().substring(0, 8));
    }

    /**
     * Creates an identity with a given id (tests).
     *
     * @param id instance id
     */
    public CataponteInstance(String id) {
        this.id = id;
    }

    /**
     * Returns the identity.
     *
     * @return host name plus a random suffix
     */
    public String id() {
        return id;
    }

    private static String hostName() {
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (UnknownHostException e) {
            return "cataponte";
        }
    }
}
