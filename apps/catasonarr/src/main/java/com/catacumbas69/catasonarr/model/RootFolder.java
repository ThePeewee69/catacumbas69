package com.catacumbas69.catasonarr.model;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * A root folder: where series folders are made, as a path inside the pod (orch.md decision 112: the NAS is mounted
 * as Sonarr mounts it, {@code /data} and {@code /downloads}).
 *
 * @param id Sonarr-style id
 * @param path absolute path, without a trailing slash
 */
@Document("catasonarr_rootfolders")
public record RootFolder(@Id int id, String path) {
}
