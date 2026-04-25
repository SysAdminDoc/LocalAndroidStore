# Changelog

## Unreleased

- Catalog search field with exact, prefix, and lightweight fuzzy matching across app names, repo handles, descriptions, tags, versions, and package ids.
- Edge-to-edge polish: safe drawing insets at the root scaffold, IME padding for Settings, explicit dark transparent system bar styles, and API-27 navigation-bar contrast resources.
- DataStore migration hook for future settings schema changes.
- `lintDebug` blockers fixed for minSdk 26: API-guarded installer attribution and a `values-v27` style split.

## v0.2.0 — 2026-04-25 — Hardening pass

Closes the Android 14/15/16 platform-compliance gap and completes the install-flow primitives that v0.1.0 stubbed. No new big surfaces (Wear OS, ADB-pair, F-Droid emit) — those are v0.3+. Roadmap items 1–4, 7, 8, 12, 13, 16, 22 from the [v2 ROADMAP](ROADMAP.md).

### Install mechanics + signature pinning correctness

- **Update-ownership claim** on first install (Android 14+): `setRequestUpdateOwnership(true)` plus the `ENFORCE_UPDATE_OWNERSHIP` permission. After we install an app, no other installer can silently overwrite it — the closest thing to a hardware-backed signature pin you can get on stock Android. **The missing half of the v0.1 pin story.**
- **`PACKAGE_SOURCE_STORE`** declared on every session (Android 13+). Directly improves downstream apps: Accessibility-using apps installed via LAS skip the "Restricted Settings" unlock dance.
- **Explicit installer attribution** — `setInstallerPackageName`, `setOriginatingUid`, `setReferrerUri`. The system "App info → Installed from" UI now shows LocalAndroidStore + the GitHub release URL.
- **Lineage-aware signature verification** via Google's `apksig` library. APK Signature Scheme v3 / v3.1 publisher key rotations are now accepted automatically — if our pinned cert appears in the new APK's signing-cert lineage and the new cert was signed by an earlier lineage entry, the install proceeds and the pin rolls forward. Forged APKs without a valid lineage chain to our pinned cert still hard-reject.
- **Decoded `STATUS_FAILURE_*` messages** — replaces Android's generic "App not installed" with concrete causes: signature conflict, ABI / SDK incompatibility, storage shortage, restricted-state block.

### Platform compliance (Android 14 / 15 / 16)

- **`POST_NOTIFICATIONS` runtime permission flow** for the future scheduled-update channel.
- **`FOREGROUND_SERVICE_DATA_SYNC` permission declared** so v0.4's WorkManager background-update worker doesn't crash on first run.
- **Network Security Config** — `api.github.com`, `objects.githubusercontent.com`, `codeload.github.com`, `raw.githubusercontent.com` pinned at the **root CA SPKI** (DigiCert Global Root G2 + ISRG Root X1 backup). 6-month expiration so a forgotten pinset auto-disables instead of bricking the app. Cleartext traffic disabled.
- **Adaptive icon `<monochrome>` layer** — proper single-color glyph, separate from the foreground. Themed Icons on Android 13+ render the storefront silhouette cleanly under the system tint.
- **Predictive back gesture** opted in on `<application>`.

### Observability

- **Install audit log on disk** — `<files>/logs/install.log` (JSON Lines), one record per install / install-blocked / install-failed / uninstall-initiated event, rotates at 256 KB. Local only, never leaves the device. Useful as a forensic surface and a debugging trail.

### Build

- `apksig:8.7.3` added; ProGuard rules cover Tink + errorprone + google-http-client + joda already.
- Release APK: 2.31 MB signed.

### Cert SHA-256 (transparency)

```
9c6a927620d5a3ee033e4d2bd1007928d513484e1a9edbf3423b816e6ebd3a0d
```

Same as v0.1.0 (no key rotation).

---

## v0.1.0 — 2026-04-25

Initial release.

- GitHub-sourced discovery of `.apk` releases for any user / org
- Catalog grid with Catppuccin Mocha + AMOLED black theme
- One-tap install via `PackageInstaller.Session` (system dialog driven)
- One-tap uninstall via `Intent.ACTION_DELETE`
- One-tap launch of installed apps
- APK signature pinning per `applicationId` — first install captures, future installs verify, mismatches block
- Installed-state detection + "Update available" badge (remote `versionCode > local`)
- Optional GitHub PAT (encrypted via Android Keystore + EncryptedSharedPreferences)
- Optional topic filter (default `android-app`)
- Optional pre-release toggle
- Activity log + on-disk crash log
- Async-everywhere; UI never blocks on network or install
- Adaptive launcher icon (mauve / sapphire / green storefront, AMOLED black background)
- Signed release APK shipped via GitHub Actions (`KEYSTORE_BASE64` secret)
