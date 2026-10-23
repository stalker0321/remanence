#!/usr/bin/env bash
# Deploy the backend from a commit of this repository:
#   deploy/deploy-backend.sh [<commit>]   (default HEAD)
# Builds ~/remanence/releases/<sha> with its own venv, switches the `current`
# symlink and restarts remanence-backend (which migrates before starting).
# Roll back by pointing `current` at a previous release and restarting.
set -euo pipefail
repo=$(git -C "$(dirname "$0")" rev-parse --show-toplevel)
sha=$(git -C "$repo" rev-parse --short=8 "${1:-HEAD}")
root=$HOME/remanence
release=$root/releases/$sha
if [ ! -d "$release/venv" ]; then
  mkdir -p "$release"
  git -C "$repo" archive "$sha" server protocol | tar -x -C "$release"
  (cd "$release/server" && UV_PROJECT_ENVIRONMENT="$release/venv" uv sync --frozen --no-dev)
fi
ln -sfn "releases/$sha" "$root/current.new" && mv -T "$root/current.new" "$root/current"
sudo systemctl restart remanence-backend
sleep 3
curl -fsS "http://127.0.0.1:18125/healthz" && echo " deployed $sha"
