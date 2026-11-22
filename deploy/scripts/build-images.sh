#!/bin/sh
# Builds the two images of a version for linux/amd64 (the target machine), on any machine with
# Docker, a JDK 21 and Node. Usage: deploy/scripts/build-images.sh 1.0.0
#
# The jar and the console files are built here and the Dockerfiles only copy them, so building for
# amd64 on an Apple Silicon laptop runs no x86 program. Run the tests separately:
#   (cd backend && ./mvnw verify) && (cd frontend && npm test)
set -eu

VERSION="${1:?usage: build-images.sh VERSION}"
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$ROOT"

echo "== backend jar"
(cd backend && ./mvnw -B -ntp -q -DskipTests package)

echo "== console files"
(cd frontend && npm ci --no-audit --no-fund && npm run build)

echo "== images (linux/amd64)"
docker build --provenance=false --platform linux/amd64 -f backend/Dockerfile -t "geoalert/backend:$VERSION" .
docker build --provenance=false --platform linux/amd64 -f frontend/Dockerfile -t "geoalert/console:$VERSION" .

for image in "geoalert/backend:$VERSION" "geoalert/console:$VERSION"; do
  platform="$(docker image inspect "$image" --format '{{.Os}}/{{.Architecture}}')"
  echo "$image is $platform"
  [ "$platform" = "linux/amd64" ] || { echo "wrong platform for $image" >&2; exit 1; }
done
