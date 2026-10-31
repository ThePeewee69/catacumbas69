package com.catacumbas69.passario.model;

/**
 * Which side of a bridge a traffic count belongs to.
 */
public enum TrafficRole {
    /** Messages consumed from the source topic and delivered to every destination. */
    SOURCE,
    /** Messages accepted by one destination topic. */
    DESTINATION
}
