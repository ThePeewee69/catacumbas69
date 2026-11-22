package com.catacumbas69.catasonarr.model;

/**
 * A season of a series and whether it is monitored.
 *
 * @param seasonNumber 0 for the specials
 * @param monitored whether its episodes are wanted
 */
public record Season(int seasonNumber, boolean monitored) {
}
