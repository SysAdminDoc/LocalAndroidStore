# Changelog

## Unreleased

## v0.2.2 — 2026-05-01 — Pre-approval install flow (API 34+)

### New feature
- **`requestUserPreapproval` install flow (API 34+)** — For known updates (applicationId cached from a prior install), the system pre-approval sheet is now shown *before* the APK download begins. If the user approves, the session is held open; after download, inspection, and pinning checks pass, `commitSession()` is called and no second prompt is shown. Falls back silently to the standard `installApk` flow on older APIs or if the user declines. Key changes:
  - `PackageInstallerService`: added `createSessionAndRequestPreapproval()`, `commitSession()`, `abandonSession()`, `buildSessionParams()` shared helper, `PreapprovalSessionResult` sealed interface.
  - `CatalogViewModel.install()`: pre-approval block before download; abandons session on download/inspection failure or cancellation; uses `commitSession` vs `installApk` based on result.
  - Pre-approval is skipped for fresh installs (no cached applicationId) — those use the normal flow unchanged.

## v0.2.1 — 2026-05-01 — UpdateAvailable fix + UX polish

### Bug fix
- **UpdateAvailable never showed after cold start** — `AppInfo.applicationId` is always `null` at discovery time (by design: only available post-APK-inspection). `buildCardState()` always returned `NotInstalled`. Fixed by adding `AppIdCache` (SharedPreferences), which persists `owner/repo → applicationId + installedTagName` on every successful install. `refresh()` hydrates from the cache; `buildCardState()` compares tagName to detect available updates reliably across process restarts.

### UX polish
- **Channel labels** — release channel chip on cards whose tag or prerelease flag indicates alpha / beta / rc / nightly. Peach tinted, auto-hides for stable releases.
- **Stale release indicator** — Surface2-tinted chip on cards where the latest release is >12 months old. Encourages manual verification before installing aged APKs.
- **Release notes** — collapsible "Release notes" section on each card, populated from the GitHub release body. Hidden when the release has no body text.
- **Cancel download** — "Cancel" button appears on cards in Working state. Cancels the in-flight coroutine job, resets the card to its pre-working state derived from the cache.
- **Log clear button** — "Clear" text button in the log screen header. Visible only when the log has entries.

### Correctness
- `CancellationException` in the install pipeline is re-thrown so coroutine cancellation propagates correctly (was silently swallowed by the catch-all `Throwable` block).
- `developerVerificationNotice` is cleared on a successful install (was persisting stale warning after update).


- Multi-source GitHub settings: configure multiple user / org sources, enable or disable each source, set per-source topic and pre-release filters, and store per-source PATs with shared-token fallback.
- Catalog discovery now aggregates enabled sources, labels cards by source when needed, searches by source name, and uses source-specific credentials for private repo listing and APK downloads.
- Edge-to-edge polish: safe drawing insets at the root scaffold, IME padding for Settings, explicit dark transparent system bar styles, and API-27 navigation-bar contrast resources.
- DataStore migration hook for future settings schema changes.
- Secret storage migrated to a Tink AEAD-encrypted app-private file with a one-time legacy migration bridge for existing PATs and signing pins.
- Developer Verification preflight advisory: devices with Android Developer Verifier or Google verification services present now show a non-blocking package/signing-key registration warning before install commit, with local audit logging.
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
