#!/usr/bin/env bash
set -euo pipefail
release_dir=$1
image=$2
(
  cd "$release_dir"
  sha256sum --check SHA256SUMS
)
context=$(mktemp -d)
trap 'rm -rf "$context"' EXIT
install -m 0644 "$release_dir/backend-$GITHUB_SHA.jar" "$context/backend.jar"
docker build -f deploy/Dockerfile --label "org.opencontainers.image.source=$GITHUB_SERVER_URL/$GITHUB_REPOSITORY" \
  --label "org.opencontainers.image.revision=$GITHUB_SHA" -t "$image" "$context"
umask 077
for key in DB_PASSWORD DB_ROOT_PASSWORD AUTH_SECRET; do
  printf '%s=' "$key" >> "$context/settings.env"
  openssl rand -hex 32 >> "$context/settings.env"
done
DEPLOY_SETTINGS="$context/settings.env" bash deploy/update.sh "$image"
