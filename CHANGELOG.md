# Changelog

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
