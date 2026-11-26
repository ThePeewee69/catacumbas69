package com.catacumbas69.catasonarr.quality;

/**
 * A quality's revision, as Sonarr's {@code Revision} (GPL-3.0): a PROPER or REPACK raises the version, each REAL the
 * real count. Equal when version and real are; {@code isRepack} does not count.
 */
public final class Revision implements Comparable<Revision> {

    /** The version, 1 unless a PROPER, REPACK or vN says otherwise. */
    public int version;
    /** How many times REAL appears. */
    public int real;
    /** Whether it is a REPACK (or RERIP). */
    public boolean isRepack;

    /** Version 1. */
    public Revision() {
        this(1, 0, false);
    }

    /**
     * A revision.
     *
     * @param version the version
     * @param real the real count
     * @param isRepack whether it is a repack
     */
    public Revision(int version, int real, boolean isRepack) {
        this.version = version;
        this.real = real;
        this.isRepack = isRepack;
    }

    @Override
    public int compareTo(Revision other) {
        if (real != other.real) {
            return Integer.compare(real, other.real);
        }
        return Integer.compare(version, other.version);
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof Revision r && r.version == version && r.real == real;
    }

    @Override
    public int hashCode() {
        return version ^ real << 8;
    }

    @Override
    public String toString() {
        return "v" + version + (real > 0 ? " Real:" + real : "");
    }
}
