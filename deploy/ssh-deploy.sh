#!/usr/bin/env bash
# 배포 키의 SSH 강제 명령. 표준 입력의 일회용 토큰으로 요청한 커밋의 이미지를 받아 교체한다
set -euo pipefail

# 실행 중 이 파일이 새 커밋으로 바뀌어도 영향이 없도록 함수로 감싼다
main() {
  local sha=${SSH_ORIGINAL_COMMAND:-}
  [[ $sha =~ ^[0-9a-f]{40}$ ]] || { echo "배포할 커밋 SHA가 필요합니다." >&2; exit 2; }
  cd "$(dirname "$0")/.."
  exec 9>.git/deploy.lock
  flock -w 600 9
  local image=ghcr.io/teamplespace/teamplespace-backend:$sha
  # 토큰은 임시 설정 폴더에만 두고 끝나면 지운다
  cred=$(mktemp -d)
  trap 'rm -rf "$cred"' EXIT
  docker --config "$cred" login ghcr.io -u github-actions --password-stdin >/dev/null
  # main 검사를 통과한 커밋만 이미지가 있으므로 먼저 받아 커밋을 확인한다
  docker --config "$cred" pull -q "$image"
  git fetch -q --depth 1 origin "$sha"
  git checkout -q --detach FETCH_HEAD
  bash deploy/update.sh "$image"
}
main
