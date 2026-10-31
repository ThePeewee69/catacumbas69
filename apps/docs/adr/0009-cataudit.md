# 9. Cataudit owns the audit trail, fed by Cataponte bridges

Date: 2026-10-31 · Status: Proposed · Supersedes: ADR 0006 (once implemented)

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
