# AstrBot Mobile

Experimental Android wrapper for AstrBot: WebView client, embedded Alpine/proot runtime, deployment tools, adapter management, and an optional AI assistant. This is an independent project, not an official AstrBot release.

## Build

Use JDK 17 and Android SDK 35. Configure your own SDK location in `local.properties`, then run `./gradlew assembleDebug`. The bundled runtime supports arm64 devices. The project currently targets Android SDK 28 for embedded executable compatibility.

## Current limitations

- Server start/stop is explicit; there is no automatic restart or boot watchdog.
- Vendor background freezing can interrupt the App-managed server, including foreground services. Shizuku execution is not yet available in the App.
- The WebView bundles two exact-version initial voice scripts to avoid CDN startup blocking. Later voice model/WASM downloads may still require network access.
- Login DOM and full client usability are separate from HTTP 200. The latest offline client regression result is not confirmed.
- Adapter end-to-end operation, prolonged screen-off stability, and access from another LAN device are not fully verified.

API credentials and server settings must be supplied by each user. No account data, conversation history, runtime data, signing keys, or device logs are included in this source export.

## Third-party components

Bundled proot is based on termux/proot and statically linked with talloc. Corresponding source and available license notices are under `third_party/`. Pinned WebView scripts and their notices are under `app/src/main/assets/web-vendor/`. AstrBot and optional adapters are downloaded at runtime and remain subject to their respective licenses. No license grant for original project code has been selected yet.
