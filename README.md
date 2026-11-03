# Skylark

The easy proxy client for your own servers. Paste a link, tap Connect. Built on [sing-box](https://github.com/SagerNet/sing-box).

Skylark is a client only. It includes no servers, so you need a server or subscription from your own provider or setup.

**Product page:** [lihaila.com/en/skylark](https://lihaila.com/en/skylark)

## Platforms

| Platform | Stack | Source |
| --- | --- | --- |
| Android 8.0+ | Kotlin | [`app/`](app) |
| iPhone and iPad (iOS 16+), Apple Silicon Macs | Swift, Network Extension | [`ios/`](ios) |
| Windows x64 | Go, Wails | [`windows/`](windows) |
| Linux x64 and arm64 | Go, Wails | [`windows/`](windows) |

## Features

- **Add servers quickly:** share link, QR code, subscription URL or manual entry
- **Protocols:** Shadowsocks, VMess, VLESS (with Reality and XTLS Vision), Trojan, Hysteria2, TUIC, AnyTLS and SOCKS5
- **Subscription formats:** base64 link lists, Clash YAML and sing-box JSON
- **Smart mode:** sites in your own country connect directly and everything else uses the proxy, with Global and Direct one tap away
- **Your own rules:** match by domain, keyword, IP range or country, then Proxy, Direct or Block
- **Per-app proxy:** all apps, all except some, or only the ones you pick
- **Speed test:** see each server's latency and pick the fastest
- **Languages:** English, 简体中文 and 繁體中文

Feature details vary a little by platform. The description above follows the Android app.

## Privacy

No account, ads, analytics or tracking. Your servers and settings stay on your device.

The Windows and Linux versions use a license key, so they contact lihaila.com when you activate and about every two weeks after that. See the [privacy policy](store/privacy.html) for exactly what is sent.

## Buying the Windows and Linux versions

Ready-made Windows and Linux builds cost USD 2.99 at [lihaila.com](https://lihaila.com/en/skylark). One key covers both systems on up to 3 PCs. They aren't sold in mainland China or France.

## Building from source

All builds use a patched sing-box v1.14.4, cloned into the repo root (it's ignored by Git):

```sh
git clone --depth 1 --branch v1.14.4 https://github.com/SagerNet/sing-box.git sing-box
```

The build scripts are written for macOS and need Go.

**Android** needs [gomobile](https://pkg.go.dev/golang.org/x/mobile/cmd/gomobile), the Android SDK and NDK 28.0.13004108:

```sh
scripts/build-libbox.sh          # builds app/libs/libbox.aar
./gradlew assembleDirectDebug
```

The `direct` flavor includes ad blocking. The `play` flavor leaves it out to meet Google Play's VPN policy.

**iOS and Mac** need gomobile and Xcode:

```sh
scripts/build-libbox-apple.sh    # builds ios/Libbox.xcframework
open ios/Skylark.xcodeproj
```

**Windows** needs [go-winres](https://github.com/tc-hib/go-winres). The sing-box patch is applied by the libbox scripts above, or run it directly first:

```sh
python3 scripts/patches/reality-client-version.py sing-box
windows/build.sh                 # builds windows/build/bin/Skylark.exe
```

**Linux** needs Docker and the same patch:

```sh
windows/build-linux.sh arm64     # or amd64
```

Pass `dev` as the first argument to either desktop script for a test build that skips license activation. Don't distribute dev builds.

[`server/`](server) holds the license server for Windows and Linux (Node.js, Stripe).

## License

[GNU GPLv3](COPYING). Copyright (C) 2026 Lihai La LLC. See [`LICENSE`](LICENSE).

## Contact

[support@lihaila.com](mailto:support@lihaila.com)
