package com.catacumbas69.catasonarr.quality;

/** How a part of a quality was found (Sonarr's {@code QualityDetectionSource}). */
public enum QualityDetectionSource {
    /** Not found. */
    UNKNOWN,
    /** From the name. */
    NAME,
    /** From the file's extension. */
    EXTENSION,
    /** From the file's contents (ffprobe). */
    MEDIA_INFO
}
