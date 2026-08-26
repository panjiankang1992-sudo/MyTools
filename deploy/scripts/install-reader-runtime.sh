#!/usr/bin/env bash
set -euo pipefail

project_dir="${1:-/opt/code/MyTools}"
runtime_dir="${2:-/opt/yuyutian/mytools/runtime/reader-runtime}"
backend_env="${3:-/opt/yuyutian/mytools/config/services.env}"
source_dir="$project_dir/deploy/reader-runtime"
env_file="$runtime_dir/runtime.env"

sudo install -d -m 750 "$runtime_dir/storage"
sudo install -m 640 "$source_dir/docker-compose.yml" "$runtime_dir/docker-compose.yml"

if ! sudo test -f "$env_file"; then
  runtime_key="$(openssl rand -hex 32)"
  printf 'READER_RUNTIME_SECURE_KEY=%s\n' "$runtime_key" | sudo tee "$env_file" >/dev/null
  sudo chmod 600 "$env_file"
fi

sudo docker compose --env-file "$env_file" -f "$runtime_dir/docker-compose.yml" up -d

if sudo test -f "$backend_env"; then
  runtime_key="$(sudo sed -n 's/^READER_RUNTIME_SECURE_KEY=//p' "$env_file" | tail -1)"
  sudo sed -i '/^READER_RUNTIME_\(SECURE_KEY\|ENABLED\|BASE_URL\)=/d' "$backend_env"
  printf 'READER_RUNTIME_SECURE_KEY=%s\nREADER_RUNTIME_ENABLED=true\nREADER_RUNTIME_BASE_URL=http://127.0.0.1:23120\n' \
    "$runtime_key" | sudo tee -a "$backend_env" >/dev/null
  sudo chmod 600 "$backend_env"
fi

sudo install -m 644 "$project_dir/deploy/systemd/mytools-reader-runtime.service" \
  /etc/systemd/system/mytools-reader-runtime.service
sudo install -m 644 "$project_dir/deploy/systemd/mytools-container-log@.service" \
  /etc/systemd/system/mytools-container-log@.service
sudo install -d -m 750 /opt/yuyutian/logs/mytools/mytools-reader-runtime
sudo install -d -m 755 /etc/logrotate.d
sudo install -m 644 "$project_dir/deploy/logrotate/mytools-container-services" \
  /etc/logrotate.d/mytools-container-services
sudo install -d -m 755 /etc/systemd/system/mytools-backend.service.d
sudo install -m 644 "$project_dir/deploy/systemd/mytools-backend-reader-runtime.conf" \
  /etc/systemd/system/mytools-backend.service.d/reader-runtime.conf
sudo systemctl daemon-reload
sudo systemctl enable --now mytools-reader-runtime.service
sudo systemctl enable --now mytools-container-log@mytools-reader-runtime.service
