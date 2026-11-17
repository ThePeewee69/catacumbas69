#!/usr/bin/env bash
# The MongoDB admin user peewee69 and the mongo-express login on meloes (orch.md decision 101). Runs on the Mac;
# every oc command goes to meloes over ssh (key-based: `ssh meloes oc whoami` must work without a password). Each
# password is asked twice, not shown, and goes to meloes on stdin: never on a command line, in git or in the history.
#
#   bash microshift/apps/mongodb/mongo-admin.sh peewee69   sets the password of peewee69 (Secret mongodb/mongodb-password);
#                                                         the operator applies it and rewrites peewee69-connection
#   bash microshift/apps/mongodb/mongo-admin.sh express    sets the user and password of mongo-express' login
#                                                         (Secret mongodb/mongo-express-auth)
#
# Both restart mongo-express, which reads its Secrets only when it starts. The password of peewee69 goes into a
# connection string, so it may not have : @ / ? # [ ] % or spaces, and needs at least 16 characters.
set -euo pipefail

MELOES="${MELOES:-meloes}"
NS=mongodb

on_meloes() { ssh -o BatchMode=yes "$MELOES" "$@"; }

yaml_quote() {  # single-quoted YAML scalar
  printf "'%s'" "${1//\'/\'\'}"
}

ask_password() {  # $1 what for, $2 minimum length, $3 forbidden-character pattern (empty: none) → password on stdout
  local p1 p2
  read -r -s -p "New password for $1: " p1 </dev/tty; echo >&2
  read -r -s -p "Again: " p2 </dev/tty; echo >&2
  [[ "$p1" == "$p2" ]] || { echo "The two do not match; nothing changed." >&2; exit 1; }
  [[ ${#p1} -ge $2 ]] || { echo "At least $2 characters; nothing changed." >&2; exit 1; }
  if [[ -n "$3" && "$p1" =~ $3 ]]; then
    echo "It may not have : @ / ? # [ ] % or spaces; nothing changed." >&2
    exit 1
  fi
  printf '%s' "$p1"
}

apply_secret() {  # name, then key value pairs
  local name="$1"
  shift
  { printf 'apiVersion: v1\nkind: Secret\nmetadata:\n  name: %s\n  namespace: %s\ntype: Opaque\nstringData:\n' "$name" "$NS"
    while [[ $# -gt 0 ]]; do
      printf '  %s: %s\n' "$1" "$(yaml_quote "$2")"
      shift 2
    done
  } | on_meloes 'oc apply -f -' >/dev/null
}

restart_express() {
  if on_meloes "oc -n $NS get deploy mongo-express" >/dev/null 2>&1; then
    on_meloes "oc -n $NS rollout restart deploy/mongo-express" >/dev/null
    echo "mongo-express restarted."
  fi
}

on_meloes 'oc whoami' >/dev/null || { echo "ssh $MELOES 'oc whoami' failed: is the ssh key set up?" >&2; exit 1; }

case "${1:-}" in
  peewee69)
    password="$(ask_password peewee69 16 '[]:@/?#%[[:space:]]')"
    apply_secret mongodb-password password "$password"
    echo "Secret $NS/mongodb-password written. The operator applies it to the three members within a minute or two;"
    echo "  check with: ssh $MELOES 'oc -n $NS get mdbc catacumbas69'   (PHASE Running)"
    echo "Then update everything else that logs in as peewee69 (IntelliJ, mongosh, MongoDB Compass…)."
    restart_express
    ;;
  express)
    read -r -p "User of mongo-express' login: " user </dev/tty
    [[ -n "$user" && "$user" != *[[:space:]]* ]] || { echo "Empty or with spaces; nothing changed." >&2; exit 1; }
    password="$(ask_password "$user (mongo-express)" 12 '')"
    apply_secret mongo-express-auth username "$user" password "$password"
    echo "Secret $NS/mongo-express-auth written."
    restart_express
    ;;
  *)
    sed -n '2,13p' "$0" | sed 's/^# \{0,1\}//'
    exit 1
    ;;
esac
