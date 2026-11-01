#!/bin/zsh
# Compiles the sing-box engine (v1.14.4) into app/libs/libbox.aar.
# First time: git clone --depth 1 --branch v1.14.4 https://github.com/SagerNet/sing-box.git sing-box
set -e
cd "$(dirname "$0")/../sing-box"
python3 ../scripts/patches/reality-client-version.py .
export GOPROXY=https://goproxy.cn,direct GOSUMDB=sum.golang.google.cn
export ANDROID_HOME=$HOME/Library/Android/sdk
export ANDROID_NDK_HOME=$ANDROID_HOME/ndk/28.0.13004108
export PATH=$HOME/go/bin:$PATH
gomobile init
gomobile bind -v -o ../app/libs/libbox.aar \
  -target android/arm64,android/arm,android/amd64 -androidapi 24 \
  -javapkg=io.nekohasekai -libname=box -trimpath -buildvcs=false \
  -ldflags "-X github.com/sagernet/sing-box/constant.Version=1.14.4 -X runtime.godebugDefault=multipathtcp=0,tlssha1=1 -checklinkname=0 -s -w -buildid=" \
  -tags with_gvisor,with_quic,with_wireguard,with_utls,with_clash_api,badlinkname,tfogo_checklinkname0 \
  ./experimental/libbox
ls -la ../app/libs/libbox.aar
