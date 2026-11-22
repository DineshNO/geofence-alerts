#!/bin/sh
# Tests the upgrade from 1.0.0 to 1.1.0 the way INSTALL.md describes it, while traffic runs.
#   deploy/scripts/test-rolling-upgrade.sh FEED_FILE [HOST_PORT]
# The bundle must hold both versions: deploy/scripts/build-bundle.sh 1.0.0 1.1.0
#
# It installs 1.0.0 from the bundle, loads the whole feed, starts steady traffic (readers and
# feeders, upgrade-load.py), runs the upgrade commands of INSTALL.md one by one, and prints how
# many requests failed. Between the database change and the first new backend it checks that the
# old version still works on the changed schema. At the end it checks the result in the database.
set -eu

FEED="${1:?usage: test-rolling-upgrade.sh FEED_FILE [HOST_PORT]}"
PORT="${2:-8080}"
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
WORK="${TMPDIR:-/tmp}/geoalert-upgrade-test"
PROJECT="geoalert-upgrade-test"
URL="http://localhost:$PORT"
export LB_PORT="$PORT" COMPOSE_PROJECT_NAME="$PROJECT"
PSQL="docker compose exec -T postgres psql -U geoalert -d geoalert"

if [ -d "$WORK" ]; then (cd "$WORK" && docker compose down -v >/dev/null 2>&1 || true); fi
rm -rf "$WORK" && mkdir -p "$WORK"
cp "$ROOT/deploy/bundle/"* "$WORK/"
cd "$WORK"
cat > users.json <<'JSON'
[
  { "username": "feed",  "role": "INGEST",     "password": "upgrade-feed-pass-7" },
  { "username": "alice", "role": "OPERATOR",   "password": "upgrade-alice-pass-7" }
]
JSON

echo "== install 1.0.0 from the bundle"
sha256sum -c SHA256SUMS 2>/dev/null || shasum -a 256 -c SHA256SUMS
mkdir v100 && tar -xzf geoalert-deploy-1.0.0.tar.gz -C v100
docker load -i geoalert-images-1.0.0.tar.gz | tail -2
cd v100
cat ../users.json > secrets/users.json
docker compose up -d --wait 2>&1 | tail -2
until curl -s -o /dev/null "$URL/lb-health"; do sleep 2; done
echo "running: $(docker compose ps --format '{{.Service}}={{.Image}}' | sort | tr '\n' ' ')"

echo "== load the whole feed into 1.0.0 (no source_type column exists yet)"
python3 "$ROOT/tools/replay.py" --base-url "$URL" --file "$FEED" --username feed \
  --password upgrade-feed-pass-7 --speed max --connections 4 2>&1 | tail -6
echo "reports: $($PSQL -tA -c 'select count(*) from reports')"

echo "== start steady traffic"
rm -f ../stop
python3 "$ROOT/deploy/scripts/upgrade-load.py" "$URL" "$FEED" "$WORK/stop" feed upgrade-feed-pass-7 \
  alice upgrade-alice-pass-7 > ../load.out 2>&1 &
LOAD=$!
sleep 5

echo "== INSTALL.md upgrade, step 1: prepare"
cd "$WORK"
docker load -i geoalert-images-1.1.0.tar.gz | tail -2
tar -xzf geoalert-deploy-1.1.0.tar.gz -C v100
cd v100

echo "== step 2: expand the database"
docker compose run --rm migrate 2>&1 | tail -2
echo "column: $($PSQL -tA -c "select data_type||' nullable='||is_nullable from information_schema.columns where table_name='reports' and column_name='source_type'")"

echo "== the old version on the changed schema (1.0.0 is still running on both backends)"
echo "running: $(docker compose ps --format '{{.Service}}={{.Image}}' | grep backend | sort | tr '\n' ' ')"
for i in 1 2 3; do
  code=$(curl -s -o /dev/null -w '%{http_code}' -X POST "$URL/api/v1/reports/batch" \
    -H "Authorization: Bearer $(curl -s -X POST "$URL/api/v1/auth/login" -H 'Content-Type: application/json' \
      -d '{"username":"feed","password":"upgrade-feed-pass-7"}' | python3 -c 'import json,sys;print(json.load(sys.stdin)["accessToken"])')" \
    -H 'Content-Type: application/x-ndjson' \
    --data-binary "{\"reportId\":\"v100-on-new-schema-$i\",\"assetId\":\"AST-00001\",\"kind\":\"SURFACE\",\"observedAt\":\"2026-11-02T06:00:0$i.000Z\",\"lat\":1.0,\"lon\":1.0,\"source\":\"GND-1\"}")
  echo "1.0.0 delivers a report on the new schema: HTTP $code"
done
echo "rows written by 1.0.0 with no type: $($PSQL -tA -c "select count(*) from reports where source_type is null and report_id like 'v100-on-new-schema-%'")"

upgrade_backend() {  # $1 = the one to replace, $2 = the other one
  cat "nginx/upstream-only-$2.conf" > nginx/active/upstream.conf
  docker compose exec lb nginx -s reload 2>&1 | tail -1
  docker compose up -d --no-deps --wait "backend-$1" 2>&1 | tail -1
  cat nginx/upstream-both.conf > nginx/active/upstream.conf
  docker compose exec lb nginx -s reload 2>&1 | tail -1
}
echo "== step 3: replace backend-1"; upgrade_backend 1 2
echo "== step 4: replace backend-2"; upgrade_backend 2 1
echo "== step 5: replace the console"
docker compose up -d --no-deps --wait console 2>&1 | tail -1
echo "running: $(docker compose ps --format '{{.Service}}={{.Image}}' | sort | tr '\n' ' ')"

echo "== step 6: backfill (traffic still running)"
START=$(date +%s)
cat sql/10-backfill-procedure.sql | $PSQL -v ON_ERROR_STOP=1 >/dev/null
echo "CALL backfill_source_type(10000, 50);" | $PSQL -v ON_ERROR_STOP=1
echo "CALL backfill_source_type(10000, 50);" | $PSQL -v ON_ERROR_STOP=1   # the catch-up run
echo "backfill took $(( $(date +%s) - START )) s"
cat sql/20-verify.sql | $PSQL

echo "== step 7: required column, then the index (traffic still running)"
cat sql/30-not-null.sql | $PSQL -v ON_ERROR_STOP=1
cat sql/40-index.sql | $PSQL -v ON_ERROR_STOP=1

sleep 5
touch ../stop
wait "$LOAD" || true

echo "== result"
cat ../load.out
echo "column now: $($PSQL -tA -c "select is_nullable from information_schema.columns where table_name='reports' and column_name='source_type'") (is_nullable)"
echo "index valid: $($PSQL -tA -c "select indisvalid from pg_index i join pg_class c on c.oid=i.indexrelid where c.relname='reports_asset_type_observed_idx'")"
echo "types: $($PSQL -tA -F ' ' -c "select source_type, count(*) from reports group by 1 order by 1" | tr '\n' ';')"
echo "types agree with the source prefix: $($PSQL -tA -c "select count(*) from reports where source_type <> case when source like 'SAT%' then 'SATELLITE' when source like 'GND%' then 'GROUND' when source like 'MOB%' then 'MOBILE' else 'UNKNOWN' end") disagreeing rows"
token=$(curl -s -X POST "$URL/api/v1/auth/login" -H 'Content-Type: application/json' -d '{"username":"alice","password":"upgrade-alice-pass-7"}' | python3 -c 'import json,sys;print(json.load(sys.stdin)["accessToken"])')
echo "track with sourceType filter: $(curl -s -H "Authorization: Bearer $token" "$URL/api/v1/assets/AST-00001/track?sourceType=GROUND&limit=2")"
echo "the upgrade test system is running on port $PORT (cd $WORK/v100 && COMPOSE_PROJECT_NAME=$PROJECT docker compose down -v removes it)"
