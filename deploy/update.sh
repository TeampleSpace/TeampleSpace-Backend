#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
[[ $# == 1 ]] || { echo "실행할 이미지 주소가 필요합니다." >&2; exit 2; }
export API_IMAGE=$1
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
