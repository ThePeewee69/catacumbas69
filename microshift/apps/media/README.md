# Media apps on MicroShift

The media apps that run on meloes, in the namespace `media` (orch.md decision 116). For now only **Seerr**; the
others still run in K3s (`apps/media/`) and come here when they leave it.

| App | Port | Route | Login |
|---|---|---|---|
| Seerr | 5055 | `https://seerr.catacumbas69.com` | Seerr's own, with Plex accounts |

## Seerr

[Seerr](https://docs.seerr.dev) takes requests for series and films: the merge of Overseerr and Jellyseerr.
Official image `ghcr.io/seerr-team/seerr`, pinned in `seerr/seerr-deployment.yaml`; ArgoCD Application `seerr`
(manual Sync). It runs as the image's `node` user (UID 1000) through its own service account with the SCC
`nonroot-v2`. Its settings and SQLite database are in `/app/config`, a 5 Gi volume on meloes' disk (`seerr-config`;
SQLite on NFS can corrupt). It trusts our CA (`NODE_EXTRA_CA_CERTS`, the ConfigMap `catacumbas69-ca` that
trust-manager writes in `media`), so it reaches Catasonarr through its Route.

### First time

1. **DNS**: `seerr.catacumbas69.com` pointing at meloes, like the other Routes.
2. **Sync** `seerr` in ArgoCD (after the push that brings `microshift/argocd/seerr.yaml`). The pod waits in
   `ContainerCreating` until trust-manager has written `catacumbas69-ca` in `media` (seconds; `tls` syncs on its
   own).
3. Open `https://seerr.catacumbas69.com` and follow Seerr's setup: **Plex** (sign in with Plex, pick the server and
   the libraries).
4. **Catasonarr as Sonarr**: Settings → Services → Sonarr → *Add Sonarr Server*:
   - Server name `Catasonarr`, **Default server** ticked
   - Hostname `catasonarr.catacumbas69.com`, port `443`, **Use SSL** ticked, URL base empty
   - API key: the key of the Sonarr Catasonarr replaces (Sonarr → Settings → General → API Key)
   - *Test*, then quality profile, root folder (e.g. `/data/Series`), language profile (the only one) and
     **Season folders** ticked
   - **Enable automatic search** off until Catasonarr searches (phase 2); a request with it on still adds the
     series, and the search ends *unsuccessful*
5. Request a series in Seerr: it appears in Catasonarr's Series page.

### Every update

Change the tag in `seerr/seerr-deployment.yaml` to the new release (<https://github.com/seerr-team/seerr/releases>),
commit, push and Sync. Read the release notes first: Seerr's own database is migrated when it starts.
