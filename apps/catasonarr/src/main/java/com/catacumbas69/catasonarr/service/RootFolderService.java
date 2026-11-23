package com.catacumbas69.catasonarr.service;

import com.catacumbas69.catasonarr.model.RootFolder;
import com.catacumbas69.catasonarr.model.Series;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;

/**
 * Root folders (orch.md decision 112): where series folders are made, configurable as in Sonarr. Paths are inside
 * the pod, where the NAS is mounted as Sonarr mounts it ({@code /data}, {@code /downloads}).
 */
@Service
public class RootFolderService {

    /** Sequence name. */
    public static final String SEQUENCE = "rootfolder";

    /**
     * A root folder as the API shows it.
     *
     * @param folder the root folder
     * @param accessible whether it exists and can be written
     * @param freeSpace free bytes, or {@code null} when not accessible
     * @param unmappedFolders its sub-folders that are no series' folder
     */
    public record Described(RootFolder folder, boolean accessible, Long freeSpace, List<Path> unmappedFolders) {
    }

    private final MongoTemplate mongo;
    private final Sequences sequences;
    private final CatasonarrEvents events;

    /**
     * Creates the service.
     *
     * @param mongo MongoDB
     * @param sequences ids
     * @param events events
     */
    public RootFolderService(MongoTemplate mongo, Sequences sequences, CatasonarrEvents events) {
        this.mongo = mongo;
        this.sequences = sequences;
        this.events = events;
    }

    /**
     * Returns every root folder.
     *
     * @return the folders by id
     */
    public List<RootFolder> all() {
        return mongo.find(new Query().with(Sort.by("id")), RootFolder.class);
    }

    /**
     * Returns a root folder.
     *
     * @param id the id
     * @return the folder
     */
    public RootFolder get(int id) {
        RootFolder folder = mongo.findById(id, RootFolder.class);
        if (folder == null) {
            throw new NotFoundException("Root folder " + id);
        }
        return folder;
    }

    /**
     * Finds the root folder a path is in.
     *
     * @param path a series' folder
     * @return the root folder, or empty
     */
    public Optional<RootFolder> containing(String path) {
        Path p = Path.of(path).normalize();
        return all().stream().filter(r -> p.startsWith(Path.of(r.path())) && !p.equals(Path.of(r.path())))
                .max(Comparator.comparingInt(r -> r.path().length()));
    }

    /**
     * Returns whether a path is a root folder.
     *
     * @param path the path
     * @return {@code true} if it is one
     */
    public boolean isRootFolder(String path) {
        return mongo.exists(Query.query(Criteria.where("path").is(normalize(path))), RootFolder.class);
    }

    /**
     * Adds a root folder, as Sonarr checks it: absolute, existing, writable, not already a root folder or inside
     * one.
     *
     * @param path the path
     * @param actor who did it
     * @return the folder with its id
     */
    public RootFolder add(String path, String actor) {
        if (path == null || path.isBlank() || !Path.of(path).isAbsolute()) {
            throw new BadRequestException("path", "Invalid Path");
        }
        String normalized = normalize(path);
        Path p = Path.of(normalized);
        if (!Files.isDirectory(p)) {
            throw new BadRequestException("path", "Folder '" + normalized + "' does not exist");
        }
        if (!Files.isWritable(p)) {
            throw new BadRequestException("path", "Folder '" + normalized + "' is not writable by user");
        }
        for (RootFolder r : all()) {
            Path other = Path.of(r.path());
            if (p.equals(other)) {
                throw new BadRequestException("path", "Path is already configured as a root folder");
            }
            if (p.startsWith(other) || other.startsWith(p)) {
                throw new BadRequestException("path", "Path is a child or parent of the root folder " + r.path());
            }
        }
        RootFolder folder = new RootFolder(sequences.next(SEQUENCE), normalized);
        mongo.insert(folder);
        events.change("rootfolder", "added", normalized, actor, null, Map.of("id", folder.id(), "path", normalized));
        return folder;
    }

    /**
     * Removes a root folder; its series and files stay.
     *
     * @param id the id
     * @param actor who did it
     */
    public void delete(int id, String actor) {
        RootFolder folder = get(id);
        mongo.remove(Query.query(Criteria.where("id").is(id)), RootFolder.class);
        events.change("rootfolder", "deleted", folder.path(), actor, Map.of("id", id, "path", folder.path()), null);
    }

    /**
     * Describes root folders for the API: access, free space and the sub-folders that are no series' folder.
     *
     * @param folders the folders
     * @param withUnmapped whether to list the unmapped sub-folders (reads each folder)
     * @return each described
     */
    public List<Described> describe(List<RootFolder> folders, boolean withUnmapped) {
        Set<Path> seriesPaths = withUnmapped ? mongo.findAll(Series.class).stream().map(Series::path)
                .filter(s -> s != null && !s.isBlank()).map(s -> Path.of(s).normalize()).collect(Collectors.toSet())
                : Set.of();
        List<Described> described = new ArrayList<>();
        for (RootFolder r : folders) {
            Path p = Path.of(r.path());
            boolean accessible = Files.isDirectory(p) && Files.isWritable(p);
            Long free = null;
            List<Path> unmapped = new ArrayList<>();
            if (accessible) {
                try {
                    free = Files.getFileStore(p).getUsableSpace();
                } catch (IOException e) {
                    free = null;
                }
                if (withUnmapped) {
                    unmapped = unmapped(p, seriesPaths);
                }
            }
            described.add(new Described(r, accessible, free, unmapped));
        }
        return described;
    }

    /**
     * Normalizes a path: no {@code .}, {@code ..} or trailing slash.
     *
     * @param path the path
     * @return the normalized path
     */
    public static String normalize(String path) {
        String n = Path.of(path.strip()).normalize().toString();
        return n.length() > 1 && n.endsWith("/") ? n.substring(0, n.length() - 1) : n;
    }

    private static List<Path> unmapped(Path root, Set<Path> seriesPaths) {
        try (Stream<Path> children = Files.list(root)) {
            return children.filter(Files::isDirectory)
                    .filter(c -> !FileSystemLookup.hidden(c.getFileName().toString()))
                    .filter(c -> !seriesPaths.contains(c.normalize()))
                    .sorted()
                    .toList();
        } catch (IOException e) {
            return List.of();
        }
    }
}
