package com.catacumbas69.catasonarr.service;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.springframework.stereotype.Service;

/**
 * The folder browser behind Sonarr's {@code /api/v3/filesystem} (orch.md decision 114), translated from Sonarr's
 * {@code FileSystemLookupService}: the whole file system of the pod, as Sonarr shows its own. Sonarr's list of system
 * folders is hidden (Synology's {@code #recycle} and {@code @eaDir} among them), and so is every name that starts
 * with a dot.
 */
@Service
public class FileSystemLookup {

    /** Folders Sonarr never shows, lower case: Windows, macOS, QNAP and Synology system folders. */
    private static final Set<String> SYSTEM = Set.of("boot", "bootmgr", "cache", "msocache", "recovery", "$recycle.bin",
            "recycler", "system volume information", "temporary internet files", "windows", ".fseventd", ".spotlight",
            ".trashes", ".vol", "cachedmessages", "caches", "trash", ".@__thumb", "@eadir", "#recycle");

    private static final Comparator<Entry> BY_NAME = Comparator.comparing(Entry::name, String.CASE_INSENSITIVE_ORDER)
            .thenComparing(Entry::name);

    /**
     * A folder or a file.
     *
     * @param type {@code folder} or {@code file}
     * @param name its name
     * @param path its path; a folder's ends with {@code /}
     * @param extension a file's extension with its dot, or {@code null}
     * @param size a file's size in bytes; 0 for a folder
     * @param lastModified when it last changed, or {@code null}
     */
    public record Entry(String type, String name, String path, String extension, long size, Instant lastModified) {
    }

    /**
     * What a folder holds.
     *
     * @param parent its parent, ending with {@code /}; empty for a relative path, {@code null} for {@code /}
     * @param directories its folders, by name
     * @param files its files, by name, when asked
     */
    public record Contents(String parent, List<Entry> directories, List<Entry> files) {

        static Contents empty(String parent) {
            return new Contents(parent, List.of(), List.of());
        }
    }

    /**
     * Whether a folder or file is left out of the browser, and of a root folder's unmapped folders.
     *
     * @param name its name
     * @return true for Sonarr's system folders and for names that start with a dot
     */
    public static boolean hidden(String name) {
        return name.startsWith(".") || SYSTEM.contains(name.toLowerCase(Locale.ROOT));
    }

    /**
     * Lists a folder, as Sonarr: what is typed up to its last {@code /} is the folder ({@code /data/Se} lists
     * {@code /data/}), unless {@code allowFoldersWithoutTrailingSlashes} and the whole text is a folder.
     *
     * @param query the typed path; empty for {@code /}
     * @param includeFiles whether files are listed too
     * @param allowFoldersWithoutTrailingSlashes whether {@code /data/Series} lists {@code /data/Series/} itself
     * @return the contents; empty when the folder cannot be read
     */
    public Contents lookup(String query, boolean includeFiles, boolean allowFoldersWithoutTrailingSlashes) {
        String typed = query == null || query.isBlank() ? "/" : query.strip();
        if (allowFoldersWithoutTrailingSlashes && typed.startsWith("/") && isDirectory(typed)) {
            return contents(typed, includeFiles);
        }
        int last = typed.lastIndexOf('/');
        return last == -1 ? Contents.empty(null) : contents(typed.substring(0, last + 1), includeFiles);
    }

    /**
     * Whether a path is a file, as Sonarr's {@code /api/v3/filesystem/type}.
     *
     * @param path the path
     * @return {@code file} when it is a file, else {@code folder} (even when it does not exist, as Sonarr)
     */
    public String type(String path) {
        try {
            return path != null && Files.isRegularFile(Path.of(path)) ? "file" : "folder";
        } catch (InvalidPathException e) {
            return "folder";
        }
    }

    private Contents contents(String folder, boolean includeFiles) {
        Path dir;
        try {
            dir = Path.of(folder);
        } catch (InvalidPathException e) {
            return Contents.empty(null);
        }
        String parent = parent(dir, folder);
        List<Entry> directories = new ArrayList<>();
        List<Entry> files = new ArrayList<>();
        try (DirectoryStream<Path> children = Files.newDirectoryStream(dir)) {
            for (Path child : children) {
                String name = child.getFileName().toString();
                if (hidden(name)) {
                    continue;
                }
                BasicFileAttributes attributes;
                try {
                    attributes = Files.readAttributes(child, BasicFileAttributes.class);
                } catch (IOException e) {
                    continue; // gone, or not ours to read: left out, as Sonarr does with what it cannot read
                }
                Instant modified = attributes.lastModifiedTime().toInstant();
                if (attributes.isDirectory()) {
                    directories.add(new Entry("folder", name, withSlash(child.toString()), null, 0, modified));
                } else if (includeFiles && attributes.isRegularFile()) {
                    int dot = name.lastIndexOf('.');
                    files.add(new Entry("file", name, child.toString(), dot > 0 ? name.substring(dot) : null,
                            attributes.size(), modified));
                }
            }
        } catch (IOException | SecurityException e) {
            return Contents.empty(parent); // missing, not a folder or not readable: only the way back up
        }
        directories.sort(BY_NAME);
        files.sort(BY_NAME);
        return new Contents(parent, directories, files);
    }

    private static boolean isDirectory(String path) {
        try {
            return Files.isDirectory(Path.of(path));
        } catch (InvalidPathException e) {
            return false;
        }
    }

    private static String parent(Path dir, String folder) {
        Path parent = dir.toAbsolutePath().normalize().getParent();
        if (parent != null && folder.startsWith("/")) {
            return withSlash(parent.toString());
        }
        return "/".equals(folder) ? null : "";
    }

    private static String withSlash(String path) {
        return path.endsWith("/") ? path : path + "/";
    }
}
