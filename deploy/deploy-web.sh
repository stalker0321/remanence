#!/usr/bin/env bash
# Build the web app on the NL build host and publish it behind Caddy.
#
#   deploy/deploy-web.sh [git-rev]
#
# Releases live in /var/www/remanence/releases/<sha>; /var/www/remanence/current
# is swapped atomically. app-config.json is carried over from the live release,
# and the previous release's hashed assets are kept so open tabs and old
# service workers can still load their chunks.
set -euo pipefail
rev=${1:-HEAD}
repo=$(cd "$(dirname "$0")/.." && pwd)
sha=$(git -C "$repo" rev-parse --short=8 "$rev")
nl="hryshyn_dev@34.147.53.61"
key="$HOME/.ssh/vuzol_workers"
build="/srv/remanence/builds/web-$sha"
root=/var/www/remanence
release="$root/releases/$sha"

git -C "$repo" archive --format=tar "$sha" web protocol server/src/remanence/recovery/web \
  | ssh -i "$key" "$nl" "mkdir -p $build && tar -x -C $build"
ssh -i "$key" "$nl" "source /srv/remanence/tools/env.sh && cd $build/web && npm ci --offline --no-audit --no-fund >/dev/null && npm run -s typecheck && npx vitest run >/dev/null && npm run -s build >/dev/null"

stage=$(mktemp -d)
trap 'rm -rf "$stage"' EXIT
rsync -a -e "ssh -i $key" "$nl:$build/web/dist/" "$stage/"
live=$(readlink -f "$root/current")
cp "$live/app-config.json" "$stage/app-config.json"
cp --update=none "$live"/assets/* "$stage/assets/"

sudo mkdir -p "$release"
sudo rsync -a --delete "$stage/" "$release/"
sudo chown -R root:root "$release"
sudo chmod -R a+rX "$release"
sudo ln -sfn "$release" "$root/current.tmp"
sudo mv -T "$root/current.tmp" "$root/current"
curl -fsS -o /dev/null https://remanence.hryshyn.dev/ && echo "web deployed $sha"
