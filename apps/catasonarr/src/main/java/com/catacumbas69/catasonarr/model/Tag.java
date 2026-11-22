package com.catacumbas69.catasonarr.model;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * A tag, in lower case as Sonarr keeps them.
 *
 * @param id Sonarr-style id
 * @param label the label
 */
@Document("catasonarr_tags")
public record Tag(@Id int id, String label) {
}
