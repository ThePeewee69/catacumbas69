# Catasonarr

Catasonarr is a **TV series manager that speaks Sonarr's API v3** (orch.md decision 109): Prowlarr, qBittorrent,
Transmission, Bazarr, Overseerr, Jellyseerr and Homarr work with it as they do with Sonarr. It runs next to the
other apps on meloes, keeps its data in MongoDB (`catasonarr_*`) and publishes **everything it does** on one Pulsar
topic, `catacumbas69/catasonarr/events`, which Cataudit stores (and any other app may read).

**This module is GPL-3.0** ([`LICENSE`](LICENSE)): its release-name parser is translated from Sonarr's, which is
GPL-3.0. The rest of the repository keeps its own licence.

Engineering rules: [`../orch.md`](../orch.md). Decisions: orch.md 109 (the app), 112 (phase 1), 114 (browse for
folders) and 115 (import library, look and logo).

## Status

**Phase 1: series and episodes** (decision 112). Sonarr's clients and its own page add, edit, refresh and delete
series; the episodes, root folders, quality profiles, definitions, custom formats, tags and commands are there with
Sonarr 4's fields and ids. Searching and downloading come in phase 2: until then the search commands are accepted
and end *unsuccessful*.

| Phase | What |
|---|---|
| 0 (done) | Skeleton: module, login (Dex for the page, API key for the API v3), `system/status`, deployment |
| **1 (this)** | Series and episodes (TVmaze, TMDB optional), root folders, quality, tags, commands, the page, import of quality from `sonarr.db` |
| 2 | Indexers pushed by Prowlarr (Torznab), RSS sync, automatic and manual search, the release parser |
| 3 | qBittorrent and Transmission: send, follow the queue, import and rename into the media folder |
| 4 | Calendar, Wanted and Queue pages; Bazarr, Overseerr, Jellyseerr and Homarr; import of Sonarr's series |

## API v3

What phase 1 answers, with Sonarr 4's fields (Swagger UI at `/swagger-ui.html`):

| Path | What |
|---|---|
| `GET/POST /api/v3/series`, `GET/PUT/DELETE /api/v3/series/{id}`, `GET /api/v3/series/lookup?term=` | Series. `lookup` takes words, `tvdb:<id>` or `imdb:<id>`; `DELETE ?deleteFiles=true` deletes the series' folder (only inside a root folder) |
| `GET /api/v3/episode?seriesId=`, `GET/PUT /api/v3/episode/{id}`, `PUT /api/v3/episode/monitor` | Episodes and their monitoring |
| `/api/v3/rootfolder` | Root folders, with free space and folders not yet series |
| `GET /api/v3/filesystem?path=`, `GET /api/v3/filesystem/type?path=` | The folder browser behind **Browse…** (decision 114): the pod's whole file system, as Sonarr's; system folders (`#recycle`, `@eaDir`, …) and dot names hidden |
| `/api/v3/qualityprofile`, `/api/v3/qualitydefinition`, `/api/v3/customformat` | Quality (also their `schema`) |
| `/api/v3/tag`, `/api/v3/language`, `/api/v3/languageprofile` | Tags; languages (Sonarr's list; language profiles as Sonarr 4: one, deprecated) |
| `GET/POST /api/v3/command`, `GET /api/v3/command/{id}` | `RefreshSeries` runs; the searches and `RescanSeries` end *unsuccessful* until phase 2 |
| `POST /api/v3/catasonarr/import/sonarr-quality` | Catasonarr's own: the import from `sonarr.db` (below) |

**Series data**: [TVmaze](https://www.tvmaze.com/api) (CC BY-SA, credited in the page's footer), found by
TheTVDB's id as the clients send it. With a TMDB token, [TMDB](https://www.themoviedb.org) gives the pictures and
the summary in pt-PT. Every series is refreshed every 12 hours (only those TVmaze changed, or a week old).

**Import library** (the page's *Import library*, decision 115): as Sonarr's Library Import, the folders of a root
folder that are no series yet, matched in TVmaze by name and year (`The Office (2005)`) or by an id in the name
(`{tvdb-79168}`); check the matches, change the wrong ones, set monitoring and profile, and import. Nothing is
searched for when importing. **Refresh** reads the NAS again: what was imported leaves, new folders come in, and
the rows already there keep their choices.

**Import from Sonarr**: everything about quality and the folder naming, from the `sonarr.db` of a Sonarr backup
(System → Backup → Backup Now, then unzip), on the Mac:

```
bash microshift/apps/catacumbas69-apps/catasonarr/catasonarr-import-sonarr.sh ~/Downloads/sonarr.db
```

It asks for the API key without showing it. `CATASONARR_URL=http://localhost:8086` sends it to the Catasonarr in
IntelliJ instead (profile `no-auth`, no key).

## Two ways in

| Path | Login | Who |
|---|---|---|
| `/api/v3/**` with a key | Sonarr's API key, in `X-Api-Key` or `?apikey=` (the key of the Sonarr it replaces) | Prowlarr, Bazarr, Overseerr, Jellyseerr, Homarr, the import script |
| `/api/v3/**` without a key | The Dex session, with CSRF (`X-XSRF-TOKEN`) | The page |
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
| `CATASONARR_TMDB_TOKEN` | *(empty: TMDB off)* | TMDB's API Read Access Token; Secret `catasonarr-tmdb` (key `token`), written by `catasonarr-secret.sh tmdb` |
| `CATASONARR_TVMAZE_URL`, `CATASONARR_TMDB_URL` | `https://api.tvmaze.com`, `https://api.themoviedb.org/3` | The tests point TVmaze at a fake |
| `CATASONARR_METADATA_REFRESHINTERVAL`, `CATASONARR_METADATA_REFRESHINITIALDELAY` | `PT12H`, `PT10M` | The refresh of every series |
| `CATASONARR_COMMANDS_KEEP` | `7d` | How long finished commands are kept |
| `SPRING_DATA_MONGODB_URI` | the ReplicaSet in the cluster | Secret `catacumbas69-apps-mongodb` (key `uri`) |
| `PULSAR_SERVICE_URL`, `PULSAR_BROKER_URL`, `PULSAR_LISTENER_NAME` | the cluster's | As the other apps (§2.1) |
| `CATAPONTE_URL` | `http://cataponte:8082` | Where the `audit-catasonarr` bridge is registered |
| `SPRING_PROFILES_ACTIVE` | *(not set: login on)* | `no-auth` turns both logins off (IntelliJ, tests) |

## Run locally

Run `CatasonarrApplication` (port **8086**). IntelliJ *Environment variables* (replace `<user>` and `<password>`;
`.idea/` stays out of Git, CFG-2). `SPRING_PROFILES_ACTIVE=no-auth` turns off both logins, Dex for the page and the
API key for `/api/v3`; without it Catasonarr refuses to start for lack of the client secret and the key:

```
SPRING_PROFILES_ACTIVE=no-auth;PULSAR_SERVICE_URL=http://192.168.86.35:31080;PULSAR_BROKER_URL=pulsar://192.168.86.35:31650;PULSAR_LISTENER_NAME=external;SPRING_DATA_MONGODB_URI=mongodb://<user>:<password>@192.168.86.35:32017/catacumbas69?authSource=admin&directConnection=true;CATAPONTE_URL=http://localhost:8082
```

Start it after Catavigia and before Catapulsar: Cataponte → Cataudit → Catametricas → Catavigia → Catasonarr →
Catapulsar. It registers `audit-catasonarr` in Cataponte at start-up and retries while Cataponte is not up. It
creates `catacumbas69/catasonarr` and its `events` topic in the Pulsar it points at. Open <http://localhost:8086>;
`curl -s http://localhost:8086/api/v3/system/status` answers without a key. `/data` and `/downloads` exist only in
the pod: on the Mac, add a local folder as root folder (Settings) to try adding series. TVmaze needs no key; for
TMDB add `;CATASONARR_TMDB_TOKEN=<token>` (never in Git).

## Test

`mvn verify` in `apps/`: unit tests (TVmaze's mapping, monitoring rules, folder names, titles, default profiles),
`SecurityConfigTests` with both logins on, `ProvisioningIT` and `SeriesApiIT` against real Pulsar and MongoDB
(Testcontainers; `SeriesApiIT` adds a series the way Overseerr does against a fake TVmaze, and imports quality rows
from Sonarr). The page's HTML, CSS and JavaScript are linted and formatted as Catapulsar's (ESLint, Stylelint,
html-validate, Prettier; `npm run format` fixes the formatting).

## Deploy

`microshift/apps/catacumbas69-apps/catasonarr/` and the ArgoCD Application `catasonarr` (manual Sync, as the other
apps). One replica, 320 Mi / 640 Mi, with the OpenTelemetry agent. It runs as user and group 1000 (as Sonarr's
PUID/PGID) and mounts the NAS as Sonarr does: `/data` (`/volume1/Media`) and `/downloads` (`/volumeUSB4/usbshare`). Before the first Sync, see the one-time setup
in [`microshift/apps/catacumbas69-apps/README.md`](../../microshift/apps/catacumbas69-apps/README.md#catasonarr).

Catavigia watches its readiness: `service.down` (critical) opens when it is not `UP` or does not answer
(`CATASONARR_URL` in Catavigia, orch.md decision 111).
