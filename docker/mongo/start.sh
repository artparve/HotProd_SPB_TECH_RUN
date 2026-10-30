#!/bin/sh
set -eu
umask 077
printf '%s' "$MONGO_KEYFILE_SECRET" > /tmp/repaircalc-mongo-key
chown mongodb:mongodb /tmp/repaircalc-mongo-key
exec /usr/local/bin/docker-entrypoint.sh mongod --replSet "$MONGO_REPLICA_SET" --keyFile /tmp/repaircalc-mongo-key --bind_ip_all --port "$MONGO_PORT"
