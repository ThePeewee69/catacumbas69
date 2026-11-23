package com.catacumbas69.catasonarr.controller;

import com.catacumbas69.catasonarr.dto.FileSystemResource;
import com.catacumbas69.catasonarr.service.FileSystemLookup;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Sonarr's {@code /api/v3/filesystem} (orch.md decision 114): the folder browser of the root folders' Browse button,
 * over the pod's whole file system, as Sonarr's. Reads only.
 */
@Tag(name = "File system (Sonarr API v3)")
@RestController
@RequestMapping("/api/v3/filesystem")
public class FileSystemController {

    private final FileSystemLookup lookup;

    /**
     * Creates the controller.
     *
     * @param lookup the folder browser
     */
    public FileSystemController(FileSystemLookup lookup) {
        this.lookup = lookup;
    }

    /**
     * What a folder holds: the text up to its last {@code /} is the folder, as in Sonarr.
     *
     * @param path the typed path; empty for {@code /}
     * @param includeFiles whether files are listed too
     * @param allowFoldersWithoutTrailingSlashes whether a folder typed without its last {@code /} is listed itself
     * @return the parent, the folders and the files
     */
    @Operation(summary = "What a folder holds")
    @GetMapping
    public FileSystemResource contents(@RequestParam(required = false) String path,
            @RequestParam(defaultValue = "false") boolean includeFiles,
            @RequestParam(defaultValue = "false") boolean allowFoldersWithoutTrailingSlashes) {
        FileSystemLookup.Contents contents = lookup.lookup(path, includeFiles, allowFoldersWithoutTrailingSlashes);
        return new FileSystemResource(contents.parent(), models(contents.directories()), models(contents.files()));
    }

    /**
     * Whether a path is a file or a folder.
     *
     * @param path the path
     * @return {@code {"type": "file"}} or {@code {"type": "folder"}} (also when it does not exist, as Sonarr)
     */
    @Operation(summary = "Whether a path is a file or a folder")
    @GetMapping("/type")
    public Map<String, String> type(@RequestParam(required = false) String path) {
        return Map.of("type", lookup.type(path));
    }

    private static List<FileSystemResource.Model> models(List<FileSystemLookup.Entry> entries) {
        return entries.stream().map(e -> new FileSystemResource.Model(e.type(), e.name(), e.path(), e.extension(),
                e.size(), e.lastModified())).toList();
    }
}
