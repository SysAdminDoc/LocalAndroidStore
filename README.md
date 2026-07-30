<h1 align="center">LocalAndroidStore</h1>

<p align="center">
  <a href="https://github.com/SysAdminDoc/LocalAndroidStore/releases"><img src="https://img.shields.io/badge/version-0.2.2-cba6f7?style=for-the-badge" alt="Version" /></a>
  <a href="LICENSE"><img src="https://img.shields.io/badge/license-MIT-a6e3a1?style=for-the-badge" alt="License" /></a>
  <a href="https://developer.android.com/about/versions/oreo"><img src="https://img.shields.io/badge/Android-8.0%2B-74c7ec?style=for-the-badge" alt="Android 8.0+" /></a>
  <a href="https://kotlinlang.org/"><img src="https://img.shields.io/badge/Kotlin-2.1-7F52FF?style=for-the-badge&logo=kotlin&logoColor=white" alt="Kotlin" /></a>
</p>

> **A personal store for the Android apps you build yourself.**
> Lists every APK across your GitHub releases, downloads the latest, and drives the system installer with a single tap. Install. Update. Uninstall. Move on.

LocalAndroidStore exists for one reason: when you ship a lot of Android apps from GitHub Releases, sideloading each one through the file manager on every fresh install / re-image is friction. F-Droid won't host private or in-development apps. Obtainium is the closest generic equivalent, but it's not tailored to your catalog or your visual identity.

This is the Android sibling of [LocalChromeStore](https://github.com/SysAdminDoc/LocalChromeStore) — same idea, same look, different platform.

---

## Why it exists

Stock Android won't let you "silent-install" anything unless you're a device-owner / Work Profile admin. Every other app on the device — including this one — has to go through the system PackageInstaller dialog, which the user must confirm. That's by design. What we *can* do is:

- discover every APK release across your GitHub repos,
- download the latest one and drive `PackageInstaller.Session` so the system dialog appears once per install,
- pin the publisher's signing certificate so a silent key swap (repo takeover, MITM) gets blocked instead of installed,
- show installed-state and update-available status, and
- give you a one-tap launch / uninstall path.

That's what this is.

---

## Features (current)

- **Multi-source GitHub discovery** — every enabled GitHub user / org source with a `.apk` asset on its latest release. Each source has its own enable toggle, optional topic filter, pre-release toggle, and optional PAT.
- **Rate-aware offline catalog** — release lookups are capped at four concurrent requests, GitHub ETags reuse unchanged responses, partial sources survive other source failures, and a dated on-device snapshot remains usable offline. TLS, token, authorization, rate-limit, network, server, malformed-response, and valid-empty outcomes are shown distinctly.
- **Store-style cards** — Catppuccin Mocha on AMOLED black. Repo handle, star count, version tag, status badge, two-line description.
- **Fast catalog search** — filter by app name, repo owner / handle, description, tag, version, or package id. Exact hits rank first, with lightweight fuzzy matching for compact names.
- **One-tap install** — APK is downloaded to app cache, then driven through `PackageInstaller.Session`. The system shows its install dialog, the user confirms once, done.
- **One-tap uninstall** — fires `Intent.ACTION_DELETE`, lands on the system uninstall confirmation. Catalog refreshes after.
- **One-tap open** — launches the installed app's main activity.
- **Gentle queued updates** — installed updates can run through Android 14+ user-initiated data-transfer jobs (WorkManager fallback on older versions), then wait for the target app to leave the foreground, device idle, and calls to end before commit. Attempts are capped and terminal reasons persist on the card.
- **APK signature pinning** — first successful install captures the signing-cert SHA-256 fingerprint. Future updates that don't match the pin are **blocked** with a clear "publisher key changed — possible MITM or repo takeover" warning. We never auto-accept a key swap.
- **Developer Verification preflight** — installs separately report whether a Google verification surface is present, that package registration is **Unknown** (Android exposes no status capability to this app), and that LocalAndroidStore's direct sideload route is outside the initial participating-store enforcement beginning 2026-09-30. The advisory links to Google's official guidance.
- **Version-aware installed state** — source-scoped records retain package, manifest version, signer, and GitHub asset identity. A tag or asset change is shown as a new release until its APK is inspected; only a higher manifest `versionCode` becomes an update, while equal-code reinstalls and lower-code downgrades require explicit actions.
- **GitHub PATs (optional)** — source-specific tokens bump API rate limits from 60 → 5,000/hr and unlock private repos for that source. Stored in a Tink AEAD-encrypted app-private file, with the keyset protected by the Android Keystore.
- **Activity log + crash log** — every download, install, uninstall, and crash is logged in-app and to disk at `<app files dir>/logs/crash.log`.
- **Async everywhere** — the UI never blocks on a download or an API call.

---

## Install

### From release (recommended)

1. Grab the latest `LocalAndroidStore-vX.Y.Z.apk` from the [Releases page](https://github.com/SysAdminDoc/LocalAndroidStore/releases).
2. Sideload it to your device however you sideload (`adb install`, file manager, Sync to phone, etc.).
3. The first time you open it and try to install something, Android will prompt for **"Install unknown apps"** — grant it. The app deep-links to the right setting.

### From source

```bash
git clone https://github.com/SysAdminDoc/LocalAndroidStore.git
cd LocalAndroidStore
./gradlew assembleDebug
# then sideload app/build/outputs/apk/debug/app-debug.apk
```

This repository's automated verification produces the debug APK only; it does not sign release artifacts.

---

## Usage

1. Tap **Settings** in the bottom nav.
2. Configure one or more **GitHub sources**. Each source is a GitHub user or org; the default source is `SysAdminDoc`.
3. *(Optional)* Paste a source-specific personal access token to raise rate limits and surface private repos owned by that source. The field is masked; the value lives in a Tink AEAD-encrypted app-private file with an Android Keystore-protected keyset.
4. *(Optional)* Enable **Filter by topic** per source if you want to limit discovery to repos tagged with that source's topic.
5. *(Optional)* Toggle **Show pre-releases** per source if you want to see `prerelease: true` releases.
6. Tap **Save settings**, hop back to **Catalog**, hit **Refresh**.

Every qualifying repo appears as a card. Tap **Install** — the APK downloads, the system install dialog appears, you confirm. Tap **Open** to launch. Tap **Uninstall** to land on the system uninstall confirmation.

---

## How discovery works

For each enabled GitHub source, LocalAndroidStore:

1. Lists owned, non-archived, non-fork public repos via the GitHub REST API (`/users/{user}/repos`).
2. If the source has a PAT, also lists authenticated repos via `/user/repos`, filters them back to the source owner, and dedupes them with the public list so private user / org repos can appear.
3. For each repo, fetches the latest release (`/repos/{owner}/{repo}/releases/latest`, or the first non-draft from `/releases?per_page=10` when pre-releases are enabled), with a global maximum of four concurrent release requests.
4. Picks one APK asset per release: skips `*.apk.idsig` sidecars and `*.aab` files, prefers an asset whose name contains `universal`, otherwise picks the largest `.apk`.
5. Drops repos with no APK asset on their latest release. Archived repos and forks are dropped at step 1.
6. Persists ETag-tagged GitHub responses and a per-source catalog snapshot. A `304 Not Modified` reuses the saved response; partial, offline, and rate-limited refreshes keep usable releases and show snapshot age.

There is no opinionated topic filter unless you turn one on — your own user / org listing already keeps the catalog tight.

---

## Where things live

| Path | Purpose |
| --- | --- |
| `<files-dir>/logs/crash.log` | On-disk crash log |
| `<files-dir>/catalog/http/` | ETag-tagged GitHub response cache |
| `<files-dir>/catalog/snapshots/` | Dated per-source offline catalog snapshots |
| `<cache-dir>/apks/` | Downloaded APKs (transient, OS-cleanable) |
| `<files-dir>/secrets/secrets.v1.tinkaead` | Tink AEAD-encrypted GitHub PATs and signing-cert pins per `applicationId` |
| DataStore `settings` | GitHub sources, topic filters, pre-release toggles |
| SharedPreferences `las_appid_cache` | Source/repository-scoped installed package, version, signer, and release-asset identity |
| SharedPreferences `queued_update_status` | Attempt count and durable queued-update terminal state |

The app declares `android:allowBackup="false"` and excludes everything from cloud / device-transfer backups — secrets stay on the device.

---

## Architecture

Single-Activity Compose app, ~2,100 lines of Kotlin. No DI framework, no Retrofit — the surface is small enough that a hand-rolled `ServiceLocator` + OkHttp is cleaner.

```
app/src/main/kotlin/com/sysadmin/lasstore/
├── data/
│   ├── GitHubClient.kt        OkHttp + kotlinx.serialization, paginated repo + release listing
│   ├── ApkInspector.kt        PackageManager.getPackageArchiveInfo → applicationId, versionCode, signing SHA-256
│   ├── InstallStateRepo.kt    PackageManager wrapper for "is X installed at version Y?"
│   ├── DeveloperVerificationPreflight.kt  Android Developer Verification advisory detector
│   ├── SecretStore.kt         Tink AEAD secret file for PAT + per-package signing pins
│   ├── AppSettings.kt         Source settings model + normalization
│   ├── SettingsStore.kt       DataStore Preferences for non-secret settings
│   ├── Logger.kt              In-memory + on-disk log with crash handler
│   └── ServiceLocator.kt      Hand-rolled DI, init from App.onCreate()
├── domain/
│   ├── AppInfo.kt             Discovered model + CardStatus enum
│   └── DiscoveryUseCase.kt    Listing → release → APK-asset picker
├── install/
│   └── PackageInstallerService.kt   Session-backed install, intent-based uninstall, launch
├── ui/
│   ├── theme/                 Catppuccin Mocha + AMOLED black dark theme
│   ├── catalog/               LazyVerticalGrid + search/filter + AppCard + StatusBadge + ViewModel
│   ├── settings/              Form + ViewModel
│   └── log/                   Live log viewer
└── App.kt + MainActivity.kt
```

The signature-pin store is keyed by `applicationId`. On a successful install we read the signing cert from the *exact APK we just installed* (not from PackageManager, which would also work but loses provenance), SHA-256 it, and store it. On every subsequent install for the same `applicationId`, we re-read the SHA-256 from the new APK's signing cert and refuse to install if it doesn't match the pin.

Developer Verification preflight runs after APK metadata inspection and before `PackageInstaller.Session.commit()`. It models verification-surface presence, registration status, and rollout applicability as separate facts. Registration remains `Unknown` because Android exposes no status capability to LocalAndroidStore. Google's [official FAQ](https://developer.android.com/developer-verification/guides/faq) says direct sideloads and stores outside its initial participating list are not subject to the 2026-09-30 regional phase; global rollout begins in 2027, with the exact date and future independent-store behavior still unpublished. The advisory is informational and never blocks installation.

The one-release EncryptedSharedPreferences migration window ended after v0.2.1, so `androidx.security:security-crypto` is no longer shipped. The plaintext emergency fallback is still migrated into Tink and cleared whenever Android Keystore becomes available again.

---

## Why not Obtainium?

Obtainium is great for what it does — point-and-shoot any GitHub release URL into a generic source list. This is more opinionated:

- Tailored UI for your catalog (a small, intentional set of GitHub users / orgs instead of a generic source-URL bag).
- Shared visual language with [LocalChromeStore](https://github.com/SysAdminDoc/LocalChromeStore).
- Signature pinning is enforced per `applicationId`, not optional.
- AMOLED-true-black + Catppuccin accents.

Use Obtainium if you want the bigger source ecosystem (F-Droid, IzzyOnDroid, html scrapers, etc.). Use this if you ship from GitHub Releases and want a clean store UI for *your* repos.

---

## Roadmap

See [ROADMAP.md](ROADMAP.md). Highlights:

- **v0.2.x** — Preapproval/constraints for update installs, UIDT download work.
- **v0.3.0** — Source plugin contract, F-Droid index consume/export, Wear OS companion, multi-device ADB pair.
- **v0.4.0** — Light theme + accent picker.

---

## Build environment

- Android Studio Ladybug+ / AGP 8.7.3 / Kotlin 2.1.0 / Compose BOM 2024.12.01
- JDK 17 (CI) or JDK 21 (Android Studio jbr)
- minSdk 26 (Android 8.0), targetSdk / compileSdk 35 (Android 15)
- Debug APK assembly, lint, unit tests, and connected-device tests are the supported automated verification path.

---

## Threat model

LocalAndroidStore is in your trust boundary — once you grant it "Install unknown apps," it can install any APK on your device. Be honest about what that means.

**What you trust:**

- **The GitHub repo owner** of every catalog source you add. If they ship malware, LAS will install it. Signature pinning catches a *change* in publisher key, not a publisher who was malicious from the start.
- **Android's maintained system CA store** for HTTPS connections to GitHub. Static CA pins were removed on 2026-07-29 after GitHub's live certificate chain no longer matched them and catalog access failed closed. Cleartext traffic remains disabled.
- **OkHttp 4.12+** — known-CVE-clean as of 2026-04-25.
- **The Android Keystore-backed Tink keyset** that protects local PATs and signing pins.
- **The Android platform's `PackageInstaller.Session` + `apksig`** for verifying signatures. Both are first-party Google code.
- **LocalAndroidStore itself.** The signed v0.2 APK is reproducible from this repo + the cert SHA-256 published in CHANGELOG. Anyone can rebuild and compare. The publisher key (`9c6a9276…e6ebd3a0d`) is the project's identity — if it leaks, the project is compromised; mitigation is rotating the key and getting users to verify the new lineage manually.

**What you don't trust:**

- A *new* publisher key on a previously-installed app. v0.2 hard-rejects an unannounced key swap. Legitimate Android Signature Scheme v3 / v3.1 rotations (pin in the new APK's signing-cert lineage) are accepted automatically and the pin rolls forward.
- A re-signed APK delivered via a hostile network. HTTPS authenticates GitHub through Android's system trust store; even if a hostile source delivered different bytes, the per-application publisher-signature pin rejects an unexpected signing key.
- A competing installer trying to silently update an LAS-installed app. v0.2 claims update ownership on first install (Android 14+), so other installers must show the user a system dialog before overwriting.
- Anything LAS-installed targeting Accessibility / Notification Listener / Device Admin without your conscious consent. v0.2 declares `PACKAGE_SOURCE_STORE` so downstream apps don't get a free pass on Restricted Settings — *you still have to flip those toggles per-app*.
- An unknown Android Developer Verification registration status. Presence of a Google verification package is reported only as capability-surface presence and never treated as proof of registration or enforcement; the platform owns the final install decision.

**What we're not in the business of:**

- We don't ship telemetry. Crash logs are local only (`<files>/logs/crash.log` + `install.log`).
- We don't run silent installs. Stock Android doesn't allow it without device-owner status; the system dialog is unavoidable on first install of every catalog app. v0.4 will offer Shizuku as an opt-in tier-2 path.
- We don't fetch a second APK at runtime. The APK staged for install is the APK published on GitHub Releases; nothing else.
- We don't share your installed-app list with anyone.

**How to verify a release yourself:**

```bash
# Compare the cert SHA-256 with the value in CHANGELOG.md
apksigner verify --print-certs LocalAndroidStore-v0.2.0.apk

# Compare the APK SHA-256 with the released sidecar
sha256sum -c LocalAndroidStore-v0.2.0.apk.sha256
```

If either fails, treat the binary as untrusted and report it.

---

## Limitations

- No silent install. Stock Android doesn't allow it for non-device-owner apps. The system install dialog appears once per install. v0.4 will add Shizuku as an opt-in tier-2 path.
- Uninstall opens the system uninstall confirmation. We can't bypass it without device-owner / Work Profile admin.
- Catalog refresh and APK download happen on-tap; v0.4 adds scheduled background refresh via WorkManager.
- Only GitHub Releases sources are supported today. F-Droid, GitLab, and HTML source plugins are planned for v0.3+.

---

## License

[MIT](LICENSE).
