package com.catacumbas69.catasonarr.model;

import java.util.List;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * A custom format, as Sonarr keeps it (orch.md decision 112).
 *
 * @param id Sonarr's id
 * @param name unique name
 * @param includeCustomFormatWhenRenaming whether its name goes into file names
 * @param specifications its conditions
 */
@Document("catasonarr_customformats")
public record CustomFormat(@Id int id, String name, boolean includeCustomFormatWhenRenaming,
                           List<Specification> specifications) {
}
