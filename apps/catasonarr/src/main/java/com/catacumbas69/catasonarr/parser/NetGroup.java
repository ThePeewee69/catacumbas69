package com.catacumbas69.catasonarr.parser;

import java.util.List;

/**
 * A group of a match, as .NET's {@code Group}: every capture it made, in order; its value is the last one's.
 *
 * @param captures the captures, possibly none
 */
public record NetGroup(List<NetCapture> captures) {

    /**
     * Whether it captured anything, as {@code Group.Success}.
     *
     * @return whether there is a capture
     */
    public boolean success() {
        return !captures.isEmpty();
    }

    /**
     * The last capture's text, or {@code ""}, as {@code Group.Value}.
     *
     * @return the value
     */
    public String value() {
        return captures.isEmpty() ? "" : captures.get(captures.size() - 1).value();
    }

    /**
     * Where the last capture starts, or 0.
     *
     * @return the index
     */
    public int index() {
        return captures.isEmpty() ? 0 : captures.get(captures.size() - 1).index();
    }

    /**
     * The last capture's length, or 0.
     *
     * @return the length
     */
    public int length() {
        return value().length();
    }

    /**
     * Where the last capture ends.
     *
     * @return the index after it
     */
    public int endIndex() {
        return index() + length();
    }
}
