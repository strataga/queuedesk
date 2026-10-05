#!/bin/sh
# Small, inspectable project wrapper. This is not the Apache wrapper distribution.
set -eu
ROOT=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
. "$ROOT/scripts/java-env.sh"
VERSION=3.9.11
HASH=bcfe4fe305c962ace56ac7b5fc7a08b87d5abd8b7e89027ab251069faebee516b0ded8961445d6d91ec1985dfe30f8153268843c89aa392733d1a3ec956c9978
CACHE="$ROOT/.cache"
MAVEN="$CACHE/apache-maven-$VERSION"
if [ ! -x "$MAVEN/bin/mvn" ]; then
  mkdir -p "$CACHE"
  ARCHIVE="$CACHE/maven.tar.gz"
  curl --fail --silent --show-error --location "https://repo.maven.apache.org/maven2/org/apache/maven/apache-maven/$VERSION/apache-maven-$VERSION-bin.tar.gz" -o "$ARCHIVE"
  actual=$(shasum -a 512 "$ARCHIVE" | cut -d ' ' -f 1)
  [ "$actual" = "$HASH" ] || { echo "Maven archive checksum mismatch" >&2; rm -f "$ARCHIVE"; exit 1; }
  tar -xzf "$ARCHIVE" -C "$CACHE"
  rm "$ARCHIVE"
fi
cd "$ROOT"
exec "$MAVEN/bin/mvn" "-Dmaven.repo.local=$CACHE/repository" "$@"
