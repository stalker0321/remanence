#!/usr/bin/env bash
# Nightly backup of Remanence production: a database dump and the blob
# directory. Kept 7 days on this host and 30 days on the NL archive disk.
# Run by remanence-backup.timer; safe to run by hand.
set -euo pipefail

local_dir=$HOME/remanence/backups/daily
remote=hryshyn_dev@34.147.53.61
remote_dir=/srv/remanence-archive/prod-backups
ssh_opts=(-i "$HOME/.ssh/vuzol_workers" -o BatchMode=yes)
stamp=$(date -u +%Y%m%dT%H%M%SZ)

umask 077
mkdir -p "$local_dir"
db=$local_dir/db-$stamp.dump
blobs=$local_dir/blobs-$stamp.tgz

docker exec remanence-prod-postgres-1 pg_dump -U remanence -Fc remanence > "$db.partial"
mv "$db.partial" "$db"
tar -C /var/lib/remanence -czf "$blobs.partial" blobs
mv "$blobs.partial" "$blobs"

find "$local_dir" -type f \( -name 'db-*.dump' -o -name 'blobs-*.tgz' \) -mtime +7 -delete

ssh "${ssh_opts[@]}" "$remote" "mkdir -p $remote_dir"
scp -q "${ssh_opts[@]}" "$db" "$blobs" "$remote:$remote_dir/"
ssh "${ssh_opts[@]}" "$remote" "find $remote_dir -type f -mtime +30 -delete"
echo "backed up $stamp: $(du -h "$db" | cut -f1) db, $(du -h "$blobs" | cut -f1) blobs"
