#!/usr/bin/env bash
# Deploy the license server code. Usage: server/deploy/deploy.sh   (one-time setup: setup.sh)
set -euo pipefail
TARGET="${TARGET:-ubuntu@54.83.190.141}"
SRC="$(cd "$(dirname "$0")/.." && pwd)"
rsync -az --delete --exclude node_modules --exclude data --exclude .env --exclude secrets --exclude deploy \
  "$SRC"/ "$TARGET":/tmp/skylark-license-src/
ssh "$TARGET" 'set -e
  sudo rsync -a --delete --exclude node_modules --exclude data --exclude .env /tmp/skylark-license-src/ /opt/skylark-license/
  cd /opt/skylark-license && sudo npm ci --omit=dev --silent --cache /tmp/skylark-npm
  sudo chown -R root:skylark /opt/skylark-license && sudo chown -R skylark:skylark /opt/skylark-license/data
  sudo systemctl restart skylark-license
  sleep 2; curl -s 127.0.0.1:8794/skylark/api/health; echo'
