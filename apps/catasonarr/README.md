# Catasonarr

Catasonarr is a **TV series manager that speaks Sonarr's API v3** (orch.md decision 109): Prowlarr, qBittorrent,
Transmission, Bazarr, Overseerr, Jellyseerr and Homarr work with it as they do with Sonarr. It runs next to the
other apps on meloes, keeps its data in MongoDB (`catasonarr_*`) and publishes **everything it does** on one Pulsar
topic, `catacumbas69/catasonarr/events`, which Cataudit stores (and any other app may read).

**This module is GPL-3.0** ([`LICENSE`](LICENSE)): its release-name parser is translated from Sonarr's, which is
GPL-3.0. The rest of the repository keeps its own licence.

Engineering rules: [`../orch.md`](../orch.md). Decisions: orch.md 109 (the app), 112 (phase 1), 114 (browse for
folders), 115 (import library, look and logo), 117 to 119 (paths in any case, live pages, connections), 120 (the
import from the backup's zip), 121 (tooltips) and 122 (phases 2 and 3: search, download, import).

## Status

**Phases 2 and 3: search, download and import** (decision 122), on top of phase 1's series and episodes (decision
112). Catasonarr reads its indexers (Torznab, pushed by Prowlarr) every 15 minutes and on searches, judges each
release with Sonarr's rules, sends the best to qBittorrent or Transmission in **its own category, `catasonarr`**,
follows the queue, imports finished downloads (moved and renamed as Sonarr names them, with subtitles and extras,
the torrent then removed), blocklists and searches again after a failure, rescans the series' folders, keeps
replaced files 7 days in `/data/.recycle` and tells Plex.

| Phase | What |
|---|---|
| 0 (done) | Skeleton: module, login (Dex for the page, API key for the API v3), `system/status`, deployment |
| 1 (done) | Series and episodes (TVmaze, TMDB optional), root folders, quality, tags, commands, the page, import of quality from a Sonarr backup |
| **2 and 3 (this)** | All of Sonarr's parser with its tests, Torznab indexers, RSS sync, automatic and interactive search, decisions, qBittorrent and Transmission, the queue, import and rename (ffprobe), rescan, recycle bin, Plex, history and blocklist, Activity and the settings' sections |
| 4 | Calendar and Wanted pages; Bazarr, Overseerr, Jellyseerr and Homarr checked end to end; import of Sonarr's series and files |

**How it lives next to Sonarr**: both can run at once against the same clients. Catasonarr only adds, reads and
removes the torrents of the category `catasonarr`; Sonarr keeps its own. Catasonarr reaches Prowlarr and
qBittorrent on K3s by name over http (`http://prowlarr.catacumbas69.com`, `http://qbittorrent.catacumbas69.com`).
In Prowlarr add Catasonarr as a *Sonarr* app (its address and the API key); Prowlarr reaching Catasonarr's https
Route may need its certificate validation off, or our CA.

## API v3

What it answers, with Sonarr 4's fields (Swagger UI at `/swagger-ui.html`):

| Path | What |
|---|---|
| `GET/POST /api/v3/series`, `GET/PUT/DELETE /api/v3/series/{id}`, `GET /api/v3/series/lookup?term=` | Series. `lookup` takes words, `tvdb:<id>` or `imdb:<id>`; `DELETE ?deleteFiles=true` deletes the series' folder (only inside a root folder) |
| `GET /api/v3/episode?seriesId=`, `GET/PUT /api/v3/episode/{id}`, `PUT /api/v3/episode/monitor` | Episodes and their monitoring |
| `/api/v3/rootfolder` | Root folders, with free space and folders not yet series |
| `GET /api/v3/filesystem?path=`, `GET /api/v3/filesystem/type?path=` | The folder browser behind **Browse…** (decision 114): the pod's whole file system, as Sonarr's; system folders (`#recycle`, `@eaDir`, …) and dot names hidden |
| `/api/v3/qualityprofile`, `/api/v3/qualitydefinition`, `/api/v3/customformat` | Quality (also their `schema`) |
| `/api/v3/tag`, `/api/v3/language`, `/api/v3/languageprofile` | Tags; languages (Sonarr's list; language profiles as Sonarr 4: one, deprecated) |
| `/api/v3/indexer` (+ `schema`, `test`, `testall`, `?forceSave=true`) | Torznab indexers; Prowlarr adds and updates them. The API key shows as `********` |
| `/api/v3/downloadclient` (+ `schema`, `test`, `testall`) | qBittorrent and Transmission, each with the category `catasonarr`; passwords show as `********` |
| `/api/v3/releaseprofile`, `/api/v3/delayprofile` (+ `PUT reorder/{id}?after=`), `/api/v3/remotepathmapping` | Release and delay profiles, remote path mappings |
| `GET /api/v3/release?episodeId=` or `?seriesId=&seasonNumber=`, `POST /api/v3/release` | The interactive search: every release with its decision and rejections; grab one (kept 30 min) |
| `GET /api/v3/queue`, `/queue/details`, `/queue/status`; `DELETE /queue/{id}`, `/queue/bulk`; `POST /queue/grab/{id}`, `/queue/grab/bulk` | The queue: one item per episode of each download in `catasonarr`, and the releases held back; remove (`removeFromClient`, `blocklist`, `skipRedownload`) or grab a held-back one now |
| `GET /api/v3/history` (paged), `/history/since`, `/history/series`, `POST /history/failed/{id}` | Grabs, imports, failures, deleted files; mark a grab as failed |
| `GET /api/v3/blocklist` (paged), `DELETE /blocklist/{id}`, `/blocklist/bulk` | Releases never grabbed again |
| `GET /api/v3/episodefile?seriesId=`, `GET/DELETE /api/v3/episodefile/{id}`, `DELETE /episodefile/bulk` | The episodes' files with quality, custom formats and MediaInfo; delete sends to the recycle bin |
| `/api/v3/config/naming`, `/config/mediamanagement`, `/config/indexer`, `/config/downloadclient` | Sonarr's settings pages, each a view of Catasonarr's settings |
| `GET/POST /api/v3/command`, `GET /api/v3/command/{id}` | `RefreshSeries` (then a rescan), `RescanSeries`, `SeriesSearch`, `SeasonSearch`, `EpisodeSearch`, `MissingEpisodeSearch`, `CutoffUnmetEpisodeSearch`, `RssSync`, `RefreshMonitoredDownloads` |
| `GET/PUT /api/v3/catasonarr/settings`, `POST /api/v3/catasonarr/plex/test` | Catasonarr's own: every setting (Plex included, its token never) and the Plex test |
| `POST /api/v3/catasonarr/import/sonarr` | Catasonarr's own: the import from a Sonarr backup (below) |
| `GET /api/v1/connections`, `GET /api/v1/connections/pulsar/health`, `GET /api/v1/connections/mongodb/health` | Catasonarr's own, for Settings → Connections (decision 119): Pulsar's addresses and MongoDB's connection without its password, and a health check of each; Dex session only |

**Series data**: [TVmaze](https://www.tvmaze.com/api) (CC BY-SA, credited in the page's footer), found by
TheTVDB's id as the clients send it. With a TMDB token, [TMDB](https://www.themoviedb.org) gives the pictures and
the summary in pt-PT. Every series is refreshed every 12 hours (only those TVmaze changed, or a week old).

**Live pages** (decision 118): Series, a series' detail, Import library and Settings update themselves when
something changes, here, through a client (Seerr) or by Catasonarr itself, and a notice says what changed (those of
the same 2 s together). Catasonarr's events reach the pages through the system bridge `pages-catasonarr`, the topic
`catacumbas69/catasonarr/pages` (message TTL 1 h), the subscription `catasonarr-pages-<pod>` of each replica (in
Catapulsar's Consumers panel, `pages`) and the WebSocket `/api/v1/updates`.

**Tooltips** (decision 121): every button, button-like link, tick box and menu says what it does, half a second
after the mouse rests on it or when the keyboard reaches it. A new control gets one with `tip` in `el()` (or
`data-tip` in `index.html`).

**Import library** (the page's *Import library*, decision 115): as Sonarr's Library Import, the folders of a root
folder that are no series yet, matched in TVmaze by name and year (`The Office (2005)`) or by an id in the name
(`{tvdb-79168}`); check the matches, change the wrong ones, set monitoring and profile, and import. Nothing is
searched for when importing. **Refresh** reads the NAS again: what was imported leaves, new folders come in, and
the rows already there keep their choices.

**Activity** (decision 122): the queue (progress, state and why an import waits; remove, blocklist, grab a held-back
release now), the history (a page at a time, by event; mark a grab as failed) and the blocklist. A series' page
shows each episode's file (quality, size, below the cutoff) with *Search* and *Interactive…* per episode and per
season, *Search monitored* and *Rescan*. The interactive search lists every release found, best first, with its
score and why it was rejected; any can be grabbed. **Settings** are in sections: General, Indexers (and RSS),
Download clients (and finished or failed downloads), Profiles, Media management (episode naming, recycle bin,
extras) and Plex.

**Import of downloads**: a finished torrent in `catasonarr` (its path as Catasonarr sees it, through the remote path
mappings) has each video file matched to its episodes and read with ffprobe; Sonarr's import rules decide (not a
sample, enough free space, an upgrade, not imported before, an episode title when needed). Accepted files are moved
into the series' folder under Sonarr's name, the old file to the recycle bin; then the torrent and its data are
removed from the client (*Remove completed*, on by default). Nothing is done with an unknown series: the queue says
why.

**Import from Sonarr**: quality, custom formats, the naming (folders and episode files), the general settings (not
the recycle bin), the qBittorrent and Transmission clients **with their passwords** (their category becomes
`catasonarr`; a host inside K3s becomes `<name>.catacumbas69.com`, port 80, http), release and delay profiles, remote
path mappings and their tags, from the zip of a Sonarr backup (System → Backup → Backup Now, then download it;
decisions 120 and 122), on the Mac:

```
bash microshift/apps/catacumbas69-apps/catasonarr/catasonarr-import-sonarr.sh ~/Downloads/sonarr_backup_v4.0.14.2939_2026.11.25_21.00.00.zip
```

It reads the zip's `sonarr.db` in a temporary folder, without unzipping anything next to the zip, and asks for the
API key without showing it. `CATASONARR_URL=http://localhost:8086` sends it to the Catasonarr in IntelliJ instead
(profile `no-auth`, no key). Delete the zip afterwards: it holds Sonarr's API key and the clients' passwords.

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
| `CATASONARR_PLEX_TOKEN` | *(empty: Plex not told)* | Plex's token; Secret `catasonarr-plex` (key `token`), written by `catasonarr-secret.sh plex`. Plex's address is in Settings → Plex |
| `CATASONARR_TVMAZE_URL`, `CATASONARR_TMDB_URL` | `https://api.tvmaze.com`, `https://api.themoviedb.org/3` | The tests point TVmaze at a fake |
| `CATASONARR_METADATA_REFRESHINTERVAL`, `CATASONARR_METADATA_REFRESHINITIALDELAY` | `PT12H`, `PT10M` | The refresh of every series |
| `CATASONARR_COMMANDS_KEEP` | `7d` | How long finished commands are kept |
| `CATASONARR_OUTSIDE_PULSAR_SERVICE_URL`, `CATASONARR_OUTSIDE_PULSAR_BROKER_URL`, `CATASONARR_OUTSIDE_MONGODB_HOSTS` | `http://meloes:31080`, `pulsar://meloes:31650`, `meloes:32017` | Shown in Settings → Connections as the way in from outside (IntelliJ, Compass); Catasonarr itself uses the cluster's names |
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

`mvn verify` in `apps/`: unit tests (TVmaze's mapping, monitoring rules, folder names, titles, default profiles;
**Sonarr's 1409 parser cases**, `NetRegex`, episode file names, MediaInfo, the queue),
`SecurityConfigTests` with both logins on, `ProvisioningIT` and `SeriesApiIT` against real Pulsar and MongoDB
(Testcontainers; `SeriesApiIT` adds a series the way Overseerr does against a fake TVmaze, and imports quality rows
from Sonarr). The page's HTML, CSS and JavaScript are linted and formatted as Catapulsar's (ESLint, Stylelint,
html-validate, Prettier; `npm run format` fixes the formatting).

## Deploy

`microshift/apps/catacumbas69-apps/catasonarr/` and the ArgoCD Application `catasonarr` (manual Sync, as the other
apps). One replica, 320 Mi / 640 Mi, with the OpenTelemetry agent. It runs as user and group 1000 (as Sonarr's
PUID/PGID) and mounts the NAS as Sonarr does: `/data` (`/volume1/Media`) and `/downloads` (`/volumeUSB4/usbshare`);
the image has `ffprobe` (Alpine's `ffmpeg`). Before the first Sync, see the one-time setup
in [`microshift/apps/catacumbas69-apps/README.md`](../../microshift/apps/catacumbas69-apps/README.md#catasonarr).

Catavigia watches its readiness: `service.down` (critical) opens when it is not `UP` or does not answer
(`CATASONARR_URL` in Catavigia, orch.md decision 111).
