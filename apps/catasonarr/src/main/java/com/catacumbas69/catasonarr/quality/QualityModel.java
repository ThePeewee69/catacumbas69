package com.catacumbas69.catasonarr.quality;

/**
 * A release's or a file's quality with its revision, as Sonarr's {@code QualityModel} (GPL-3.0). Ordered by the
 * quality's weight, then real, then version; the detection sources say how it was found and do not count.
 */
public final class QualityModel implements Comparable<QualityModel> {

    /** The quality. */
    public Quality quality;
    /** The revision. */
    public Revision revision;
    /** How the source was found. */
    public QualityDetectionSource sourceDetectionSource = QualityDetectionSource.UNKNOWN;
    /** How the resolution was found. */
    public QualityDetectionSource resolutionDetectionSource = QualityDetectionSource.UNKNOWN;
    /** How the revision was found. */
    public QualityDetectionSource revisionDetectionSource = QualityDetectionSource.UNKNOWN;

    /** Unknown, version 1. */
    public QualityModel() {
        this(Qualities.UNKNOWN, new Revision());
    }

    /**
     * A quality.
     *
     * @param quality the quality
     * @param revision its revision, or {@code null} for version 1
     */
    public QualityModel(Quality quality, Revision revision) {
        this.quality = quality;
        this.revision = revision == null ? new Revision() : revision;
    }

    @Override
    public int compareTo(QualityModel other) {
        int weight = Integer.compare(Qualities.weight(quality), Qualities.weight(other.quality));
        if (weight != 0) {
            return weight;
        }
        return revision.compareTo(other.revision);
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof QualityModel q && q.quality.equals(quality) && q.revision.equals(revision);
    }

    @Override
    public int hashCode() {
        return (17 * 23 + revision.hashCode()) * 23 + quality.hashCode();
    }

    @Override
    public String toString() {
        return quality.name() + " " + revision;
    }
}
