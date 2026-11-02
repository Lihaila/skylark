#!/usr/bin/env bash
# Removes Skylark. Your servers and settings stay in ~/.config/Skylark (delete that folder too if you want).
set -euo pipefail
sudo rm -f /usr/local/bin/skylark /usr/share/applications/skylark.desktop /usr/share/icons/hicolor/256x256/apps/skylark.png
sudo rm -rf /opt/skylark
echo "Skylark removed."
