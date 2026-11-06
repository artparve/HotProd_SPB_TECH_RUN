#!/bin/sh
set -eu
java -jar /app/backend.jar --repaircalc.command=import-catalog "--repaircalc.catalog.file=${CATALOG_FILE:-${DATA_DIR:-/app/data}/catalog-expanded.json}" "--repaircalc.catalog.mappings=${CATALOG_MAPPINGS_FILE:-${DATA_DIR:-/app/data}/work-mappings-expanded.json}"
java -jar /app/backend.jar --repaircalc.command=import-rules "--repaircalc.rules.file=${RULES_FILE:-${DATA_DIR:-/app/data}/rules-expanded.json}"
java -jar /app/backend.jar --repaircalc.command=import-inspirations
