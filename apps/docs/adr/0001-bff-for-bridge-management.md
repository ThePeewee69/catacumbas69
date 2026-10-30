# 1. Pulsário proxies bridge management (backend-for-frontend)

Date: 2026-10-30 · Status: Accepted

## Context

Pulsário's browser UI manages bridges that live in Passário. Calling Passário directly from the browser
needs CORS, exposes Passário outside the cluster, and bypasses Pulsário's audit log.

## Decision

The browser talks only to Pulsário. Pulsário exposes `/api/v1/bridges/**` and forwards each call to
Passário's REST API server-side, recording an audit entry for every mutation. Passário is a `ClusterIP`
service with CORS disabled.

## Consequences

- One origin, one place for authentication and audit.
- Pulsário must map Passário failures to `502`/`503` Problem Details and keep the rest of the UI working.
- Until the proxy exists, Passário allows CORS from the Pulsário origin configured in
  `PASSARIO_CORS_ALLOWED_ORIGINS` (known gap G-9).
