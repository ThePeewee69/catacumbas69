# 11. Shared settings live in Catapulsar and are published on a compacted topic

Date: 2026-10-31 · Status: Proposed

## Context

Settings are edited in Catapulsar's UI (refresh interval, Cataponte URL, collection interval…). Other
services need some of them, and every replica of every service must see a change, also replicas that start
after it. Today Catametricas reads Catapulsar's collection, and Catapulsar keeps the Cataponte URL in each
replica's memory, so other replicas miss changes.

## Decision

- **Catapulsar owns all shared settings** in its collection and remains the only place to edit them (REST,
  audited through its events).
- On every change, and once at start-up for every setting, Catapulsar publishes the value to
  `catacumbas69/catapulsar/settings` with **message key = setting id** (e.g. `catametricas.collect-interval-seconds`).
  The topic has **compaction** enabled, so it always holds the latest value per key.
- Every service (and every Catapulsar replica) reads the topic **from the earliest compacted message** with a
  Pulsar `Reader` at start-up, keeps the values in memory, and keeps reading for changes. A missing key
  means "use the configured default".
- A `SettingChanged` event is also published on `catacumbas69/catapulsar/events` for the audit trail.

## Consequences

- Services start with the right values even when Catapulsar is down, and all replicas converge within
  seconds of a change.
- The settings topic is a public contract: keys and value formats are documented in `orch.md`; values are
  JSON (`{"value": 60}`), so new fields can be added.
- A value written directly to MongoDB is not seen by other services until Catapulsar republishes it (at its
  next start-up); settings must be changed through the API.
