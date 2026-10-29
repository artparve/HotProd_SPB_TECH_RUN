#!/bin/sh
set -eu
java -jar /app/backend.jar --repaircalc.command=import-catalog \
  --repaircalc.catalog.file=/app/data/catalog-v1.json \
  --repaircalc.catalog.mappings=/app/data/work-mappings-v1.json
exec java -jar /app/backend.jar --repaircalc.command=import-rules \
  --repaircalc.rules.file=/app/data/rules-v1.json
