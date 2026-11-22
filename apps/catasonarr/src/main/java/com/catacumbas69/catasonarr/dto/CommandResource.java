package com.catacumbas69.catasonarr.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.Map;

/**
 * Sonarr's {@code CommandResource}.
 *
 * @param id the id
 * @param name the command
 * @param commandName the command, as shown
 * @param message what happened
 * @param body what was sent
 * @param priority {@code normal}
 * @param status {@code queued}, {@code started}, {@code completed}, {@code failed}
 * @param result {@code unknown}, {@code successful}, {@code unsuccessful}
 * @param queued when it arrived
 * @param started when it started
 * @param ended when it ended
 * @param trigger {@code manual}
 * @param stateChangeTime when its status last changed
 * @param sendUpdatesToClient {@code false}
 * @param updateScheduledTask {@code false}
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CommandResource(int id, String name, String commandName, String message, Map<String, Object> body,
                              String priority, String status, String result, Instant queued, Instant started,
                              Instant ended, String trigger, Instant stateChangeTime, boolean sendUpdatesToClient,
                              boolean updateScheduledTask) {
}
