# 14. Bridge areas: named groups of bridges, each limited to the topics its glob patterns allow

Date: 2026-11-04 · Status: Accepted (2026-11-04)

## Context

Catapulsar's Bridges panel shows every bridge in one list, so the system bridges that the apps register at
start-up (`audit-<app>`, ADR 0009) are mixed with the user's own. As more bridges are added for different
purposes (home media, sensors, tests), one list does not say which bridge belongs to what, and nothing stops a
bridge of one purpose from reading or writing topics of another.

## Decision

### Areas

- An **area** is a named group of bridges with a **filter**: a list of glob patterns over topic names. Cataponte
  owns areas, as it owns bridges (ADR 0010). They are stored in MongoDB collection `cataponte_areas`:
  `id`, `name` (unique, same rules as a bridge name), `description`, `patterns` (0–20), `version` (RES-9),
  `createdAt`, `updatedAt`.
- A bridge belongs to **at most one** area through an explicit field, `areaId`. A bridge without one is in the
  general **Bridges** group. System bridges never have an area; they are always in **System**.

### Patterns

- Written **without** `persistent://`: `tenant/namespace/topic`. A pattern that starts with `non-persistent://`
  matches only non-persistent topics; any other pattern matches only persistent ones.
- `*` matches any characters **within one segment** (no `/`); `**` matches any characters **across segments**.
  Examples: `home/media/*` (every topic of `home/media`), `home/**` (everything in `home`), `*/*/events`
  (every topic called `events`), `home/media/events*` (`events` and its partitions `events-partition-N`).
- Allowed characters: those of Pulsar names plus `*` and `/`. A pattern must have three segments unless it uses
  `**`. Invalid patterns are refused with `400`.
- **No patterns** means the area allows every topic.

### Rules

- **Creating or editing a bridge in an area**: its source **and every destination** must match at least one
  pattern of the area; otherwise `400`, naming each topic that does not fit. The same holds when a bridge is
  moved to another area (the edit form has an area selector).
- **Changing an area's patterns** is always accepted. Bridges of the area whose topics no longer match keep
  running and are marked **outside the filter** (`outsideArea: true` and `outsideTopics` in the bridge response,
  computed on read).
  They must fit again the next time they are edited.
- **Deleting an area that has bridges** needs a choice, `DELETE /api/v1/areas/{id}?bridges=`:
  - `delete`: delete the area and all its bridges;
  - `move&to=<areaId>`: move them to another area (bridges that do not fit its patterns are moved and marked
    outside the filter, as above);
  - `move` without `to`: move them to the general Bridges group.

  Without `bridges`, an area that still has bridges answers `409` with how many it has. An empty area is just
  deleted.
- Areas change **definitions only**: workers, subscriptions and the reconciliation (ADR 0004) are unaffected, so
  N replicas need nothing new (ADR 0012).

### API (Cataponte, proxied by Catapulsar under the same paths)

| Method | Path | Description |
|---|---|---|
| `GET` | `/api/v1/areas` | Every area, with the number of bridges in each |
| `POST` | `/api/v1/areas` | Create `{name, description?, patterns}` (`201`) |
| `PUT` | `/api/v1/areas/{id}` | Replace, with `version` (RES-9) |
| `DELETE` | `/api/v1/areas/{id}?bridges=delete\|move&to=` | Delete (`204`); see the rules above |

`BridgeRequest` gains `areaId` (optional; must exist, else `400`); `BridgeResponse` gains `areaId`,
`outsideArea` and `outsideTopics`. Catapulsar forwards `/api/v1/areas/**` like the bridges and publishes
`area.<action>-requested` for each forwarded change. `PUT /api/v1/bridges/system/{name}` ignores `areaId`, and editing a system bridge with an `areaId` is refused
(`400`).

### Events and audit (ADR 0008, ADR 0009)

- **Every area action is audited in Cataudit**, like every bridge action: Cataponte publishes
  `com.catacumbas69.cataponte.area.created|updated|deleted.v1` on `catacumbas69/cataponte/events`, with the
  name, description and patterns before and after, and the actor from `X-Forwarded-For`. The existing
  `audit-cataponte` system bridge copies them to `cataudit/audit`, so they appear in Catapulsar's Audit panel
  (entity `area`) with no change to Cataudit.
- Moving a bridge to another area is a `bridge.updated`. Deleting an area with `bridges=delete` publishes one
  `bridge.deleted` per bridge and then `area.deleted`; with `move`, one `bridge.updated` per bridge.

### Catapulsar UI

- Tabs in the Bridges panel, in this order: **System**, **Bridges**, then one tab per area, then **+ Area**.
  Each tab shows its count. The panel opens on the **last tab used** (remembered in the browser; a per-viewer
  convenience, not an action, so not audited), **System** the first time or when that area no longer exists.
- The header's *running* count is the open tab's. **+ New Bridge** is hidden on System; in an area's tab it
  creates the bridge in that area, and the topic pickers show only the topics its patterns match.
- An area's tab shows its patterns and ✎ (edit name, description and patterns, with a preview of the topics they
  match) and Delete (asks: delete its bridges, or move them to Bridges or another area).
- System bridges keep Edit, Delete and Disable, as today.
- Bridges outside their area's filter get an orange *outside filter* tag naming the topics that no longer match.

## Consequences

- The filter is enforced by Cataponte, so it also holds for bridges created through the API, not only the UI.
- An area is an organising and guarding tool, not a permission: anyone who can use Catapulsar can edit an area's
  patterns or move a bridge out of it (Catapulsar has no users yet, orch.md SEC rules).
- Patterns are matched against names, not against what exists: a pattern may match topics created later, and
  matching nothing is allowed.
- Deleting an area with `bridges=delete` deletes bridges in bulk; the UI confirms it, and every deletion is
  audited.

## Review (2026-11-04)

1. The panel opens on the last tab used; System by default.
2. A system bridge is fixed in System: `areaId` is refused (`400`) on a system bridge.
3. This is ADR 0014; the phase-7 ADR (dead letters and Catavigia, orch.md decisions 42–49) becomes **ADR 0015**.
4. Every area action goes to Cataudit (see *Events and audit*).
