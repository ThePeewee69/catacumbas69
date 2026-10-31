# 4. Bridges are desired state, reconciled on an interval

Date: 2026-10-30 · Status: Accepted

## Context

Bridges can be changed through the API or directly in MongoDB. Starting and stopping workers from API
handlers would create two code paths and lose changes made outside the API.

## Decision

The `bridges` collection is the desired state. API calls only change documents. `BridgeManager` reads the
collection every `cataponte.bridge.reconcile-interval` (5 s) and starts, stops or restarts workers; a change
is detected through the document `version`.

## Consequences

- Changes take effect within one interval.
- If MongoDB is unreachable the manager keeps the current workers running.
- Restarting a worker waits for the old one to release its subscription before starting the new one.
