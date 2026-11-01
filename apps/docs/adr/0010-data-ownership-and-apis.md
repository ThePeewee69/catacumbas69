# 10. Each service owns its collections and exposes them through its API

Date: 2026-10-31 · Status: Accepted (phases 2–5, 2026-10-31 – 2026-11-01; migrations dropped, see below) ·
Supersedes: the cross-service reads of ADR 0007

## Context

ADR 0007 lets Catapulsar read Catametricas' collections and Catametricas read Catapulsar's settings. Both
are explicit exceptions to ARCH-1 and couple the services through document shapes.

## Decision

- All services keep using the database `catacumbas69`. **Every collection has exactly one owner**, and a
  service MUST NOT read or write collections it does not own:

  | Owner | Collections (current names) |
  |---|---|
  | Catapulsar | `catapulsar_settings` |
  | Cataponte | `cataponte_bridges`, `cataponte_traffic_minutes`, `cataponte_traffic_totals` (were `bridges`, `bridge_traffic_*`), `cataponte_worker_status` (new) |
  | Catametricas | `catametricas_topic_snapshots`, `catametricas_topic_minutes`, `catametricas_topic_hours`, `catametricas_topic_totals`, `catametricas_task_runs` (were `contario_*`; `contario_status` and `contario_locks` dropped) |
  | Cataudit | `cataudit_events` |

  **All collections are renamed to their owner's prefix** in a one-off migration run by each owner at
  start-up (copy → verify counts → switch → drop the old one; idempotent, so a restart halfway is safe):
  `pulsario_settings` → `catapulsar_settings`; `bridges`, `bridge_traffic_*` → `cataponte_bridges`,
  `cataponte_traffic_*`; `contario_*` → `catametricas_*`; `audit_logs` → `cataudit_events` (ADR 0009).
  The audit `application` values keep the old app names only in migrated entries. Each migration ships with
  its owner's phase (decided 2026-10-31): `audit_logs` in phase 2 (copied, kept), `pulsario_settings` in phase 3 (copied, dropped;
  done 2026-11-01),
  `contario_*` in phase 4 (renamed with `renameCollection` instead of copied, indexes kept; done 2026-11-01),
  `bridges`/`bridge_traffic_*` in phase 5 (copied with a server-side `$merge`, counted, dropped; done 2026-11-01)
  (orch.md §15).
- **Catametricas exposes a query API**, which Catapulsar calls instead of reading MongoDB for statistics. As
  built in phase 4 it is generic (decided 2026-11-01): `GET /api/v1/topics`, `GET /api/v1/series` and
  `GET /api/v1/collector` (ADR 0013); Catapulsar keeps building the overview rows from them.
- Catametricas no longer reads Catapulsar's settings collection; it gets its interval from the settings
  topic (ADR 0011).
- Each service's MongoDB user is still shared at first; separate users with access to their own prefix only
  can follow without code changes.

## Consequences

- ARCH-1 has no exceptions left. Collections can change shape freely behind each service's API.
- Catapulsar depends on Catametricas being up to show statistics; it degrades like the Bridges panel does
  when Cataponte is down (503 for that panel only).
- One more network hop for the overviews; the response is small (one row per tenant/namespace/topic).

## No backward compatibility (2026-11-01)

The data in MongoDB was test data, so every migration was removed from the code and the old collections were
dropped by hand (orch.md decision 40). The ownership table above is the final state; the renames and copies
described here are history.
