#!/usr/bin/env bash
# Imports Sonarr's settings from its database into Catasonarr (orch.md decisions 112 and 122): quality definitions,
# custom formats, quality profiles with their scores, the naming (folders and episode files), the general settings
# (not the recycle bin), the qBittorrent and Transmission clients with their passwords (their category becomes
# catasonarr; a host inside K3s becomes its name in catacumbas69.com, over http), release and delay profiles, remote
# path mappings and their tags. Sonarr's ids are kept.
#
#   bash microshift/apps/catacumbas69-apps/catasonarr/catasonarr-import-sonarr.sh ~/Downloads/sonarr_backup_v4.0.14.2939_2026.11.25_21.00.00.zip
#
# Runs on the Mac, with macOS's own unzip and sqlite3, on the zip of a Sonarr backup (System → Backup → Backup Now,
# then download it; orch.md decision 120): its sonarr.db is a consistent copy, which one taken by hand from Sonarr's
# /config while it runs may not be. The zip is never changed: its sonarr.db is unzipped into a temporary folder,
# read there and deleted at the end.
# Asks for Catasonarr's API key (the one of the Sonarr it replaces) without showing it, and sends the rows to
# Catasonarr's own import, which replaces those settings. CATASONARR_URL changes the address (default
# https://catasonarr.catacumbas69.com; http://localhost:8086 for a Catasonarr in IntelliJ with the profile no-auth,
# which asks for no key).
set -euo pipefail

URL="${CATASONARR_URL:-https://catasonarr.catacumbas69.com}"

fail() {  # a refusal that cannot go unnoticed: red on a terminal, and NOTHING CHANGED on a line of its own
  local red='' reset=''
  if [[ -t 2 ]]; then red=$'\e[1;31m'; reset=$'\e[0m'; fi
  printf '\n%s✘ %s%s\n%s✘ NOTHING CHANGED%s\n\n' "$red" "$1" "$reset" "$red" "$reset" >&2
  exit 1
}

rows() {  # a query → its rows as a JSON array; [] when there are none
  local out
  out="$(sqlite3 -json "$WORK/sonarr.db" "$1")" || fail "sqlite3 could not read the sonarr.db in $ZIP."
  printf '%s' "${out:-[]}"
}

[[ $# -eq 1 ]] || { sed -n '2,17p' "$0" | sed 's/^# \{0,1\}//'; exit 1; }
ZIP="$1"
[[ -f "$ZIP" ]] || fail "No file $ZIP."
[[ "$ZIP" == *.zip ]] || fail "$ZIP is not a zip: give the zip of a Sonarr backup (System → Backup)."
command -v unzip >/dev/null || fail "unzip not found (it comes with macOS)."
command -v sqlite3 >/dev/null || fail "sqlite3 not found (it comes with macOS)."
command -v curl >/dev/null || fail "curl not found."

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
body="$WORK/body.json"
unzip -l "$ZIP" sonarr.db >/dev/null 2>&1 || fail "$ZIP has no sonarr.db: is it the zip of a Sonarr backup?"
unzip -q -o "$ZIP" sonarr.db -d "$WORK" \
  || fail "Could not unzip $ZIP (macOS may need Terminal allowed in System Settings → Privacy & Security → Files and Folders)."
{
  printf '{"qualityDefinitions":'
  rows 'SELECT Id AS id, Quality AS quality, Title AS title, MinSize AS minSize, MaxSize AS maxSize,
        PreferredSize AS preferredSize FROM QualityDefinitions ORDER BY Id'
  printf ',"customFormats":'
  rows 'SELECT Id AS id, Name AS name, Specifications AS specifications,
        IncludeCustomFormatWhenRenaming AS includeCustomFormatWhenRenaming FROM CustomFormats ORDER BY Id'
  printf ',"qualityProfiles":'
  rows 'SELECT Id AS id, Name AS name, Cutoff AS cutoff, Items AS items, UpgradeAllowed AS upgradeAllowed,
        FormatItems AS formatItems, MinFormatScore AS minFormatScore, CutoffFormatScore AS cutoffFormatScore,
        MinUpgradeFormatScore AS minUpgradeFormatScore FROM QualityProfiles ORDER BY Id'
  printf ',"naming":'
  rows 'SELECT SeriesFolderFormat AS seriesFolderFormat, SeasonFolderFormat AS seasonFolderFormat,
        SpecialsFolderFormat AS specialsFolderFormat, ReplaceIllegalCharacters AS replaceIllegalCharacters,
        ColonReplacementFormat AS colonReplacementFormat,
        CustomColonReplacementFormat AS customColonReplacementFormat, RenameEpisodes AS renameEpisodes,
        StandardEpisodeFormat AS standardEpisodeFormat, DailyEpisodeFormat AS dailyEpisodeFormat,
        AnimeEpisodeFormat AS animeEpisodeFormat, MultiEpisodeStyle AS multiEpisodeStyle FROM NamingConfig LIMIT 1'
  printf ',"config":'
  rows 'SELECT Key AS key, Value AS value FROM Config'
  printf ',"tags":'
  rows 'SELECT Id AS id, Label AS label FROM Tags ORDER BY Id'
  # SELECT * on the next ones: their columns changed between Sonarr 4 releases; Catasonarr reads them by name
  printf ',"downloadClients":'
  rows 'SELECT * FROM DownloadClients ORDER BY Id'
  printf ',"releaseProfiles":'
  rows 'SELECT * FROM ReleaseProfiles ORDER BY Id'
  printf ',"delayProfiles":'
  rows 'SELECT * FROM DelayProfiles ORDER BY Id'
  printf ',"remotePathMappings":'
  rows 'SELECT * FROM RemotePathMappings ORDER BY Id'
  printf '}'
} >"$body"

count() { sqlite3 "$WORK/sonarr.db" "SELECT COUNT(*) FROM $1"; }
echo "Read from $ZIP: $(count QualityProfiles) quality profiles, $(count CustomFormats) custom formats," \
  "$(count DownloadClients) download clients, $(count ReleaseProfiles) release and $(count DelayProfiles) delay" \
  "profiles, $(count RemotePathMappings) remote path mappings."

key=''
if [[ "$URL" == https://* ]]; then
  read -r -s -p "Catasonarr's API key (the one of the Sonarr it replaces): " key </dev/tty; echo
  [[ "$key" =~ ^[0-9a-f]{32}$ ]] || fail "Not a Sonarr API key: it has 32 characters, 0-9 and a-f only."
fi

# The key goes to curl on stdin as a header, never on the command line; without a key, no header at all
headers=''
[[ -n "$key" ]] && headers="$(printf 'X-Api-Key: %s' "$key")"
answer="$(printf '%s\n' "$headers" | curl -sS --fail-with-body -H @- -H 'Content-Type: application/json' \
  --data-binary @"$body" "$URL/api/v3/catasonarr/import/sonarr")" || fail "Catasonarr refused the import: $answer"
echo "Imported into $URL: $answer"
