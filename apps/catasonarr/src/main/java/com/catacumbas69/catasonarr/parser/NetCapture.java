package com.catacumbas69.catasonarr.parser;

/**
 * One capture of a group, as .NET's {@code Capture}: what it matched and where.
 *
 * @param value the text captured
 * @param index where it starts in the input
 */
public record NetCapture(String value, int index) {

    /**
     * Where it ends, as Sonarr's {@code EndIndex()}.
     *
     * @return the index after its last character
     */
    public int endIndex() {
        return index + value.length();
    }
}
