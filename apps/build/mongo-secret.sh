#!/usr/bin/env bash
# MongoDB credentials of the catacumbas69 apps in MicroShift (decisions 84, 85). The password is generated here
# and never shown; the connection string is copied without being printed.
#
#   apps/build/mongo-secret.sh password   once, before syncing the mongodb Application with the new user:
#                                          creates Secret mongodb/catacumbas69-apps-password (random password)
#   apps/build/mongo-secret.sh copy       after the operator created the user: copies its connection string to
#                                          Secret catacumbas69-apps/catacumbas69-apps-mongodb (key uri)
#
# The operator cannot write that Secret into another namespace (it gets garbage-collected), hence the copy.
# Run `copy` again only if the password changes. Runs on meloes, with oc pointed at MicroShift
# (see microshift/apps/catacumbas69-apps/README.md).
set -euo pipefail

MDB_NAMESPACE=mongodb
APPS_NAMESPACE=catacumbas69-apps
PASSWORD_SECRET=catacumbas69-apps-password
OPERATOR_SECRET=catacumbas69-apps-connection
APPS_SECRET=catacumbas69-apps-mongodb

k() {
  oc "$@"
}

case "${1:-}" in
  password)
    if k -n "$MDB_NAMESPACE" get secret "$PASSWORD_SECRET" >/dev/null 2>&1; then
      echo "Secret $MDB_NAMESPACE/$PASSWORD_SECRET already exists; nothing changed."
      exit 0
    fi
    # Letters and digits only, so the connection string needs no escaping. tr reads a fixed amount of random bytes
    # (about 120 usable characters), so no command of the pipeline is cut short (with pipefail, a SIGPIPE would
    # stop the script without a word).
    password="$(head -c 512 /dev/urandom | LC_ALL=C tr -dc 'A-Za-z0-9')"
    password="${password:0:32}"
    if [[ ${#password} -lt 32 ]]; then
      echo "Could not generate a password; nothing changed." >&2
      exit 1
    fi
    k -n "$MDB_NAMESPACE" create secret generic "$PASSWORD_SECRET" --from-literal=password="$password" >/dev/null
    unset password
    echo "Created Secret $MDB_NAMESPACE/$PASSWORD_SECRET. Now sync the mongodb Application, then run: $0 copy"
    ;;
  copy)
    if ! k -n "$MDB_NAMESPACE" get secret "$OPERATOR_SECRET" >/dev/null 2>&1; then
      echo "Secret $MDB_NAMESPACE/$OPERATOR_SECRET does not exist yet: is mongodb synced and the user created?" >&2
      exit 1
    fi
    encoded="$(k -n "$MDB_NAMESPACE" get secret "$OPERATOR_SECRET" -o jsonpath='{.data.connectionString\.standard}')"
    uri="$(printf '%s' "$encoded" | base64 --decode)"
    unset encoded
    if [[ -z "$uri" ]]; then
      echo "Secret $MDB_NAMESPACE/$OPERATOR_SECRET has no connection string yet: is the user created?" >&2
      exit 1
    fi
    k create namespace "$APPS_NAMESPACE" --dry-run=client -o yaml | k apply -f - >/dev/null
    k -n "$APPS_NAMESPACE" create secret generic "$APPS_SECRET" --from-literal=uri="$uri" --dry-run=client -o yaml \
      | k apply -f - >/dev/null
    unset uri
    echo "Secret $APPS_NAMESPACE/$APPS_SECRET is up to date (key uri)."
    ;;
  *)
    echo "Usage: $0 password|copy" >&2
    exit 2
    ;;
esac
