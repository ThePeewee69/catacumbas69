package com.catacumbas69.catasonarr.dto;

/**
 * Sonarr's {@code QueueStatusResource}: how many downloads are in the queue and whether any has an error or a
 * warning.
 *
 * @param id always 0
 * @param totalCount every download, unknown ones included
 * @param count the downloads of a known series
 * @param unknownCount the downloads Sonarr cannot match to a series
 * @param errors a known download has an error
 * @param warnings a known download has a warning
 * @param unknownErrors an unknown download has an error
 * @param unknownWarnings an unknown download has a warning
 */
public record QueueStatusResource(int id, int totalCount, int count, int unknownCount, boolean errors,
                                  boolean warnings, boolean unknownErrors, boolean unknownWarnings) {
}
