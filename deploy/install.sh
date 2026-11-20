#!/usr/bin/env bash
set -euo pipefail
[[ $EUID == 0 ]] || { echo "sudo bash deploy/install.sh로 실행하세요." >&2; exit 1; }
cd "$(dirname "$0")"
dest=/etc/teamplespace-backend
install -d -m 0700 "$dest"
exec 9>"$dest/install.lock"
flock 9
docker compose version >/dev/null
docker info >/dev/null
umask 077
if [[ ! -f "$dest/settings.env" ]]; then
  if [[ $# == 1 ]]; then
    install -m 0600 "$1" "$dest/settings.env"
  else
    for volume in teamplespace-backend-dev_dbdata teamplespace-backend-dev_uploads; do
      if docker volume inspect "$volume" >/dev/null 2>&1; then
        echo "기존 볼륨이 있습니다. 설치 명령 뒤에 기존 settings.env 절대 경로를 지정하세요." >&2
        exit 1
      fi
    done
    tmp=$(mktemp "$dest/settings.XXXXXXXX")
    trap 'rm -f "$tmp"' EXIT
    for key in DB_PASSWORD DB_ROOT_PASSWORD AUTH_SECRET; do
      printf '%s=' "$key" >> "$tmp"
      openssl rand -hex 32 >> "$tmp"
    done
    mv "$tmp" "$dest/settings.env"
  fi
fi
install -m 0644 compose.yaml "$dest/compose.yaml"
install -m 0755 update.sh "$dest/update.sh"
install -m 0644 teamplespace-backend-update.{service,timer} /etc/systemd/system/
systemctl daemon-reload
systemctl enable --now teamplespace-backend-update.timer
systemctl start teamplespace-backend-update.service
