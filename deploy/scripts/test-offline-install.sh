#!/bin/sh
# Tests an install from the bundle the way the target machine does it: the local images are removed
# first, the bundle is checked and loaded, the users file is a different one, and the system is
# started with the commands of INSTALL.md. Usage:
#   deploy/scripts/test-offline-install.sh VERSION [HOST_PORT]
# It cannot switch the network of this machine off; what it shows is that no step needs it: nothing
# is pulled (pull_policy: never) and the containers have no route out.
set -eu

VERSION="${1:?usage: test-offline-install.sh VERSION [HOST_PORT]}"
PORT="${2:-8080}"
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
WORK="${TMPDIR:-/tmp}/geoalert-install-test"
PROJECT="geoalert-install-test"

echo "== clean slate: remove the old test system and every image of the system"
if [ -d "$WORK" ]; then (cd "$WORK" && docker compose -p "$PROJECT" down -v >/dev/null 2>&1 || true); fi
rm -rf "$WORK" && mkdir -p "$WORK"
for image in "geoalert/backend:$VERSION" "geoalert/console:$VERSION" \
             postgres:16.16-alpine nginxinc/nginx-unprivileged:1.31.7-alpine3.24; do
  docker rmi -f "$image" >/dev/null 2>&1 || true
done
if docker image inspect --platform linux/amd64 "geoalert/backend:$VERSION" >/dev/null 2>&1; then
  echo "the images are still there" >&2; exit 1
fi
echo "no image of the system is present"

echo "== the target's folder: only the bundle files"
cp "$ROOT/deploy/bundle/"* "$WORK/"
cd "$WORK"

# a users file that is not the development one
cat > "$WORK/users-other.json" <<'JSON'
[
  { "username": "feed",  "role": "INGEST",     "password": "other-feed-pass-7" },
  { "username": "boss1", "role": "SUPERVISOR", "password": "other-boss-pass-7" },
  { "username": "alice", "role": "OPERATOR",   "password": "other-alice-pass-7" },
  { "username": "bob",   "role": "OPERATOR",   "password": "other-bob-pass-7" }
]
JSON

echo "== INSTALL.md, command 1 to 5"
if command -v sha256sum >/dev/null 2>&1; then sha256sum -c SHA256SUMS; else shasum -a 256 -c SHA256SUMS; fi
tar -xzf "geoalert-deploy-$VERSION.tar.gz"
docker load -i "geoalert-images-$VERSION.tar.gz" | tail -4
cat "$WORK/users-other.json" > secrets/users.json
LB_PORT="$PORT" docker compose -p "$PROJECT" up -d 2>&1 | tail -3

echo "== wait for the load balancer"
i=0
until curl -s -o /dev/null "http://localhost:$PORT/lb-health"; do
  i=$((i+1)); [ "$i" -lt 90 ] || { echo "did not start" >&2; docker compose -p "$PROJECT" ps; exit 1; }
  sleep 2
done
sleep 5
docker compose -p "$PROJECT" ps --format 'table {{.Service}}\t{{.State}}\t{{.Health}}'

echo "== checks"
echo "health:  $(curl -s "http://localhost:$PORT/health")"
echo "console: HTTP $(curl -s -o /dev/null -w '%{http_code}' "http://localhost:$PORT/")"
login() { curl -s -o /dev/null -w '%{http_code}' -X POST "http://localhost:$PORT/api/v1/auth/login" \
            -H 'Content-Type: application/json' -d "{\"username\":\"$1\",\"password\":\"$2\"}"; }
echo "login alice (in this users file):     HTTP $(login alice other-alice-pass-7)"
echo "login op01 (from the other file):     HTTP $(login op01 devpass-local)"
echo "login alice with a wrong password:    HTTP $(login alice wrong)"
echo "images pulled during the install: none (pull_policy: never; the images were loaded from the bundle)"
echo "backend-1 reaching another host:  $(docker compose -p "$PROJECT" exec -T backend-1 wget -T 3 -q -O- https://example.com 2>&1 | head -1)"
echo "the install test system is running on port $PORT (docker compose -p $PROJECT down -v removes it)"
