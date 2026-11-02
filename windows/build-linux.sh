#!/usr/bin/env bash
# Builds the Linux package in Docker. Usage: ./build-linux.sh [dev] [arm64|amd64]
#   dev: a test build that skips license activation (never distribute it).
set -euo pipefail
cd "$(dirname "$0")"
MODE=release; [ "${1:-}" = "dev" ] && { MODE=dev; shift; }
ARCH=${1:-arm64}
TAGS=with_gvisor,with_quic,with_utls,with_clash_api,badlinkname,tfogo_checklinkname0
LD="-s -w -buildid= -X github.com/sagernet/sing-box/constant.Version=1.14.4 -X runtime.godebugDefault=multipathtcp=0,tlssha1=1 -checklinkname=0"
[ "$MODE" = dev ] && LD="$LD -X main.devUnlock=1"
IMAGE=skylark-linux-build-$ARCH
# First run for an architecture builds its environment (downloads about 190 MB of packages).
docker image inspect "$IMAGE" >/dev/null 2>&1 || docker build --platform linux/$ARCH -t "$IMAGE" linux
OUT=build/linux-$ARCH-$MODE/skylark
rm -rf "$OUT"; mkdir -p "$OUT"
# The whole repo is mounted because go.mod replaces sing-box with ../sing-box (patched).
docker run --rm --platform linux/$ARCH \
  -v "$(cd .. && pwd)":/repo -v "$HOME/go/pkg/mod":/go/pkg/mod -v skylark-gocache-$ARCH:/root/.cache/go-build \
  -e GOPROXY=https://goproxy.cn,direct -e GOSUMDB=sum.golang.google.cn -e GOFLAGS=-mod=mod -e GOTOOLCHAIN=auto \
  -w /repo/windows "$IMAGE" sh -ec "
    CGO_ENABLED=1 go build -trimpath -tags desktop,production,webkit2_41,$TAGS -ldflags '$LD' -o $OUT/skylark .
    CGO_ENABLED=0 go build -trimpath -tags $TAGS -ldflags '$LD' -o $OUT/skylark-core ./cmd/skylark-core
    chown -R $(id -u):$(id -g) build"
cp linux/install.sh linux/uninstall.sh linux/skylark.desktop linux/README.txt ../LICENSE "$OUT"/
cp winres/icon.png "$OUT/skylark.png"
chmod +x "$OUT"/install.sh "$OUT"/uninstall.sh
NAME=Skylark-Linux-$ARCH; [ "$MODE" = dev ] && NAME=$NAME-dev
tar -C "$(dirname "$OUT")" -czf "build/bin/$NAME.tar.gz" skylark
ls -lh "build/bin/$NAME.tar.gz" "$OUT/skylark" "$OUT/skylark-core"
