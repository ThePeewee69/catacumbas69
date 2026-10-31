# 6. One audit log for every configuration change

Date: 2026-10-30 · Status: Accepted

## Context

Every configuration change must be recorded in MongoDB. Catapulsar audited bridge changes as they passed
through its BFF, so changes made directly on Cataponte's API, or by Cataponte itself (schema migration),
were missing, and the entries held only the bridge id, not what changed.

## Decision

- `audit_logs` is a shared, **append-only** collection: an explicit exception to ARCH-1. Catapulsar and
  Cataponte insert entries with the same fields (`timestamp`, `user`, `action`, `topic`,
  `topicProperties`) plus `application` (`pulsario` | `passario`, the apps' names when the log started; now Catapulsar and
  Cataponte). Only Catapulsar reads it and owns its
  indexes; nobody updates or deletes entries.
- Cataponte records every bridge change itself (`CREATE/UPDATE/DELETE/ENABLE/DISABLE/MIGRATE_BRIDGE`),
  with the definition `before` and `after`. Catapulsar forwards the browser's address as
  `X-Forwarded-For` so the entry names the user; Catapulsar no longer writes its own bridge entries.
- Catapulsar records its own changes: topics, subscriptions, skip/clear backlog, produce, and runtime
  settings (`CHANGE_SETTING` with `from`/`to`, e.g. the Cataponte URL).
- An entry is written after the change is stored. If the audit insert fails the change stays applied and
  the full entry is logged at `ERROR` (Cataponte) or `WARN` (Catapulsar's asynchronous writer).

## Consequences

- One query over `audit_logs` shows every change, whichever client or service made it.
- Both services depend on the entry shape; changing it needs both to change together (MONGO-2 applies).
- Without authentication the "user" is a client IP address; once an OAuth proxy is in place (SEC-2) the
  forwarded user name should replace it.
