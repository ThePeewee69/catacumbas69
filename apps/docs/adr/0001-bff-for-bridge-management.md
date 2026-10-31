# 1. Catapulsar proxies bridge management (backend-for-frontend)

Date: 2026-10-30 · Status: Accepted

## Context

Catapulsar's browser UI manages bridges that live in Cataponte. Calling Cataponte directly from the browser
needs CORS, exposes Cataponte outside the cluster, and bypasses Catapulsar's audit log.

## Decision

The browser talks only to Catapulsar. Catapulsar exposes `/api/v1/bridges/**` and forwards each call to
Cataponte's REST API server-side, recording an audit entry for every mutation. Cataponte is a `ClusterIP`
service with CORS disabled.

## Consequences

- One origin, one place for authentication and audit.
- Catapulsar must map Cataponte failures to `502`/`503` Problem Details and keep the rest of the UI working.
- Cataponte's CORS is closed by default; `CATAPONTE_CORS_ALLOWED_ORIGINS` exists only for local experiments.
- Catapulsar needs `CATAPONTE_URL` (in the cluster: `http://cataponte:8082`).
