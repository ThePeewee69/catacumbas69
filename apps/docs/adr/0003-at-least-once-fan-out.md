# 3. At-least-once fan-out with per-bridge ordering

Date: 2026-10-30 · Status: Accepted

## Context

A bridge copies each source message to 1..n destinations. Destinations can fail independently. Pulsar
transactions would allow exactly-once but add latency and broker requirements.

## Decision

- Acknowledge a source message only after every destination confirmed it.
- `ordered` bridges (Exclusive/Failover/Key_Shared): on any failure stop acknowledging, wait for in-flight
  sends, then `redeliverUnacknowledgedMessages()` so the stream is replayed in order.
- Unordered bridges (any subscription, typically Shared): negatively acknowledge the failed message only.
- Sends are pipelined up to `maxInFlight` per bridge (target ≥ 1,000 msg/s).

## Consequences

- No message loss; duplicates are possible, so destination consumers must be idempotent.
- In ordered mode a message sent in the instant between a producer failure and the pause can overtake the
  replay — accepted as a rare edge case.
