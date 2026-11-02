#!/usr/bin/env bash
# One-time server setup for the license service. Run from the Mac: server/deploy/setup.sh
# - creates the skylark user, /opt/skylark-license, /etc/skylark-license
# - uploads the license signing key (never committed to git)
# - copies SMTP + Stripe secret key from OpenVidSI's .env on the server (values never leave the server)
# - installs the systemd unit and the Caddy routes
set -euo pipefail
TARGET="${TARGET:-ubuntu@54.83.190.141}"
HERE="$(cd "$(dirname "$0")" && pwd)"
scp "$HERE/../secrets/license-ed25519.pem" "$HERE/skylark-license.service" "$TARGET":/tmp/
ssh "$TARGET" 'set -e
  id skylark >/dev/null 2>&1 || sudo useradd --system --no-create-home --shell /usr/sbin/nologin skylark
  sudo mkdir -p /opt/skylark-license/data /etc/skylark-license
  sudo install -m 640 -o root -g skylark /tmp/license-ed25519.pem /etc/skylark-license/license-ed25519.pem && rm /tmp/license-ed25519.pem
  if [ ! -f /opt/skylark-license/.env ]; then
    sudo grep -E "^(SMTP_HOST|SMTP_PORT|SMTP_USER|SMTP_PASS|STRIPE_SECRET_KEY)=" /opt/openvidsi/.env | sudo tee /opt/skylark-license/.env >/dev/null
    echo "MAIL_FROM=Skylark <contact@lihaila.com>" | sudo tee -a /opt/skylark-license/.env >/dev/null
    sudo chown root:skylark /opt/skylark-license/.env && sudo chmod 640 /opt/skylark-license/.env
  fi
  sudo chown -R skylark:skylark /opt/skylark-license/data
  sudo install -m 644 /tmp/skylark-license.service /etc/systemd/system/skylark-license.service && rm /tmp/skylark-license.service
  sudo systemctl daemon-reload && sudo systemctl enable skylark-license
  if ! grep -q "skylark-license" /etc/caddy/Caddyfile; then
    sudo cp /etc/caddy/Caddyfile /etc/caddy/Caddyfile.bak-skylark
    sudo python3 - <<PY
p="/etc/caddy/Caddyfile"; s=open(p).read()
anchor="  # OpenVidSI (Node app, 127.0.0.1:8793). Must stay above try_files."
block="""  # Skylark for Windows purchase + licenses (skylark-license, Node on 127.0.0.1:8794). Above try_files.
  route /skylark/buy {
    reverse_proxy 127.0.0.1:8794
  }
  route /skylark/thanks {
    reverse_proxy 127.0.0.1:8794
  }
  route /skylark/api/* {
    reverse_proxy 127.0.0.1:8794
  }
"""
assert anchor in s
s=s.replace(anchor, block+anchor,1); open(p,"w").write(s)
PY
    if sudo -u caddy caddy validate --config /etc/caddy/Caddyfile; then sudo systemctl reload caddy;
    else sudo cp /etc/caddy/Caddyfile.bak-skylark /etc/caddy/Caddyfile; echo "Caddy config invalid, restored"; exit 1; fi
  fi'
echo "setup done; now run deploy.sh"
