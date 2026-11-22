#!/usr/bin/env bash
# Imports what touches quality from Sonarr's database into Catasonarr (orch.md decision 112): quality definitions,
# custom formats, quality profiles with their scores, and the folder naming. Sonarr's ids are kept.
#
#   bash microshift/apps/catacumbas69-apps/catasonarr/catasonarr-import-sonarr.sh ~/Downloads/sonarr.db
#
# Runs on the Mac with macOS's own sqlite3, on a COPY of sonarr.db (copied first from Sonarr's /config,
# /volume1/k3s/media/Sonarr on the NAS; the file Sonarr is using is never opened, and the copy is only read). Asks for Catasonarr's API key (the one of the Sonarr
# it replaces) without showing it, and sends the rows to Catasonarr's own import, which replaces its quality
# settings. CATASONARR_URL changes the address (default https://catasonarr.catacumbas69.com; http://localhost:8086
# for a Catasonarr in IntelliJ with the profile no-auth, which asks for no key).
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
  out="$(sqlite3 -readonly -json "$DB" "$1")" || fail "sqlite3 could not read $DB: is it a copy of sonarr.db?"
  printf '%s' "${out:-[]}"
}

[[ $# -eq 1 ]] || { sed -n '2,11p' "$0" | sed 's/^# \{0,1\}//'; exit 1; }
DB="$1"
[[ -f "$DB" ]] || fail "No file $DB."
command -v sqlite3 >/dev/null || fail "sqlite3 not found (it comes with macOS)."
command -v curl >/dev/null || fail "curl not found."

body="$(mktemp)"
trap 'rm -f "$body"' EXIT
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
        CustomColonReplacementFormat AS customColonReplacementFormat FROM NamingConfig LIMIT 1'
  printf '}'
} >"$body"

echo "Read from $DB: $(sqlite3 -readonly "$DB" 'SELECT COUNT(*) FROM QualityProfiles') quality profiles," \
  "$(sqlite3 -readonly "$DB" 'SELECT COUNT(*) FROM CustomFormats') custom formats."

key=''
if [[ "$URL" == https://* ]]; then
  read -r -s -p "Catasonarr's API key (the one of the Sonarr it replaces): " key </dev/tty; echo
  [[ "$key" =~ ^[0-9a-f]{32}$ ]] || fail "Not a Sonarr API key: it has 32 characters, 0-9 and a-f only."
fi

# The key goes to curl on stdin as a header, never on the command line; without a key, no header at all
headers=''
[[ -n "$key" ]] && headers="$(printf 'X-Api-Key: %s' "$key")"
answer="$(printf '%s\n' "$headers" | curl -sS --fail-with-body -H @- -H 'Content-Type: application/json' \
  --data-binary @"$body" "$URL/api/v3/catasonarr/import/sonarr-quality")" || fail "Catasonarr refused the import: $answer"
echo "Imported into $URL: $answer"
