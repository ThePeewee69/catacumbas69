# 10. Each service owns its collections and exposes them through its API

Date: 2026-10-31 · Status: Proposed · Supersedes: the cross-service reads of ADR 0007 (once implemented)

## Context

ADR 0007 lets Catapulsar read Catametricas' collections and Catametricas read Catapulsar's settings. Both
are explicit exceptions to ARCH-1 and couple the services through document shapes.

## Decision

- All services keep using the database `catacumbas69`. **Every collection has exactly one owner**, and a
  service MUST NOT read or write collections it does not own:

  | Owner | Collections (current names) |
  |---|---|
  | Catapulsar | `pulsario_settings` |
  | Cataponte | `bridges`, `bridge_traffic_minutes`, `bridge_traffic_totals` |
  | Catametricas | `contario_topic_snapshots`, `contario_topic_minutes`, `contario_topic_hours`, `contario_topic_totals`, `contario_status`, `contario_locks`, `catametricas_task_runs` (new) |
  | Cataudit | `cataudit_events` (new); `audit_logs` until migrated (ADR 0009) |

  **All collections are renamed to their owner's prefix** in a one-off migration run by each owner at
  start-up (copy → verify counts → switch → drop the old one; idempotent, so a restart halfway is safe):
  `pulsario_settings` → `catapulsar_settings`; `bridges`, `bridge_traffic_*` → `cataponte_bridges`,
  `cataponte_traffic_*`; `contario_*` → `catametricas_*`; `audit_logs` → `cataudit_events` (ADR 0009).
  The audit `application` values keep the old app names only in migrated entries. Each migration ships with
  its owner's phase (decided 2026-10-31): `audit_logs` in phase 2, `pulsario_settings` in phase 3,
  `contario_*` in phase 4, `bridges`/`bridge_traffic_*` in phase 5 (orch.md §15).
- **Catametricas exposes a query API** (`GET /api/v1/metrics/overview`, `/tenants/{t}`, `/namespaces/{t}/{ns}`,
  `/topics/{topic}` with `period`), returning the overview rows Catapulsar shows today. Catapulsar calls it
  through its BFF (as it does for Cataponte) and no longer reads MongoDB for statistics.
- Catametricas no longer reads Catapulsar's settings collection; it gets its interval from the settings
  topic (ADR 0011).
- Each service's MongoDB user is still shared at first; separate users with access to their own prefix only
  can follow without code changes.

## Consequences

- ARCH-1 has no exceptions left. Collections can change shape freely behind each service's API.
- Catapulsar depends on Catametricas being up to show statistics; it degrades like the Bridges panel does
  when Cataponte is down (503 for that panel only).
- One more network hop for the overviews; the response is small (one row per tenant/namespace/topic).
