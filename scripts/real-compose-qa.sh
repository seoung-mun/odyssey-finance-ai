#!/usr/bin/env bash
# 실제 Compose 경계만 확인한다. 모든 생성물은 이 실행의 project에 귀속된다.
set -euo pipefail

root=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
compose_file="$root/docker-compose.yml"
tmp_dir=
env_file=
override_file=
config_file=
logs_file=
ca_file=
project=
http_port=
https_port=
started=false

fail() {
  printf 'REAL QA 실패: %s\n' "$*" >&2
  exit 1
}

compose() {
  docker compose --project-name "$project" --env-file "$env_file" -f "$compose_file" -f "$override_file" "$@"
}

wait_https() {
  attempt=0
  until curl --silent --cacert "$ca_file" --resolve "localhost:$https_port:127.0.0.1" --max-time 5 --fail --output /dev/null "$https_base/" 2>/dev/null; do
    attempt=$((attempt + 1))
    [ "$attempt" -lt 30 ] || fail 'Caddy HTTPS가 준비되지 않았습니다'
    sleep 1
  done
}

certificate_pins() {
  root_pin=$(openssl x509 -in "$ca_file" -pubkey -noout | openssl pkey -pubin -outform DER | openssl dgst -sha256 -binary | openssl base64 -A)
  leaf_pin=$(openssl s_client -connect "127.0.0.1:$https_port" -servername localhost </dev/null 2>/dev/null | openssl x509 -pubkey -noout | openssl pkey -pubin -outform DER | openssl dgst -sha256 -binary | openssl base64 -A)
  printf '%s,%s\n' "$root_pin" "$leaf_pin"
}

masked_logs() {
  [ -f "$logs_file" ] || return 0
  sed \
    -e "s/$POSTGRES_PASSWORD/[REDACTED]/g" \
    -e "s/$REDIS_PASSWORD/[REDACTED]/g" \
    -e "s/$INTERNAL_API_TOKEN/[REDACTED]/g" \
    -e "s/$JWT_SECRET/[REDACTED]/g" \
    "$logs_file" >&2
}

cleanup() {
  status=$?
  if [ "$started" = true ]; then
    compose logs --no-color >"$logs_file" 2>/dev/null || true
    if [ "$status" -ne 0 ]; then
      masked_logs || true
    fi
    compose down -v --remove-orphans >/dev/null 2>&1 || true
    docker image rm "${project}-core-api" "${project}-analysis-api" "${project}-caddy" >/dev/null 2>&1 || true
  fi
  [ -n "$tmp_dir" ] && rm -rf "$tmp_dir"
  exit "$status"
}

trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

if [ "${1:-}" = "--self-check" ]; then
  command -v docker >/dev/null || fail 'docker가 필요합니다'
  command -v curl >/dev/null || fail 'curl이 필요합니다'
  command -v openssl >/dev/null || fail 'openssl이 필요합니다'
  command -v python3 >/dev/null || fail 'python3가 필요합니다'
  [ -f "$compose_file" ] || fail 'docker-compose.yml이 없습니다'
  printf 'self-check: prerequisites present\n'
  exit 0
fi

[ "$#" -eq 0 ] || fail '지원 옵션: --self-check'
command -v docker >/dev/null || fail 'docker가 필요합니다'
command -v curl >/dev/null || fail 'curl이 필요합니다'
command -v openssl >/dev/null || fail 'openssl이 필요합니다'
command -v python3 >/dev/null || fail 'python3가 필요합니다'
[ -f "$compose_file" ] || fail 'docker-compose.yml이 없습니다'

tmp_dir=$(mktemp -d "${TMPDIR:-/tmp}/odyssey-real-qa.XXXXXX")
env_file="$tmp_dir/.env"
override_file="$tmp_dir/compose.qa.yml"
config_file="$tmp_dir/compose.json"
logs_file="$tmp_dir/compose.log"
ca_file="$tmp_dir/caddy-root.crt"
project="odyssey-real-qa-${UID:-0}-$(date +%s)-$$"

read -r http_port https_port < <(python3 - <<'PY'
import socket

sockets = []
ports = []
for _ in range(2):
    sock = socket.socket()
    sock.bind(("127.0.0.1", 0))
    sockets.append(sock)
    ports.append(sock.getsockname()[1])
print(*ports)
for sock in sockets:
    sock.close()
PY
)

POSTGRES_PASSWORD=$(openssl rand -hex 24)
REDIS_PASSWORD=$(openssl rand -hex 24)
INTERNAL_API_TOKEN=$(openssl rand -hex 24)
JWT_SECRET=$(openssl rand -hex 32)
umask 077
printf '%s\n' \
  'POSTGRES_DB=dacon_qa' \
  'POSTGRES_USER=dacon_qa' \
  "POSTGRES_PASSWORD=$POSTGRES_PASSWORD" \
  "REDIS_PASSWORD=$REDIS_PASSWORD" \
  "INTERNAL_API_TOKEN=$INTERNAL_API_TOKEN" \
  "JWT_SECRET=$JWT_SECRET" \
  'GOOGLE_CLIENT_ID=real-qa-invalid.apps.googleusercontent.com' \
  "API_ADDRESS=localhost:$https_port" \
  "HTTP_ADDRESS=localhost:$http_port" \
  "HTTP_PORT=$http_port" \
  "HTTPS_PORT=$https_port" \
  'LOG_LEVEL=INFO' >"$env_file"
chmod 600 "$env_file"

cat >"$override_file" <<'YAML'
services:
  caddy:
    ports: !override
      - "127.0.0.1:${HTTP_PORT}:${HTTP_PORT}"
      - "127.0.0.1:${HTTPS_PORT}:${HTTPS_PORT}"
      - "127.0.0.1:${HTTPS_PORT}:${HTTPS_PORT}/udp"
    healthcheck:
      test: ["CMD", "wget", "--no-check-certificate", "--spider", "https://localhost:${HTTPS_PORT}/actuator/health"]
YAML

compose config --format json >"$config_file"
python3 - "$config_file" <<'PY'
import json
import sys

services = json.load(open(sys.argv[1], encoding="utf-8"))["services"]
for name, service in services.items():
    ports = service.get("ports", [])
    if name != "caddy" and ports:
        raise SystemExit(f"non-public service publishes ports: {name}")
    for port in ports:
        host_ip = port.get("host_ip", "") if isinstance(port, dict) else ""
        if host_ip != "127.0.0.1":
            raise SystemExit(f"non-loopback published port: {name}")
caddy_ports = services.get("caddy", {}).get("ports", [])
if len(caddy_ports) not in (2, 3):
    raise SystemExit(f"caddy must publish only loopback HTTP and HTTPS ports: {caddy_ports}")
PY

started=true
compose build
compose up -d --wait --wait-timeout 300

compose cp caddy:/data/caddy/pki/authorities/local/root.crt "$ca_file" >/dev/null
chmod 600 "$ca_file"

containers=$(compose ps -aq)
[ -n "$containers" ] || fail '기동된 컨테이너가 없습니다'
docker inspect --format '{{json .NetworkSettings.Ports}}' $containers >"$tmp_dir/ports.jsonl"
python3 - "$tmp_dir/ports.jsonl" <<'PY'
import json
import sys

for line in open(sys.argv[1], encoding="utf-8"):
    for bindings in (json.loads(line) or {}).values():
        for binding in bindings or []:
            if binding.get("HostIp") != "127.0.0.1":
                raise SystemExit("non-loopback runtime binding")
PY

http_base="http://localhost:$http_port"
https_base="https://localhost:$https_port"
wait_https
if curl --silent --resolve "localhost:$https_port:127.0.0.1" --max-time 5 --fail --output /dev/null "$https_base/" 2>/dev/null; then
  fail 'Caddy CA 없이 HTTPS가 성공했습니다'
fi
pins_before=$(certificate_pins)
redirect_headers=$(curl --silent --show-error --resolve "localhost:$http_port:127.0.0.1" --max-time 15 --dump-header - --output /dev/null "$http_base/")
printf '%s\n' "$redirect_headers" | grep -Eq '^HTTP/[0-9.]+ 30[1278]'
printf '%s\n' "$redirect_headers" | grep -Fqi "location: $https_base/"

static_status=$(curl --silent --show-error --cacert "$ca_file" --resolve "localhost:$https_port:127.0.0.1" --max-time 15 --output /dev/null --write-out '%{http_code}' "$https_base/")
[ "$static_status" -ge 200 ] && [ "$static_status" -lt 400 ] || fail "정적 진입점 상태: $static_status"

api_status=$(curl --silent --show-error --cacert "$ca_file" --resolve "localhost:$https_port:127.0.0.1" --max-time 15 --request POST --output /dev/null --write-out '%{http_code}' "$https_base/api/v1/auth/refresh")
[ "$api_status" = 401 ] || fail "인증 API 상태: $api_status"

v4_count=$(compose exec -T postgres psql -U dacon_qa -d dacon_qa -tAc "SELECT count(*) FROM flyway_schema_history WHERE version = '4' AND success")
[ "$v4_count" = 1 ] || fail "Flyway V4 적용 횟수: $v4_count"

compose restart postgres core-api caddy
compose up -d --wait --wait-timeout 300
https_base="https://localhost:$https_port"
wait_https
compose cp caddy:/data/caddy/pki/authorities/local/root.crt "$tmp_dir/caddy-root-after.crt" >/dev/null
ca_after="$tmp_dir/caddy-root-after.crt"
cmp -s "$ca_file" "$ca_after" || fail 'Caddy root CA가 restart 뒤 변경됐습니다'
pins_after=$(certificate_pins)
[ "$pins_before" = "$pins_after" ] || fail 'Caddy CA/SPKI identity가 restart 뒤 변경됐습니다'
v4_after=$(compose exec -T postgres psql -U dacon_qa -d dacon_qa -tAc "SELECT count(*) FROM flyway_schema_history WHERE version = '4' AND success")
[ "$v4_after" = 1 ] || fail "restart 뒤 Flyway V4 적용 횟수: $v4_after"

compose logs --no-color >"$logs_file"
for secret in "$POSTGRES_PASSWORD" "$REDIS_PASSWORD" "$INTERNAL_API_TOKEN" "$JWT_SECRET"; do
  grep -Fq "$secret" "$logs_file" && fail '컨테이너 로그에 secret이 노출되었습니다'
done

printf 'REAL QA 통과: %s (HTTP %s, HTTPS %s)\n' "$project" "$http_port" "$https_port"
