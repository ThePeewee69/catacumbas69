package com.catacumbas69.catasonarr.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.List;

/**
 * Sonarr's {@code FileSystemResult}: what a folder holds (orch.md decision 114).
 *
 * @param parent the folder above, ending with {@code /}; absent for {@code /}
 * @param directories its folders
 * @param files its files, when asked for
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record FileSystemResource(String parent, List<Model> directories, List<Model> files) {

    /**
     * Sonarr's {@code FileSystemModel}.
     *
     * @param type {@code folder} or {@code file}
     * @param name its name
     * @param path its path; a folder's ends with {@code /}
     * @param extension a file's extension, with its dot
     * @param size a file's size in bytes; 0 for a folder
     * @param lastModified when it last changed
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Model(String type, String name, String path, String extension, long size, Instant lastModified) {
    }
}
