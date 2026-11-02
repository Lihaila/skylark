#!/usr/bin/env bash
# Builds Skylark.exe for Windows (x64) from macOS with plain `go build` (the Wails CLI isn't needed).
# Usage: ./build.sh [dev]    dev = test build that skips license activation (never distribute it).
set -euo pipefail
cd "$(dirname "$0")"
export GOPROXY=${GOPROXY:-https://goproxy.cn,direct} GOFLAGS=-mod=mod GOSUMDB=sum.golang.google.cn
TAGS=desktop,production,with_gvisor,with_quic,with_utls,with_clash_api,badlinkname,tfogo_checklinkname0
LDFLAGS="-H windowsgui -s -w -buildid= -X github.com/sagernet/sing-box/constant.Version=1.14.4 -X runtime.godebugDefault=multipathtcp=0,tlssha1=1 -checklinkname=0"
NAME=Skylark
if [ "${1:-}" = "dev" ]; then LDFLAGS="$LDFLAGS -X main.devUnlock=1"; NAME=Skylark-dev; fi
~/go/bin/go-winres make --arch amd64 >/dev/null   # icon, admin manifest, version info -> rsrc_windows_amd64.syso
mkdir -p build/bin
GOOS=windows GOARCH=amd64 CGO_ENABLED=0 go build -trimpath -tags "$TAGS" -ldflags "$LDFLAGS" -o "build/bin/$NAME.exe" .
ls -lh "build/bin/$NAME.exe"
