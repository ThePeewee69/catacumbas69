package com.catacumbas69.catasonarr.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

/**
 * Sonarr's {@code RootFolderResource}.
 *
 * @param id the id
 * @param path the path in the pod
 * @param accessible whether it exists and can be written
 * @param freeSpace free bytes
 * @param unmappedFolders its sub-folders that are no series' folder
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record RootFolderResource(int id, String path, boolean accessible, Long freeSpace,
                                 List<UnmappedFolder> unmappedFolders) {

    /**
     * A sub-folder that is no series' folder.
     *
     * @param name its name
     * @param path its path
     * @param relativePath its path from the root folder
     */
    public record UnmappedFolder(String name, String path, String relativePath) {
    }
}
