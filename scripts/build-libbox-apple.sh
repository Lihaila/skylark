#!/bin/zsh
# Compiles the sing-box engine (v1.14.4, patched) into ios/Libbox.xcframework.
set -e
cd "$(dirname "$0")/../sing-box"
python3 ../scripts/patches/reality-client-version.py .
export GOPROXY=https://goproxy.cn,direct GOSUMDB=sum.golang.google.cn
export PATH=$HOME/go/bin:$PATH
rm -rf Libbox.xcframework
# with_low_memory: a packet tunnel extension only gets ~50 MB on iOS.
gomobile bind -v -target ios,iossimulator -libname=box -iosversion=15.0 \
  -trimpath -buildvcs=false \
  -ldflags "-X github.com/sagernet/sing-box/constant.Version=1.14.4 -X runtime.godebugDefault=multipathtcp=0,tlssha1=1 -checklinkname=0 -s -w -buildid=" \
  -tags with_gvisor,with_quic,with_wireguard,with_utls,with_clash_api,with_dhcp,grpcnotrace,with_low_memory,badlinkname,tfogo_checklinkname0 \
  ./experimental/libbox
mkdir -p ../ios
rm -rf ../ios/Libbox.xcframework
mv Libbox.xcframework ../ios/
du -sh ../ios/Libbox.xcframework
