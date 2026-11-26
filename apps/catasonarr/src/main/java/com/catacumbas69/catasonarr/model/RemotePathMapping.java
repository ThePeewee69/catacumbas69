package com.catacumbas69.catasonarr.model;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * Where a download client's folder is for Catasonarr, as Sonarr's remote path mappings (orch.md decision 122).
 *
 * @param id Sonarr-style id
 * @param host the client's host, as in its settings
 * @param remotePath the folder as the client sees it, ending with {@code /}
 * @param localPath the same folder for Catasonarr, ending with {@code /}
 */
@Document("catasonarr_remotepathmappings")
public record RemotePathMapping(@Id int id, String host, String remotePath, String localPath) {
}
