#!/usr/bin/env bash
# Our CA on meloes (orch.md decision 97). Runs on the Mac; every oc command goes to meloes over ssh (key-based:
# `ssh meloes oc whoami` must work without a password).
#
#   bash microshift/apps/tls/ca.sh cert [file]   saves the CA's certificate (public, not a secret) to install on
#                                                the Mac and the iPhone; default ~/Downloads/catacumbas69-ca.crt
#   bash microshift/apps/tls/ca.sh backup        saves the CA's Secret (certificate AND private key) to
#                                                ~/.catacumbas69/ca-backup.yaml, readable only by you
#   bash microshift/apps/tls/ca.sh restore       puts that Secret back, after MicroShift was reinstalled; do it
#                                                before the Application tls syncs, or right after (cert-manager
#                                                then keeps the restored CA), so the devices keep trusting it
#
# The private key never goes into git; the backup file is the only copy outside the cluster.
set -euo pipefail

MELOES="${MELOES:-meloes}"
BACKUP_DIR="$HOME/.catacumbas69"
BACKUP="$BACKUP_DIR/ca-backup.yaml"

on_meloes() { ssh -o BatchMode=yes "$MELOES" "$@"; }

on_meloes 'oc whoami' >/dev/null || { echo "ssh $MELOES 'oc whoami' failed: is the ssh key set up?" >&2; exit 1; }

case "${1:-}" in
  cert)
    out="${2:-$HOME/Downloads/catacumbas69-ca.crt}"
    on_meloes "oc -n cert-manager get secret catacumbas69-ca -o 'jsonpath={.data.ca\.crt}'" | openssl base64 -d -A >"$out"
    openssl x509 -in "$out" -noout -subject -enddate -fingerprint -sha256
    echo "Saved to $out (see microshift/apps/tls/README.md to install it on the Mac and the iPhone)."
    ;;
  backup)
    umask 077
    mkdir -p "$BACKUP_DIR"
    on_meloes "oc -n cert-manager get secret catacumbas69-ca -o yaml" \
      | grep -v -E '^  (resourceVersion|uid|creationTimestamp):' >"$BACKUP.tmp"
    grep -q 'tls.key:' "$BACKUP.tmp" || { rm -f "$BACKUP.tmp"; echo "The Secret has no tls.key; nothing saved." >&2; exit 1; }
    mv "$BACKUP.tmp" "$BACKUP"
    echo "Saved to $BACKUP (certificate and PRIVATE key: keep it out of git and of shared folders)."
    ;;
  restore)
    [[ -f "$BACKUP" ]] || { echo "No backup at $BACKUP." >&2; exit 1; }
    on_meloes "oc create namespace cert-manager --dry-run=client -o yaml | oc apply -f -" >/dev/null
    on_meloes 'oc apply -f -' <"$BACKUP"
    echo "Restored. cert-manager keeps it as long as it matches microshift/apps/tls/ca.yaml."
    ;;
  *)
    sed -n '2,14p' "$0" | sed 's/^# \{0,1\}//'
    exit 1
    ;;
esac
