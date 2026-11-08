# Sing-box proxy type

This fork adds a fourth proxy type to Telegram's existing proxy screen:

**Settings → Data and Storage → Proxy → Add proxy → Sing-box**

Paste a complete [sing-box](https://sing-box.sagernet.org/) configuration in JSON form. The app
runs the sing-box core **inside the Telegram process**, exposes a loopback SOCKS5 inbound and
points tgnet at it — no `VpnService`, no separate app, and Telegram's own foreground service keeps
the connection alive in the background.

## What is supported

Everything sing-box itself supports, because the core interprets the config:

- `vless`, `vmess`, `trojan`, `shadowsocks`, `hysteria2`, `tuic`, `wireguard` (incl. `endpoints`),
  `ssh`, `anytls`, `http`, `socks`, `direct` and any future outbound type;
- `route`, `dns`, `experimental` sections and their rules.

The **inbound** side is owned by the app: any `inbounds` you paste are ignored and replaced with
`{"type":"socks","tag":"tg-socks","listen":"127.0.0.1","listen_port":<port>}`. Routing rules that
reference an inbound tag should use `tg-socks`. `route.auto_detect_interface` is forced to `false`.

## Build

The core is not vendored; build it once on a machine with Go, the Android SDK and NDK r27:

```bash
export ANDROID_HOME=/path/to/android-sdk
export ANDROID_NDK_HOME="$ANDROID_HOME/ndk/27.2.12479018"
./Tools/build-singbox-aar.sh          # pinned to v1.14.4, arm64-v8a
```

This produces `TMessagesProj/libs/libbox.aar` (arm64 only, ~20–30 MB) and records the pinned tag in
`TMessagesProj/libs/libbox.version`. Then build the app as usual:

```bash
./gradlew :TMessagesProj_App:assembleAfatRelease
```

Notes:

- Only `arm64-v8a` is bundled. On other ABIs the proxy type reports itself as unavailable instead
  of crashing.
- `PlatformInterface` changes between sing-box releases; if you bump the tag, re-check
  `experimental/libbox/platform.go` and update `SingBoxPlatform`.
- `libbox.aar` is a build artifact: keep it out of version control (or commit it, like the other
  prebuilt libraries in `TMessagesProj/jni/prebuild`).

## Limitations

- Voice/video calls use UDP and are not carried over the SOCKS5 link.
- The config is limited to 64 KB.
- The license of sing-box is GPL-3.0-or-later with a name clause; Telegram's sources are
  GPL-2.0-or-later. Combining them for personal use is fine, distribution needs both licenses to be
  honoured.
