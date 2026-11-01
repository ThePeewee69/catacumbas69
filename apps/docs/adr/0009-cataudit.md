# 9. Cataudit owns the audit trail, fed by Cataponte bridges

Date: 2026-10-31 · Status: Accepted (implemented 2026-10-31) · Supersedes: ADR 0006

## Context

Today Catapulsar and Cataponte insert into one shared `audit_logs` collection (ADR 0006). Every app must
know the collection's shape and be able to write to it.

## Decision

- A new service, **Cataudit**, owns the audit trail: it consumes `catacumbas69/cataudit/audit`, stores each
  event in its own collection (`cataudit_events`) with `_id` = CloudEvents `id`, and exposes a query API
  (`GET /api/v1/audit?app&type&subject&from&to&page`). Duplicates are ignored by the unique `_id`.
- Every app publishes all relevant actions to **its own** events topic (ADR 0008). Each app **registers its
  own audit bridge** in Cataponte when it starts: `audit-<app>`: `catacumbas69/<app>/events` →
  `catacumbas69/cataudit/audit`. Registration is an idempotent upsert by name, retried in the background
  until Cataponte answers, and never blocks start-up.
- Catapulsar's audit view reads Cataudit's API through the BFF.
- Migration: existing `audit_logs` entries are copied once into `cataudit_events` (keeping their time,
  user, action and details); `audit_logs` is then read-only and finally dropped.

## Consequences

- No service writes another service's collection; adding an app means adding its bridge, not changing
  Cataudit.
- Audit becomes **eventually consistent** and depends on Cataponte: if Cataponte is down, audit entries
  wait in each app's events topic and arrive when it is back. They are only lost if Cataponte stays down
  longer than that topic's retention, which must therefore be generous (≥ 7 days).
- The audit bridges are ordinary bridges: visible and controllable in the Bridges panel. Disabling one stops
  auditing that app, so they are marked as system bridges in the UI.
- Because events are only published after commit (ADR 0008), an action whose event could not be published
  is missing from the audit trail; the `ERROR` log with the full event is the fallback.

## Implementation (phase 2, 2026-10-31)

- Registration lives in catacommons (`AuditBridgeRegistrar`). It runs after start-up and is retried with
  back-off (10 s doubling to 5 min). It calls Cataponte's new
  **`PUT /api/v1/bridges/system/{name}`**, which creates or updates the bridge by name and does nothing when
  the definition already matches. Catametricas uses `CATAPONTE_URL`. Catapulsar uses its editable Cataponte
  URL. Cataponte registers in-process. Cataudit registers nothing.
- An audit bridge reads with subscription `cataponte-audit-<app>`, type `Failover`, ordered. It is stored
  with `system: true`. The UI shows a badge, and otherwise it is an ordinary bridge: it can be edited,
  disabled and deleted. Registering again keeps an enabled state a user changed. A deleted bridge comes back
  at the app's next start.
- Cataudit consumes with a `Shared` subscription `cataudit` and reads messages as bytes into the `CloudEvent`
  record. It inserts with `_id` = event id; a duplicate key counts as success. When MongoDB fails the message
  is negatively acknowledged and redelivered after 10 s. A message that is not a CloudEvent is logged at
  `ERROR` and acknowledged.
- `cataudit_events` keeps the event and also stores `app`, `entity` and `action` split out of the type, and
  `receivedAt`. Indexes: `time`, and `app`/`subject`/`actor`/`entity+action` each with `time`. Events are
  kept **forever**.
- API: `GET /api/v1/audit?app&entity&action&subject(prefix)&actor&from&to&page&size` returns newest first
  with the total. Also `GET /api/v1/audit/{id}` and `GET /api/v1/audit/facets`. Catapulsar forwards these
  at `/api/v1/audit/**` to its new audit view.
- `audit_logs` is copied at every start, with ids `legacy-<ObjectId>` and each old action mapped to today's
  event type. The collection is **kept**, read-only, until it is deleted by hand. Catapulsar and Cataponte no
  longer write it; that code is removed.

## No backward compatibility (2026-11-01)

The data in MongoDB was test data, so every migration was removed from the code and the old collections were
dropped by hand (orch.md decision 40). Cataudit no longer copies `audit_logs` and its events have no `legacy` flag;
the `legacy-*` entries already copied were deleted with `audit_logs`.
