#!/usr/bin/env bash
# Catasonarr's API key on meloes (orch.md decision 109). Runs on the Mac; every oc command goes to meloes over ssh
# (key-based: `ssh meloes oc whoami` must work without a password). The key goes to meloes on stdin: never on a
# command line, in git or in the history.
#
#   bash microshift/apps/catacumbas69-apps/catasonarr/catasonarr-secret.sh api-key
#
# Asks for the API key of the Sonarr that Catasonarr replaces (Sonarr → Settings → General → Security → API Key),
# twice and without showing it, and writes it into the Secret catacumbas69-apps/catasonarr-api-key (key api-key).
# Prowlarr, Bazarr, Overseerr, Jellyseerr and Homarr then only change Sonarr's address to Catasonarr's. Restarts
# Catasonarr, which reads the Secret only when it starts.
set -euo pipefail

MELOES="${MELOES:-meloes}"
NS=catacumbas69-apps
SECRET=catasonarr-api-key

on_meloes() { ssh -o BatchMode=yes "$MELOES" "$@"; }

fail() {  # a refusal that cannot go unnoticed: red on a terminal, and NOTHING CHANGED on a line of its own
  local red='' reset=''
  if [[ -t 2 ]]; then red=$'\e[1;31m'; reset=$'\e[0m'; fi
  printf '\n%s✘ %s%s\n%s✘ NOTHING CHANGED%s\n\n' "$red" "$1" "$reset" "$red" "$reset" >&2
  exit 1
}

ask_key() {  # → the key on stdout; Sonarr's keys are 32 lower-case hexadecimal characters
  local k1 k2
  read -r -s -p "Sonarr's API key: " k1 </dev/tty; echo >&2
  read -r -s -p "Again: " k2 </dev/tty; echo >&2
  [[ "$k1" == "$k2" ]] || fail "The two keys do not match."
  [[ "$k1" =~ ^[0-9a-f]{32}$ ]] || fail "Not a Sonarr API key: it has 32 characters, 0-9 and a-f only."
  printf '%s' "$k1"
}

on_meloes 'oc whoami' >/dev/null || fail "ssh $MELOES 'oc whoami' failed: is the ssh key set up?"

case "${1:-}" in
  api-key)
    key="$(ask_key)"
    on_meloes "oc create namespace $NS --dry-run=client -o yaml | oc apply -f -" >/dev/null
    printf 'apiVersion: v1\nkind: Secret\nmetadata:\n  name: %s\n  namespace: %s\ntype: Opaque\nstringData:\n  api-key: %s\n' \
      "$SECRET" "$NS" "$key" | on_meloes 'oc apply -f -' >/dev/null
    echo "Secret $NS/$SECRET written."
    if on_meloes "oc -n $NS get deploy catasonarr" >/dev/null 2>&1; then
      on_meloes "oc -n $NS rollout restart deploy/catasonarr" >/dev/null
      echo "Catasonarr restarted."
    fi
    ;;
  *)
    sed -n '2,11p' "$0" | sed 's/^# \{0,1\}//'
    exit 1
    ;;
esac
