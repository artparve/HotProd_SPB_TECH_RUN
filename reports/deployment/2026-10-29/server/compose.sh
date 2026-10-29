#!/bin/sh
set -eu
cd /opt/repaircalc
exec docker compose --env-file env/.env -f docker/docker-compose.yml -f docker/docker-compose.override.yml -f docker/docker-compose.gpu.yml --profile llm "$@"
