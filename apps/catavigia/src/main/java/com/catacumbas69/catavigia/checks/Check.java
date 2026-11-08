package com.catacumbas69.catavigia.checks;

import java.util.List;
import java.util.Set;

/**
 * One check, run once per round as a task (orch.md decision 75). It reports every problem it sees now; the alert
 * engine compares that with the previous rounds (debounce) and opens or closes the alerts of its types.
 */
public interface Check {

    /**
     * Returns the check's name, used in task ids.
     *
     * @return e.g. {@code backlog}
     */
    String name();

    /**
     * Returns the alert types this check reports; an open alert of these types that the check no longer sees is
     * counted towards closing.
     *
     * @return the types
     */
    Set<String> types();

    /**
     * Looks at the platform now.
     *
     * @return the problems seen; empty when everything is fine
     * @throws SourceUnavailableException if the check could not read its source: the task is delivered again
     *         (orch.md decision 79), and the alerts stay as they are
     */
    List<Finding> run() throws SourceUnavailableException;
}
