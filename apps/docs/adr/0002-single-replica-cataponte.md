# 2. Cataponte runs a single replica

Date: 2026-10-30 · Status: Superseded by ADR 0012 (phase 5, 2026-11-01)

## Context

Every Cataponte instance reconciles every enabled bridge. With two replicas, each would open its own
consumer on the same subscription: `Exclusive` subscriptions fail, `Failover` makes one idle, and `Shared`
splits traffic unpredictably while doubling producers.

## Decision

Deploy exactly one replica with `strategy: Recreate`. Throughput comes from pipelining inside a bridge,
not from more pods.

## Consequences

- Restarts cause a short gap (seconds); unacknowledged messages are redelivered, nothing is lost.
- Horizontal scaling needs leader election or bridge sharding first — a future ADR.

## Superseded (2026-11-01)

Cataponte now runs any number of replicas (ADR 0012, orch.md ARCH-5). Every replica runs every enabled bridge and
Pulsar spreads the messages through the subscription type; `Exclusive`, the one type that cannot have several
consumers, is no longer allowed and existing bridges were moved to `Failover`.
