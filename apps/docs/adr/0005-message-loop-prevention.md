# 5. Message loops between bridges are blocked in the configuration

Date: 2026-10-30 · Status: Accepted

## Context

Bridges form a directed graph: each one is an edge from its source topic to each destination topic. A
cycle (A→B→A, or A→B→C→A) would relay the same messages forever, multiplying them on every fan-out,
until topics fill up. A runtime guard (hop counter in a message property, dead-letter topic) was
considered; it lets a loop run until the limit is hit and adds a property to every relayed message.

## Decision

Loops are refused before they exist:

- `BridgeService` rejects a create, update or enable that would close a cycle among **enabled** bridges
  with `400` (`field: destinations`), naming the loop: `in → out ('a') → in ('b')`. The check and the
  save run under one lock, so two concurrent requests cannot close a loop together.
- `BridgeManager` checks the enabled bridges on every reconcile and does not run any bridge on a cycle
  (state `BLOCKED`), stopping it if it was running. This covers documents written to MongoDB directly.
- Partitions count as their topic (`orders-partition-3` = `orders`). Disabled bridges are ignored, so a
  loop can be prepared disabled and is refused only when the last edge is enabled.

## Consequences

- No message carries extra properties; destination consumers see messages unchanged.
- Loops that pass through something outside Cataponte (another relay, Pulsar geo-replication, a consumer
  that re-publishes) are not detected.
- A blocked bridge is visible in `/api/v1/bridges/status`, in the health details (`blocked`) and in the
  Catapulsar UI ("blocked: message loop").
