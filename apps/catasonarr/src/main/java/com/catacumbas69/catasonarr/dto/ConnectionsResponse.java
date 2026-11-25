package com.catacumbas69.catasonarr.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

/**
 * Where Catasonarr connects (orch.md decision 119): read-only, never with the password.
 *
 * @param pulsar Pulsar's addresses
 * @param mongodb MongoDB's connection, without its password
 */
public record ConnectionsResponse(Pulsar pulsar, Mongo mongodb) {

    /**
     * Pulsar's addresses.
     *
     * @param serviceUrl the admin API
     * @param brokerUrl the binary protocol
     * @param listenerName the advertised listener, when one is used
     * @param outsideServiceUrl the admin API from outside the cluster
     * @param outsideBrokerUrl the binary protocol from outside the cluster, with the listener {@code external}
     */
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    public record Pulsar(String serviceUrl, String brokerUrl, String listenerName, String outsideServiceUrl,
                         String outsideBrokerUrl) {
    }

    /**
     * MongoDB's connection string and what it says, without the password.
     *
     * @param uri the connection string, with {@code ****} for the password
     * @param hosts the hosts, {@code host:port}
     * @param database the database used
     * @param username the user, when there is one
     * @param authSource the authentication database, when set
     * @param replicaSet the replica set, when set
     * @param tls whether TLS is on
     * @param directConnection whether it connects to one host directly
     * @param outsideUri the same from outside the cluster (IntelliJ, Compass), through meloes' NodePort with
     *     {@code directConnection=true}, with {@code ****} for the password
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Mongo(String uri, List<String> hosts, String database, String username, String authSource,
                        String replicaSet, boolean tls, Boolean directConnection, String outsideUri) {
    }
}
