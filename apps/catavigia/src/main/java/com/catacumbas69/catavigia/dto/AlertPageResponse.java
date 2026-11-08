package com.catacumbas69.catavigia.dto;

import java.util.List;

/**
 * One page of the archive, newest closed first.
 *
 * @param items the alerts of the page
 * @param total archived alerts matching the filters
 * @param page page number, from 0
 * @param size page size
 */
public record AlertPageResponse(List<AlertResponse> items, long total, int page, int size) {
}
