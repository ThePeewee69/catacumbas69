# Catasonarr

Catasonarr is a **TV series manager that speaks Sonarr's API v3** (orch.md decision 109): Prowlarr, qBittorrent,
Transmission, Bazarr, Overseerr, Jellyseerr and Homarr work with it as they do with Sonarr. It runs next to the
other apps on meloes, keeps its data in MongoDB (`catasonarr_*`) and publishes **everything it does** on one Pulsar
topic, `catacumbas69/catasonarr/events`, which Cataudit stores (and any other app may read).

**This module is GPL-3.0** ([`LICENSE`](LICENSE)): its release-name parser is translated from Sonarr's, which is
GPL-3.0. The rest of the repository keeps its own licence.

Engineering rules: [`../orch.md`](../orch.md). Decision: orch.md decision 109.

## Status

**Skeleton.** It starts, provisions its namespace and events topic, registers its `audit-catasonarr` bridge,
answers Sonarr's connection test (`GET /api/v3/system/status`) and shows a page with the signed-in user. The
series, downloads, import and the other pages come in the next phases:

| Phase | What |
|---|---|
| 1 (this) | Skeleton: module, login (Dex for the page, API key for the API v3), `system/status`, deployment |
| next | Series and episodes (TheTVDB and TMDB), root folders, quality profiles |
| | Indexers pushed by Prowlarr (Torznab), RSS sync, automatic and manual search, the release parser |
| | qBittorrent and Transmission: send, follow the queue, import and rename into the media folder (NFS) |
| | Calendar, Wanted and Queue pages; Bazarr, Overseerr, Jellyseerr and Homarr; import of Sonarr's database |

## Two ways in

| Path | Login | Who |
|---|---|---|
| `/api/v3/**` | Sonarr's API key, in `X-Api-Key` or `?apikey=` (the key of the Sonarr it replaces) | Prowlarr, Bazarr, Overseerr, Jellyseerr, Homarr |
| everything else | Dex (`https://auth.catacumbas69.com`), as Catapulsar (SEC-2): `/api/**` answers `401`, pages go to Dex | The browser |
| `/actuator/health/**`, `/actuator/prometheus`, `/swagger-ui.html`, `/v3/api-docs` | none | Probes, Prometheus, API docs |

`GET /api/v3/system/status` answers with Sonarr's field names, `appName` `Catasonarr` and, as `version`, the
version of the Sonarr it replaces (`CATASONARR_SONARR_VERSION`, default `4.0.20.0`), so the clients' version
checks pass.

## Configuration

| Variable | Default | |
|---|---|---|
| `CATASONARR_AUTH_API_KEY` | *(empty: refuses to start with login)* | Secret `catasonarr-api-key` (key `api-key`), written by `catasonarr-secret.sh` |
| `CATASONARR_AUTH_CLIENT_SECRET` | *(empty: refuses to start with login)* | Secret `catasonarr-oidc` (key `client-secret`), written by `dex-secret.sh` |
| `CATASONARR_AUTH_ISSUER`, `CATASONARR_AUTH_CLIENT_ID` | `https://auth.catacumbas69.com`, `catasonarr` | Dex |
| `CATASONARR_INSTANCE_NAME`, `CATASONARR_SONARR_VERSION` | `Catasonarr`, `4.0.20.0` | What `system/status` says |
| `SPRING_DATA_MONGODB_URI` | the ReplicaSet in the cluster | Secret `catacumbas69-apps-mongodb` (key `uri`) |
| `PULSAR_SERVICE_URL`, `PULSAR_BROKER_URL`, `PULSAR_LISTENER_NAME` | the cluster's | As the other apps (§2.1) |
| `CATAPONTE_URL` | `http://cataponte:8082` | Where the `audit-catasonarr` bridge is registered |
| `SPRING_PROFILES_ACTIVE` | *(not set: login on)* | `no-auth` turns both logins off (IntelliJ, tests) |

## Run locally

From IntelliJ on the Mac, as the other apps: `SPRING_PROFILES_ACTIVE=no-auth`, the NodePort URLs of Pulsar
(`PULSAR_LISTENER_NAME=external`) and MongoDB (`directConnection=true`). Port **8086**.

## Test

`mvn verify` in `apps/`: unit tests, `SecurityConfigTests` with both logins on, and `ProvisioningIT` against real
Pulsar and MongoDB (Testcontainers). The page's HTML, CSS and JavaScript are not linted yet (gap G-22).

## Deploy

`microshift/apps/catacumbas69-apps/catasonarr/` and the ArgoCD Application `catasonarr` (manual Sync, as the other
apps). One replica, 320 Mi / 640 Mi, with the OpenTelemetry agent. Before the first Sync, see the one-time setup
in [`microshift/apps/catacumbas69-apps/README.md`](../../microshift/apps/catacumbas69-apps/README.md#catasonarr).
