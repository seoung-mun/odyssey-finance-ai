#!/usr/bin/env bash
set -euo pipefail

root=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
script="$root/scripts/real-compose-qa.sh"

bash -n "$script"
"$script" --self-check

for required in 'trap cleanup EXIT' 'docker compose --project-name "$project"' 'down -v --remove-orphans' '127.0.0.1:' 'chmod 600 "$env_file"' 'compose config --format json' 'masked_logs' 'grep -Fq "$secret"'; do
  grep -Fq "$required" "$script"
done

if grep -Eq 'printf.*\$\{?(POSTGRES_PASSWORD|REDIS_PASSWORD|JWT_SECRET|INTERNAL_API_TOKEN)' "$script"; then
  echo "secret printed directly" >&2
  exit 1
fi
