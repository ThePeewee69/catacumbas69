# 6. One audit log for every configuration change

Date: 2026-10-30 · Status: Accepted

## Context

Every configuration change must be recorded in MongoDB. Pulsário audited bridge changes as they passed
through its BFF, so changes made directly on Passário's API, or by Passário itself (schema migration),
were missing, and the entries held only the bridge id, not what changed.

## Decision

- `audit_logs` is a shared, **append-only** collection: an explicit exception to ARCH-1. Pulsário and
  Passário insert entries with the same fields (`timestamp`, `user`, `action`, `topic`,
  `topicProperties`) plus `application` (`pulsario` | `passario`). Only Pulsário reads it and owns its
  indexes; nobody updates or deletes entries.
- Passário records every bridge change itself (`CREATE/UPDATE/DELETE/ENABLE/DISABLE/MIGRATE_BRIDGE`),
  with the definition `before` and `after`. Pulsário forwards the browser's address as
  `X-Forwarded-For` so the entry names the user; Pulsário no longer writes its own bridge entries.
- Pulsário records its own changes: topics, subscriptions, skip/clear backlog, produce, and runtime
  settings (`CHANGE_SETTING` with `from`/`to`, e.g. the Passário URL).
- An entry is written after the change is stored. If the audit insert fails the change stays applied and
  the full entry is logged at `ERROR` (Passário) or `WARN` (Pulsário's asynchronous writer).

## Consequences

- One query over `audit_logs` shows every change, whichever client or service made it.
- Both services depend on the entry shape; changing it needs both to change together (MONGO-2 applies).
- Without authentication the "user" is a client IP address; once an OAuth proxy is in place (SEC-2) the
  forwarded user name should replace it.
