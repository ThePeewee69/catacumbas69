package com.catacumbas69.catasonarr.dto;

import java.util.List;

/**
 * Sonarr's {@code PagingResource}: one page of a longer list, as {@code /api/v3/queue} answers it.
 *
 * @param page the page, from 1
 * @param pageSize records per page
 * @param sortKey what the records are sorted by
 * @param sortDirection {@code ascending}, {@code descending} or {@code default}
 * @param totalRecords records on every page
 * @param records the records of this page
 * @param <T> the record's type
 */
public record PagingResource<T>(int page, int pageSize, String sortKey, String sortDirection, int totalRecords,
                                List<T> records) {
}
