package com.catacumbas69.catasonarr.model;

/**
 * A custom format's score in a quality profile.
 *
 * @param format the custom format's id
 * @param score its score
 */
public record FormatItem(int format, int score) {
}
