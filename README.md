# AstrBot Mobile

Experimental Android wrapper for AstrBot: WebView client, embedded Alpine/proot runtime, deployment tools, adapter management, and an optional AI assistant. This is an independent project, not an official AstrBot release.

## Build

Use JDK 17 and Android SDK 35. Configure your own SDK location in `local.properties`, then run `./gradlew assembleDebug`. The bundled runtime supports arm64 devices. The project currently targets Android SDK 28 for embedded executable compatibility.

### Bundled vs. online runtime

The full-embedded build ships a complete rootfs (Alpine + Python + AstrBot) inside the APK, so a fresh install works offline with no download, no `git clone`, and no `pip install`. The online path is still present as a fallback when the bundled archive is absent.

To produce the bundled archive, you need an already-provisioned rootfs. On a device:

```
run-as com.astrbot.mobile sh build_rootfs_template.sh
```

This writes `app/src/main/assets/rootfs/rootfs-template.bin` (about 300 MB) and strips all credentials, databases, plugins, logs, and caches before archiving. The output is excluded from version control via `.gitignore`, so each build must generate or supply it locally.

Two details worth knowing:

- The archive extension is `.bin` on purpose. AAPT2 treats `.gz`/`.tar`/`.zip` as archives, silently unpacking and renaming them inside `assets/`, which doubles the payload and makes the configured `noCompress` ineffective.
- The archive is a plain `tar.gz` stream despite the extension; the app decompresses it with a streaming reader and reports progress.

### First-run dashboard password

The very first server start writes a fixed initial dashboard password (`123456`, user `astrbot`) only when no password has been set yet, and flags the account as needing a change. An existing password is never overwritten.

This is a deliberate convenience/security trade-off, not an upstream AstrBot behavior:

- AstrBot validates complexity (`>= 8` chars, upper, lower, digit) when a password is *set*, but login only compares hashes. That is why a weak initial password can log in at all.
- AstrBot does **not** hard-block login when a change is required; it merely returns a `change_pwd_hint` flag. This build intentionally does not patch upstream code to force the change, so the initial password remains valid until the user changes it in the WebUI.

If you distribute this build, tell your users to change the password immediately after the first login.

## Current limitations

- Server start/stop is explicit; there is no automatic restart or boot watchdog.
- Vendor background freezing can interrupt the App-managed server, including foreground services. Shizuku execution is not yet available in the App.
- The WebView bundles two exact-version initial voice scripts to avoid CDN startup blocking. Later voice model/WASM downloads may still require network access.
- Login DOM and full client usability are separate from HTTP 200. The latest offline client regression result is not confirmed.
- Adapter end-to-end operation, prolonged screen-off stability, and access from another LAN device are not fully verified.

API credentials and server settings must be supplied by each user. No account data, conversation history, runtime data, signing keys, or device logs are included in this source export.

## Third-party components

Bundled proot is based on termux/proot and statically linked with talloc. Corresponding source and available license notices are under `third_party/`. Pinned WebView scripts and their notices are under `app/src/main/assets/web-vendor/`. AstrBot and optional adapters are downloaded at runtime and remain subject to their respective licenses. No license grant for original project code has been selected yet.
