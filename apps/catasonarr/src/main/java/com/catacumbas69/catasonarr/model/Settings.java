package com.catacumbas69.catasonarr.model;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * Catasonarr's settings that Sonarr keeps in its {@code Config} table (orch.md decision 122): indexers, download
 * clients, media management and Plex. One document, {@code settings}, in {@code catasonarr_config}; Sonarr's
 * defaults until changed or imported from Sonarr's database.
 *
 * @param id always {@link #ID}
 * @param rssSyncInterval minutes between RSS syncs, 0 to turn them off (Sonarr: 15, at least 10)
 * @param minimumAge minutes a release must be old to be grabbed
 * @param maximumSize MB, 0 for no limit
 * @param enableCompletedDownloadHandling import finished downloads
 * @param autoRedownloadFailed blocklist a failed download and search again
 * @param autoRedownloadFailedFromInteractiveSearch also for releases grabbed by hand
 * @param downloadPropersAndRepacks {@code preferAndUpgrade}, {@code doNotUpgrade} or {@code doNotPrefer}
 * @param autoUnmonitorPreviouslyDownloadedEpisodes unmonitor an episode whose file is deleted
 * @param recycleBin where replaced and deleted files go, or {@code ""} to delete them
 * @param recycleBinCleanupDays days a file stays in the recycle bin, 0 to keep
 * @param createEmptySeriesFolders create a series' folder when it is added
 * @param deleteEmptyFolders delete a season's or series' folder left empty
 * @param skipFreeSpaceCheckWhenImporting import without checking the free space
 * @param minimumFreeSpaceWhenImporting MB to leave free
 * @param importExtraFiles import subtitles and the like with the episode
 * @param extraFileExtensions their extensions, e.g. {@code srt,ass,nfo}
 * @param episodeTitleRequired when an episode needs its title before it is renamed: {@code always},
 *                             {@code bulkSeasonReleases} or {@code never}
 * @param plexEnabled tell Plex after an import, a rename or a deletion (its token is in the Secret
 *                    {@code catasonarr-plex}, never here)
 * @param plexUrl Plex's address, e.g. {@code http://plex:32400}
 * @param plexMapFrom a folder as Catasonarr sees it, e.g. {@code /data/}
 * @param plexMapTo the same folder as Plex sees it
 */
@Document("catasonarr_config")
public record Settings(
        @Id String id,
        int rssSyncInterval,
        int minimumAge,
        int maximumSize,
        boolean enableCompletedDownloadHandling,
        boolean autoRedownloadFailed,
        boolean autoRedownloadFailedFromInteractiveSearch,
        String downloadPropersAndRepacks,
        boolean autoUnmonitorPreviouslyDownloadedEpisodes,
        String recycleBin,
        int recycleBinCleanupDays,
        boolean createEmptySeriesFolders,
        boolean deleteEmptyFolders,
        boolean skipFreeSpaceCheckWhenImporting,
        int minimumFreeSpaceWhenImporting,
        boolean importExtraFiles,
        String extraFileExtensions,
        String episodeTitleRequired,
        boolean plexEnabled,
        String plexUrl,
        String plexMapFrom,
        String plexMapTo) {

    /** The document's id in {@code catasonarr_config}. */
    public static final String ID = "settings";

    /** Sonarr's defaults; the recycle bin is {@code /data/.recycle} for 7 days (orch.md decision 122). */
    public static final Settings DEFAULT = new Settings(ID, 15, 0, 0, true, true, false, "preferAndUpgrade", false,
            "/data/.recycle", 7, false, false, false, 100, true, "srt,ass,ssa,sub,idx,nfo", "always", false, "",
            "", "");
}
