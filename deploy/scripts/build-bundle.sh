#!/bin/sh
# Builds the offline bundle of one or more versions into deploy/bundle/ (for the machine that has no
# internet). Usage: deploy/scripts/build-bundle.sh 1.0.0 [1.1.0 ...]
# The images of every version must already exist (deploy/scripts/build-images.sh VERSION).
#
# The bundle holds, for each version, an archive of its images (the two of ours and the two base
# images they run next to, linux/amd64 only) and an archive of the compose file and settings, plus
# INSTALL.md and SHA256SUMS, which lists every other file. Each file is far below 2 GB.
set -eu

[ "$#" -ge 1 ] || { echo "usage: build-bundle.sh VERSION [VERSION ...]" >&2; exit 1; }
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
OUT="$ROOT/deploy/bundle"
BASE_IMAGES="postgres:16.16-alpine nginxinc/nginx-unprivileged:1.31.7-alpine3.24"

# The deploy files of a released version are taken from its tag. The newest version's tag must therefore be at
# the commit being built, or the bundle would carry the files of an older commit (and not match its images).
for version in "$@"; do latest="$version"; done
if git -C "$ROOT" rev-parse -q --verify "refs/tags/v$latest" >/dev/null; then
  [ "$(git -C "$ROOT" rev-parse "v$latest^{commit}")" = "$(git -C "$ROOT" rev-parse HEAD)" ] \
    || { echo "tag v$latest is not at HEAD: move it (git tag -f v$latest) before building the bundle" >&2; exit 1; }
fi

rm -rf "$OUT" && mkdir -p "$OUT"

# an image may be present for another platform too (an Apple Silicon laptop): ask for linux/amd64
for image in $BASE_IMAGES; do
  docker image inspect --platform linux/amd64 "$image" >/dev/null 2>&1 \
    || docker pull --platform linux/amd64 "$image"
done

for version in "$@"; do
  images="$BASE_IMAGES geoalert/backend:$version geoalert/console:$version"
  for image in $images; do
    platform="$(docker image inspect --platform linux/amd64 "$image" --format '{{.Os}}/{{.Architecture}}')"
    [ "$platform" = "linux/amd64" ] || { echo "$image is $platform, not linux/amd64" >&2; exit 1; }
  done
  echo "== images $version"
  # shellcheck disable=SC2086
  docker save --platform linux/amd64 $images | gzip -9 > "$OUT/geoalert-images-$version.tar.gz"

  echo "== deploy files $version"
  # the files of a released version come from its git tag, so the archive of 1.0.0 is the 1.0.0 one
  # even when it is built later; a version without a tag yet is taken from the working tree
  SRC="$(mktemp -d)"
  if git -C "$ROOT" rev-parse -q --verify "refs/tags/v$version" >/dev/null; then
    git -C "$ROOT" archive "v$version" deploy | tar -x -C "$SRC"
  else
    mkdir "$SRC/deploy" && cp -R "$ROOT/deploy/." "$SRC/deploy/"
  fi
  FILES="docker-compose.yml nginx env secrets/README.txt secrets/users.example.json"
  [ -d "$SRC/deploy/sql" ] && FILES="$FILES sql"
  # shellcheck disable=SC2086
  tar -czf "$OUT/geoalert-deploy-$version.tar.gz" -C "$SRC/deploy" $FILES
  rm -rf "$SRC"
done

cp "$ROOT/INSTALL.md" "$OUT/INSTALL.md"

echo "== checksums"
cd "$OUT"
if command -v sha256sum >/dev/null 2>&1; then SUM="sha256sum"; else SUM="shasum -a 256"; fi
# shellcheck disable=SC2012
ls | grep -v '^SHA256SUMS$' | sort | xargs $SUM > SHA256SUMS
cat SHA256SUMS
ls -l
