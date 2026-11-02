#!/usr/bin/env bash
# Installs Skylark to /opt/skylark, adds it to the app launcher, and gives only the engine
# (skylark-core) permission to create its network adapter. The window runs as you.
set -euo pipefail
cd "$(dirname "$(readlink -f "$0")")"

# Not a pipe into grep -q: it exits early, and pipefail would then report the library as missing.
libs=$(ldconfig -p 2>/dev/null || /sbin/ldconfig -p 2>/dev/null || true)
case "$libs" in
  *libwebkit2gtk-4.1*) have_webkit=1 ;;
  *) have_webkit=0 ;;
esac
if [ "$have_webkit" = 0 ]; then
  echo "Skylark needs WebKitGTK 4.1, which isn't installed. Install it, then run this again:"
  echo "  Arch / Omarchy:  sudo pacman -S webkit2gtk-4.1"
  echo "  Ubuntu / Debian: sudo apt install libwebkit2gtk-4.1-0"
  echo "  Fedora:          sudo dnf install webkit2gtk4.1"
  exit 1
fi

sudo install -d /opt/skylark
sudo install -m 755 skylark skylark-core /opt/skylark/
sudo install -m 644 LICENSE /opt/skylark/ 2>/dev/null || true
sudo install -Dm 644 skylark.desktop /usr/share/applications/skylark.desktop
sudo install -Dm 644 skylark.png /usr/share/icons/hicolor/256x256/apps/skylark.png
sudo ln -sf /opt/skylark/skylark /usr/local/bin/skylark
sudo install -m 755 uninstall.sh /opt/skylark/uninstall.sh

if command -v setcap >/dev/null && sudo setcap cap_net_admin,cap_net_raw,cap_net_bind_service+ep /opt/skylark/skylark-core; then
  echo "Skylark is installed. Open it from your app launcher, or run: skylark"
else
  echo "Skylark is installed, but the network permission couldn't be set on this filesystem."
  echo "It will ask for your password each time you connect."
fi
