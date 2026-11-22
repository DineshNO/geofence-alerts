#!/bin/sh
# Runs the backend for local development: a PostgreSQL in Docker (port 55432) and the Spring Boot
# application from source. Needs Docker and a JDK 21. Usage:
#   deploy/scripts/dev-backend.sh [PORT]          (PORT defaults to 8080)
# Test accounts are written once to .dev/users.json (git-ignored): feed, sup01, op01, op02 with the
# password "dev-password". The database keeps its data between runs; stop it with
#   docker rm -f geoalert-dev-db
set -eu
PORT="${1:-8080}"
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
mkdir -p "$ROOT/.dev"

if [ -z "$(docker ps -q -f name=geoalert-dev-db)" ]; then
  docker rm -f geoalert-dev-db >/dev/null 2>&1 || true
  docker run -d --name geoalert-dev-db -p 55432:5432 -e POSTGRES_DB=geoalert -e POSTGRES_USER=geoalert \
    -e POSTGRES_PASSWORD=geoalert postgres:16.16-alpine >/dev/null
  echo "waiting for the database"
  until docker exec geoalert-dev-db pg_isready -U geoalert -d geoalert >/dev/null 2>&1; do sleep 1; done
  sleep 2
fi

if [ ! -f "$ROOT/.dev/users.json" ]; then
  cat > "$ROOT/.dev/users.json" <<'JSON'
[
  { "username": "feed",  "role": "INGEST",     "password": "dev-password" },
  { "username": "sup01", "role": "SUPERVISOR", "password": "dev-password" },
  { "username": "op01",  "role": "OPERATOR",   "password": "dev-password" },
  { "username": "op02",  "role": "OPERATOR",   "password": "dev-password" }
]
JSON
fi
[ -f "$ROOT/.dev/jwt-secret" ] || LC_ALL=C tr -dc 'A-Za-z0-9' < /dev/urandom | head -c 48 > "$ROOT/.dev/jwt-secret"

echo "backend on http://localhost:$PORT  (sign in as op01 / dev-password)"
cd "$ROOT/backend"
SERVER_PORT="$PORT" \
SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:55432/geoalert \
SPRING_DATASOURCE_USERNAME=geoalert \
SPRING_DATASOURCE_PASSWORD=geoalert \
APP_SECURITY_JWT_SECRET_FILE="$ROOT/.dev/jwt-secret" \
APP_USERS_FILE="$ROOT/.dev/users.json" \
APP_INSTANCE_NAME=dev \
  ./mvnw -q spring-boot:run
