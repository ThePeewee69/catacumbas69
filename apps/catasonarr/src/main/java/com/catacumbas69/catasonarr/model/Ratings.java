package com.catacumbas69.catasonarr.model;

/**
 * A series' rating.
 *
 * @param votes number of votes, 0 when the source does not say
 * @param value rating out of 10
 */
public record Ratings(int votes, double value) {
}
