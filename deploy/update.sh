#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
registry=ghcr.io/teamplespace/teamplespace-backend

if [[ $# == 0 ]]; then
  docker pull "$registry:home-dev"
  digests=$(docker image inspect -f '{{range .RepoDigests}}{{println .}}{{end}}' "$registry:home-dev")
  API_IMAGE=$(printf '%s\n' "$digests" | grep -m1 "^$registry@sha256:[0-9a-f]\{64\}$")
elif [[ $# == 1 ]]; then
  API_IMAGE=$1
else
  exit 2
fi
export API_IMAGE
stack=(docker compose --env-file "${DEPLOY_SETTINGS:-./settings.env}" -f compose.yaml)
expected=$(docker image inspect -f '{{.Id}}' "$API_IMAGE")
"${stack[@]}" up -d --wait --wait-timeout 240
api=$("${stack[@]}" ps -q api)
[[ $(docker inspect -f '{{.Image}}' "$api") == "$expected" ]]
"${stack[@]}" exec -T api test -w /srv/uploads

for ((attempt=0; attempt<60; attempt++)); do
  if curl -fsS --max-time 3 http://127.0.0.1:18100/v1/capabilities 2>/dev/null |
    python3 -c 'import json, sys; d = json.load(sys.stdin); sys.exit(not ("configVersion" in d and "authProviders" in d))' 2>/dev/null; then
    printf 'API 응답과 업로드 경로 확인 %s\n' "$API_IMAGE"
    exit 0
  fi
  sleep 2
done
echo "배포한 API의 기능 조회 응답을 확인하지 못했습니다." >&2
exit 1
