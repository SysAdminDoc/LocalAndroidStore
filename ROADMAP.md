# Roadmap

## v0.1.0 — Done (2026-04-25)
- GitHub release discovery + catalog grid
- Install / uninstall / open / update flows
- Signature pinning
- Activity + crash logs
- Settings + EncryptedSharedPreferences PAT
- AMOLED + Catppuccin theme
- CI-signed release APK + sha256 sidecar

## v0.2.0 — Background updates + companion devices
- WorkManager update worker on a 6-hour cadence (configurable)
- Notification when an update is available; tap-to-install
- Wear OS companion: list catalog on watch, push install to paired phone
- Multi-device push via ADB pair (TLS) for pushing an APK from desktop → phone without USB

## v0.3.0 — F-Droid index export
- Generate an F-Droid-compatible `index-v1.json` + `repo/` tree from any user / org's GitHub releases
- Sign the repo, host on GitHub Pages
- Lets users add your GitHub releases as a real F-Droid repo

## v0.4.0 — Theming
- Light theme
- Accent picker (Catppuccin Mauve / Sapphire / Green / Yellow / Red / Pink / Teal)
- Optional adaptive (Material You) color scheme on Android 12+

## v0.5.0 — Multi-org support UI
- Configure multiple GitHub users / orgs from Settings (currently first-class for one)
- Per-source enable / disable
- Per-source PAT
