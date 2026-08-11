# Changelog

## Unreleased

### Added
- Added Android TV / D-pad catalog navigation. The responsive card grid is a focus group with visible focus rings, predictable leanback spacing, accessible card descriptions, and a safe primary action for the remote select button.
- Added opt-in SOCKS5/Orbot proxy routing for GitHub, F-Droid, branding, and source-directory HTTP clients. Settings validates the endpoint, keeps it off by default, and uses a dynamic proxy selector so changes apply without rebuilding clients.
- Documented fine-grained GitHub PAT support and least-privilege read permissions. The existing Bearer API path accepts `github_pat_…` tokens without importing OAuth client credentials or requesting write access.
- Added a curated source-of-sources directory. Settings loads a bounded HTTPS JSON feed, validates unique GitHub/F-Droid definitions, and adds only explicitly selected sources without importing credentials or silently enabling the rest.
- Added portable library export and restore. A bounded `.las-library` archive contains favorites, collections, source definitions without credentials, and source-routed APK lock entries; importing merges local state and exposes a Catalog restore action that retains the normal digest, signer, package, permission, and installer gates.
- Added GitHub repository lifecycle warnings. Archived repositories remain discoverable when they publish an APK, while repositories without push activity for 12+ months receive a review warning before installation or updates.
- Added bounded Markdown release-note rendering and cumulative “What’s new since installed” views. F-Droid index-v2 `whatsNew` entries are validated and retained across the published version history; GitHub notes use the existing paged release-history budget.
- Added durable favorites, source-namespaced GitHub/F-Droid category tags, and user-defined collections. The library preference file is the only app data eligible for encrypted Android backup/device transfer.
- Added Android 15 app archiving and launcher-triggered restore handling. Archived cards retain their managed-app state, and restore requests re-enter the verified foreground install flow with durable hand-off state.
- Added bounded `.apks`, `.xapk`, `.apkm`, and `.apkset` installation. Each extracted split is
  independently verified for package, version, and publisher continuity, and a foreground checklist
  streams the selected set atomically through PackageInstaller.
- Added an APKMirror-style standalone-variant matrix with ABI, density, minimum SDK, digest prefix,
  and size columns; the device-compatible row is identified before download.
- Added long-press multi-select actions for install, staged update, and uninstall batches. Android
  uninstall confirmations are persisted and advanced one package at a time after each return.
- Added an on-device APK transparency report for installed cards. It decodes the binary manifest,
  enumerates APK Signing Block pairs, and scans DEX entries against a packaged tracker-signature
  snapshot without uploading APK bytes or querying a remote scanner.
- Added an opt-in Shizuku shell-installer tier for foreground and queued APK sessions. Settings
  detects binder availability and permission state, while the installer keeps APK/signature gates,
  falls back to Android's normal PackageInstaller when Shizuku is unavailable, and can recover
  either app-owned or shell-owned sessions after a process restart.
- Added a bounded, process-death-safe staged update batch. Card actions can add managed updates
  without starting transport work; Confirm submits persisted generations sequentially, recovers
  already-submitted actions after restart, and leaves scheduling failures staged for retry.
- Added durable per-app update cadence controls: auto (daily-cap eligible), notify-only, pinned
  priority (cap bypass), and dated holds. Settings now exposes the global daily automatic-update
  cap, and failed automatic queue reservations are released safely.
- Added a durable 24-hour WorkManager catalog check constrained to unmetered network, healthy
  battery, and available storage. It queues only changed, digest-published releases for managed
  installs through the existing APK/signature/version gates and notifies when updates are queued;
  WorkManager preserves the schedule across reboot.
- Added an atomic app-private `las.lock` capture on successful installs, recording APK/manifest hashes, version code, source URL, and signer certificate for each installed package.
- Added optional HTTPS AltStore-compatible publisher feeds with bounded icon/header loading, source tint, featured-app labels, and safe news links; branding failures leave catalog discovery intact.
- Added an opt-in Android 12+ Material You color mode. It uses the system dynamic light/dark scheme and maps custom catalog tokens to the same wallpaper-derived surfaces while leaving Catppuccin as the default.
- Added persisted Catppuccin Mocha dark and Latte light themes, eight selectable primary accents, and per-source accent overrides for GitHub and F-Droid catalog cards.
- Added per-source verification badges with an in-app explanation, an Android developer-options entry point for advanced sideloading, and a Settings preference to hide locally unverified catalog cards while leaving unknown cards visible.
- Added package-identity aggregation across configured sources with a persisted preferred-source chooser for duplicate releases.
- Added normalized release-channel derivation and per-repository channel pinning; GitHub discovery now selects the newest matching stable, beta, alpha, nightly, release-candidate, or development release within its bounded history.
- Added per-app language controls that open Android 13+'s package-scoped language settings from installed catalog cards, plus automatic locale configuration generation for future translations.
- Added a Wear OS companion module with an update-count Tile, short-text complication, phone capability registration, and user-initiated watch-to-phone refresh messages.
- Added resumable foreground APK downloads with source/release-keyed partial files, HTTP byte-range continuation, complete-file digest verification, and an explicit Resume download card action.
- Added persisted F-Droid repository settings with HTTPS-plus-fingerprint validation, signed `entry.jar` verification, catalog consumption, taxonomy-aware filter chips, and red/yellow anti-feature badges.
- Added GitLab, F-Droid index-v2, and IzzyOnDroid source-plugin adapters with HTTPS-only asset resolution, repository fingerprint TOFU validation, anti-feature parsing, and signed `entry.jar` verification.
- Added a stable four-callback `SourcePlugin` contract, duplicate-safe plugin registry, and a GitHub releases adapter over the existing bounded gateway with source-local identities and digest-aware verification results.
- Added a four-request release-lookup budget, ETag/304 response reuse, bounded jittered transient retries, typed per-source failures, partial-source preservation, and dated offline catalog snapshots.
- Added gentle queued updates with Android 14+ user-initiated jobs, WorkManager fallback, `InstallConstraints`, three-attempt retry caps, durable terminal reasons, and cancellation from the release card.
- Added source-scoped installed-app records with package, manifest version, signer, and GitHub asset identity. Tag-only changes now require APK inspection; higher version codes become updates, equal codes become explicit reinstalls, and lower codes become explicit downgrades.
- Added durable foreground install coordination across download, preapproval, permission review, and commit, including startup `PackageInstaller` reconciliation and cleanup of orphaned APK/MediaStore state.
- Made queued update generations authoritative across workers and installer callbacks. Replaced generations now reject late status/cache/pin/audit finalization and abandon their stale PackageInstaller sessions.
- Added a durable queued-install operation record with target package/version/signer evidence. Worker and UIDT restarts now reconcile a matching installed package before downloading again and complete the audit/cache transition once.
- Routed queued installs on API 26–33 through the durable manifest receiver. Pending Android confirmation is now an explicit `AwaitingUserAction` state with a notification action; background code never launches the system installer Activity directly.
- Persisted complete queued payloads and restored missing background work on app launch and `BOOT_COMPLETED`; UIDT jobs are re-enqueued only after PackageInstaller/session reconciliation and never receive a replacement generation.
- Made catalog refresh/cache writes single-flight and generation-safe. Per-target snapshot/ETag writes now use unique temporary files under a target lock, so cancelled or out-of-order refreshes cannot expose stale or partial JSON.
- Replaced hash-only UIDT JobScheduler IDs with persisted collision-aware allocation keyed by the full queued app identity; canceling one queued app now releases only its own slot.
- Added a verified-APK trust contract: `apksig` must report a supported scheme, one current signer, and a valid rotation lineage; Android's archive signer must agree before permission review or first-pin enrollment.
- Added an audited two-stage publisher-key recovery flow showing source, live installed signer, stored pin, downloaded signer, verified schemes, and lineage. It requires exact package-id entry plus independent fingerprint acknowledgement, changes only the pin, and never resumes installation automatically.
- Added a one-command trust-boundary regression matrix covering GitHub failure/retry behavior, APK asset and identity rules, installer recovery, package visibility, background-work platform branches, accessibility, 200% font scale, RTL, pseudolocales, and real API 26/35/37 emulators plus Robolectric API 32/33 contracts.
- Added a restart-safe device journal that separates bounded runtime diagnostics, install/trust audit, and crash evidence; each stream has an explicit independent clear action, and an allowlisted support ZIP removes credentials and excludes installed-app inventory before sharing.
- Tightened catalog fuzzy search so sparse subsequence matches cannot outrank precise results, while preserving acronym lookup for compact app names.
- Added source credential lifecycle safeguards: duplicate owners are rejected, renamed/removed source PAT overrides are reconciled atomically, and settings can test owner access, scopes, and rate budget without logging tokens.
- Narrowed package visibility declarations to exact validated package lookups, retaining `QUERY_ALL_PACKAGES` only for headless catalog apps that cannot be discovered through launcher visibility.
- Moved Android notification permission requests to the explicit background-update queue action; denied permission now leaves foreground installs usable and Settings provides a recovery link.
- Separated anonymous and authenticated GitHub ETag caches, rejected legacy unscoped responses, and purged source HTTP/snapshot metadata across credential and source-setting transitions.
- Added explicit APK variant selection when a release contains multiple same-rank standalone artifacts, with filename/ABI/size evidence and download/install guards until a choice is made.
- Bound foreground install and save actions to generation-safe durable ownership, unique cache targets, and installer-result capabilities so stale callbacks cannot overwrite a replacement.
- Externalized active Compose copy, accessibility labels, plural counts, APK size quantities, and locale-aware dates into Android resources for localization and pseudolocale coverage.
- Moved the build lane to Android API 37 with AGP 9.1.1, Gradle 9.3.1, Kotlin 2.4.10, built-in AGP Kotlin support, and typed Kotlin compiler options; API-37 large-screen and runtime behavior remains covered by the existing verification matrix.
- Refreshed the AndroidX, Compose, coroutine, serialization, Tink, OkHttp, and apksig dependency lines for the API-37 lane, added Gradle dependency locking, and aligned GitHub response handling with OkHttp 5's non-null response bodies.
- Removed the unused legacy catalog screen and app-card implementations so routing, previews, accessibility coverage, and production behavior share one catalog surface.
- Added explicit provenance adoption for apps installed outside LocalAndroidStore: verified observations are labeled unmanaged, signer and source evidence are shown, adoption enrolls a signer pin and audit record, and queued updates remain blocked until confirmation.
- Added bounded GitHub repository pagination with typed truncation evidence; sources now report fetched/omitted repositories and a continuation page, while truncated refreshes avoid stale snapshot backfill and recommend narrowing with a topic filter.
- Added bounded, paged historical release browsing with explicit foreground selection; selected older releases retain available version/digest/signer evidence, use the normal inspection/trust/downgrade gates, are audit-recorded, and cannot enter the background queue.

### Fixed
- Catalog branding now wraps instead of clipping at large font scales and narrow RTL layouts while preserving the 48dp refresh target.
- Publisher-trust recovery now exposes one merged checkbox control for the independent-verification acknowledgement, preventing duplicate row and checkbox activation.
- Catalog status, refresh, queued-update, and install transitions now expose deduplicated TalkBack live-region announcements; download percentages collapse to stable stages and publisher-signature blocks remain assertive.
- Synchronized the v0.2.3 release identity across the README badge, build metadata, changelog, working notes, and the new `scripts/verify-version.ps1` consistency check.
- Instrumentation visual tests now write screenshots only to app-private test cache, clean them in teardown, and support an explicit CI artifact-directory argument without publishing images to the user's gallery.
- Support and persisted-log redaction now processes complete entries before truncation, preventing bearer, GitHub PAT, URL-query, and named-credential prefixes from surviving an entry boundary.
- Settings now derives a dirty draft state from persisted normalized sources and PATs, replaces stale “Registry saved” copy with an unsaved-changes notice, and immediately reflects canonical data after a successful save.
- Catalog empty states now distinguish zero enabled sources from a successful no-release result and provide a direct Settings action plus refresh path.
- Extracted shared APK package/signing/publisher-pin verification and durable trust/cache/audit finalization services used by foreground installs, queued workers, installer callbacks, and restart reconciliation.
- Moved Settings source-PAT hydration and publisher-pin recovery secret/audit/cache work onto IO, with review revalidation, per-card busy state, duplicate-action suppression, and post-operation UI results.
- Publisher-pin replacements now render as high-risk red warnings with explicit accessibility labeling and signer-continuity review copy in the activity log.
- Failed UIDT/WorkManager scheduling now cleans up best-effort transport state, records a typed storage failure, and returns actionable failure to the catalog; cancellation failures remain retryable instead of falsely reporting success.
- Bounded queued-update status persistence to 500 recent terminal records plus all active work, moved initial load and catalog queue/cancel scheduling off the main path, and added a 10,000-status Robolectric stress check.
- Added persisted IDs to diagnostics and install-audit events, migrated legacy entries to deterministic IDs, and removed timestamp/hash-based Compose keys so identical activity rows remain distinct and stable.
- Bounded Logger startup reads to a 128 KiB tail per persisted file; oversized malformed legacy diagnostics and crash logs now retain a marked tail without loading the full file into memory.
- Made support-bundle exports unique and share-safe: prior bundles remain readable, known bundles are pruned only after a 24-hour safety window within a bounded retention budget, and share-sheet failures become visible errors.
- Made legacy Save APK destinations collision-safe with reserved unique filenames and cleanup on failed copies; repeated saves preserve earlier downloads and report the actual saved name.
- Validated GitHub repository links and wrapped installer permission, uninstall, launch, and confirmation intents with resolver checks and actionable failure results; failed launches now reconcile raced package state instead of crashing the catalog.
- Reconciled install permission and visible package state when the Activity resumes from Android Settings or an external uninstall, while preserving catalog search state and avoiding overlapping refresh jobs.
- Preserved malformed multi-source settings payloads in a recoverable backup, surfaced an actionable Settings recovery state, and blocked ordinary saves until the user explicitly replaces the unreadable registry.
- Made source-registry and per-source PAT saves transaction-backed and restart-recoverable: concurrent saves are coalesced, failures roll back or remain explicitly retryable, cache invalidation follows verified persistence, and the UI distinguishes saving, saved, and error states.
- Restricted split-config detection to strict APK asset names, so metadata such as `config.json` no longer hides an installable `base.apk` while real split APK sets remain blocked.
- Partial catalog refreshes now retain only current repositories whose release lookup failed transiently, mark those saved cards stale, and discard snapshot entries older than seven days or outside the current repository/topic candidate set.
- Restored GitHub API and release-download connectivity by removing the stale static root-CA pinset and relying on Android's maintained system trust store. Cleartext traffic remains disabled, and a regression test prevents static pins from returning.
- Corrected Developer Verification guidance: package presence, registration (`Unknown`), and rollout applicability are now separate facts, and the app no longer claims its independent sideload route is covered by the initial 2026-09-30 participating-store enforcement.
- Routed every mutable `PackageInstaller` result through an explicit non-exported receiver with a persisted random session capability, package/session validation, and single-use terminal delivery.
- Cancelling a release download now cancels the underlying OkHttp call and removes both partial and final cache files.
- Corrected rotated-key inspection to use the APK's current signer rather than the first entry in Android's signing-certificate history, and made the pin store reject blank or malformed fingerprints.
- Upgraded WorkManager from 2.11.0 to 2.11.2 and removed the completed `androidx.security:security-crypto` migration bridge.

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

## Roadmap archive — 2026-08-10 — ROADMAP.md

<details>
<summary>Original roadmap snapshot</summary>

```markdown
# Roadmap

> **Document version 2.4** • Last revised 2026-05-06 • Author: SysAdminDoc
>
> **v0.2.3 status** — Released 2026-04-29 (`tag: v0.2.3`, `versionCode = 5`, `versionName = "0.2.3"`). Shipped this cut:
>   - Item **34** (Permission diff before update) — new `CardStatus.PermissionReview` state, `PermissionDiffBlock` composable in `AppCard.kt`, dangerous-permission diff in `CatalogViewModel.install()`, search-rank boost = 6 in `CatalogSearch.searchBoost()`.
>   - Item **35** (Per-app "ignore updates") — `IgnoreListStore.kt` (DataStore-backed), respected in `buildCardState()` so ignored apps stay `Installed` rather than flipping to `UpdateAvailable`.
>   - Item **62** (Save APK without installing) — `CatalogViewModel.saveApk()` + `saveToDownloads()` (MediaStore on API 29+, app-scoped Downloads on API 26–28).
>   - Hardening pass on installer attribution (`setInstallerPackageName` / `setOriginatingUid` / `setReferrerUri`), source claim, and signature-verification cleanup over `PackageInstallerService` and `ApkInspector`.
>   - Post-release: rebrand pass (logo and launcher icon) — no behavior change.
>
> **Carry-forward into v0.2.4 (cleanup tier):** item **6** (`InstallConstraints`), item **9** (UIDT) — both still blocked on WorkManager not in the codebase; item **18** (drop `androidx.security:security-crypto` after the migration window has elapsed since v0.2.1).
>
> This is a working document, not a marketing page. Each item is tagged with **Impact (1–5)** and **Effort (1–5)** and links back to a primary source. Tier labels: **Now (v0.2.x cleanup)** / **Next (v0.3.0)** / **Later (v0.4.0+)** / **Under Consideration** / **Rejected**. Every claim cites a URL in the [Appendix](#appendix--sources).

---

## State of the repo (v0.2.3 — shipped 2026-04-29)

Three milestones have shipped against the v2.3 roadmap: **v0.2.0** (hardening pass), **v0.2.1** (multi-source / search / Tink / edge-to-edge / Dev Verification preflight), **v0.2.2** (`requestUserPreapproval`), **v0.2.3** (permission diff + ignore list + save APK + installer-attribution polish). Resulting v0.2.3 ships:

- GitHub-Releases discovery for **multiple** configured users / orgs, per-source PAT (Tink AEAD-encrypted), per-source topic filter, per-source pre-release toggle.
- Catalog grid (`LazyVerticalGrid`, 320 dp adaptive) with status badge (`NotInstalled / Installed / UpdateAvailable / Working / Error / SignatureMismatch / PermissionReview`), install / uninstall / update / open / cancel / save-APK / repo buttons, channel labels (alpha / beta / rc / nightly), stale-release indicator (>12 months), collapsible release notes, and per-app ignore-updates.
- Search across name / owner / handle / description / tag / version / package id with exact + prefix + subsequence ranking and `searchBoost()` per status.
- Install via `PackageInstaller.Session` with `setRequestUpdateOwnership(true)` (API 34+) on first install, `setPackageSource(PACKAGE_SOURCE_STORE)` on every session, explicit installer attribution (`setInstallerPackageName` + `setOriginatingUid` + `setReferrerUri`), `requestUserPreapproval()` two-phase flow on known updates (API 34+), and decoded `STATUS_FAILURE_*` messages.
- Lineage-aware signature pinning per `applicationId` via Google `apksig` 8.7.3 — accepts legitimate APK Signature Scheme v3 / v3.1 rotations through `signingCertificateHistory`, hard-rejects unannounced key swaps, surfaces a "Key mismatch" card.
- Permission-diff gate: dangerous permissions added by an update render a `PermissionReview` state with the diff inline before the user can commit; cleared on successful install.
- Installed-state via `PackageManager`; remote-vs-local `versionCode` flips the badge to "Update available"; `AppIdCache` (SharedPreferences) persists `owner/repo → applicationId + installedTagName` so update-available state survives cold start.
- Tink AEAD secret file (`<files>/secrets/secrets.v1.tinkaead`) stores PATs and signing pins under an Android-Keystore-protected keyset; one-time migration bridge from EncryptedSharedPreferences runs on first launch.
- Network Security Config pinning `api.github.com`, `objects.githubusercontent.com`, `codeload.github.com`, `raw.githubusercontent.com` at the **root CA SPKI** (DigiCert Global Root G2 + ISRG Root X1 backup), 6-month expiration, cleartext disabled.
- Developer Verification preflight: detects `com.google.android.verifier` / Google verification services and surfaces a non-blocking advisory before commit.
- `<files>/logs/install.log` JSONL audit trail (rotates at 256 KB), `<files>/logs/crash.log` global uncaught handler, in-app live log viewer with clear button.
- Edge-to-edge under target API 35 with `WindowInsets.safeDrawing` + `imePadding()` + explicit dark transparent system-bar styles + `values-v27` nav-bar contrast split; predictive back opted in on `<application>`; monochrome adaptive-icon layer.
- AMOLED-true-black + Catppuccin Mocha theme, rebranded launcher / download-arrow icon (post-v0.2.3 commits).
- WorkManager `FOREGROUND_SERVICE_DATA_SYNC` + `POST_NOTIFICATIONS` + `ENFORCE_UPDATE_OWNERSHIP` permissions declared, ready for the v0.4 background-update worker.
- Save-APK-without-installing: MediaStore Downloads on API 29+, app-scoped Downloads on API 26–28.
- Resume / cancel of in-flight downloads (cancel only — resume across process death is item 36, still pending).
- DataStore migration framework hook installed.
- CI-signed release APK + sha256 sidecar (`KEYSTORE_BASE64` secret); branch protection on main with `enforce_admins=true`.
- OkHttp 4.12.0 pinned with `resolutionStrategy.eachDependency` floor to prevent transitive downgrade. apksig 8.7.3, kotlinx.serialization 1.7.3, kotlinx-coroutines 1.9.0, AGP 8.7.3, Kotlin 2.1.0, Compose BOM 2024.12.01, target/compileSdk 35, minSdk 26.

**It does not yet:** support `InstallConstraints` for gentle background install (item 6, blocked on WorkManager); use `setUserInitiated(true)` UIDT for the download Worker (item 9); ship a `WorkManager` periodic update check (item 46); resume interrupted downloads across process death (item 36); support split / XAPK / APKM / AAB bundle install (item 55); render an APKMirror-style variant matrix (item 54); ship Material You / light theme / accent picker (items 40, 41); expose Wear OS / Android TV / desktop ADB-pair surfaces (items 29, 30, 65); emit or consume an F-Droid index v2 (items 26, 27); support GitLab / IzzyOnDroid / generic plugin sources (item 25); offer Shizuku silent install (item 50); offer reproducible-build verification or a Minisign-signed catalog (items 43, 44, 45); show anti-features (item 33); display tracker / manifest viewers (item 51); rollback on crash-correlated regression (item 48).

**Stated philosophy** (preserved from README, unchanged through v0.2.3):

1. Personal store, GitHub-Releases-first, opinionated — not a generic source bag.
2. Signature pinning is mandatory, never optional, never silently overridden.
3. AMOLED-true-black + Catppuccin is the visual identity. Light theme is a future option, not the default.
4. Single-Activity Compose, hand-rolled `ServiceLocator`, no Hilt, no Retrofit. Surface area stays small.
5. No silent install on stock Android (we are not a device-owner). Privileged paths (Shizuku) are tier-2 add-ons, never required.
6. MIT license. No Co-Authored-By, no AI-attribution in committed files.

**Strategic frame from the research.** Five existential platform shifts dominate every decision below: **(a)** Android 14's `setRequestUpdateOwnership` lets a curated installer claim the update channel and is the missing half of the signature-pin guarantee — without it, a competing installer can silently overwrite our pinned apps [1, 6, 23]. **(b)** Android 15's `PACKAGE_SOURCE_STORE` exempts our downstream apps from Restricted Settings (Accessibility / Notification Listener unlock), directly improving every Accessibility-using app installed via LAS [22, 53]. **(c)** Android 14's mandatory foreground-service-type and Android 15's 6-hour FGS cap force any background updater to be UIDT-based (`setUserInitiated(true)`), not `dataSync`-based [3, 24]. **(d)** Android Developer Verification is now in phased rollout: the `com.google.android.verifier` system app appeared April 2026, the **"advanced sideloading flow"** with a 24-hour wait period launches August 2026, and enforcement begins Sept 30, 2026 in BR/ID/SG/TH [21, 51, 65, 213, 214] — every catalog APK from an unverified developer becomes uninstallable on certified devices unless the user walks the per-install "advanced flow," and surfacing this in our catalog *before* users hit the wall is differentiated UX nobody else ships. **(e)** APK Signature Scheme v3 / v3.1 key-rotation lineage means a naive SHA-256 pin will incorrectly reject *legitimate* publisher rotations — we must walk `signingCertificateHistory` before we reject [13, 19].

The single biggest user pain across 158 community sources [E1–E158] is **gentle background updates without prompting** (Obtainium #2199, #1550, #1105; SmartTube #4151; r/degoogle 1sds2ph). The single biggest competitive gap remaining as of v0.2.3: **no personal-GitHub-catalog tool ships native cert pinning baked into the install flow** with a *permission diff* and *ignore list* like ours (Obtainium delegates pinning to AppVerifier, F-Droid does it via index `AllowedAPKSigningKeys`, Accrescent does it for a curated catalog). LAS now closes that gap; v0.3 has to defend the next axis (multi-source plugin contract + F-Droid emit) before competitors copy.

**Out of scope, permanently.** Anything that requires us to be a device-owner / Work Profile admin (silent install of arbitrary publishers' apps), anything that pushes binary code from a Telegram channel as a primary source, anything monetized, anything that sends device telemetry to a server we run, anything that scrapes the user's installed-app list to show ads.

---

## Themes (cross-cutting concerns surfaced by the research)

Every Now/Next/Later item maps to one or more of these. Items that don't map get challenged.

- **T-SEC** Security & integrity — pinning, lineage, reproducible builds, signed indexes, secret storage, NSC.
- **T-INSTALL** Install mechanics — Session API, ownership claim, package-source, constraints, preapproval, archive, split/bundle.
- **T-UPDATE** Background updates — WorkManager, FGS-type, UIDT, gentle updates, per-app cadence, user override.
- **T-CATALOG** Catalog UX — search, categories, tags, collections, branding, channel labels, anti-features, variant matrix, release-notes display.
- **T-SOURCES** Source plugin architecture — multi-source, manifest format, F-Droid index v2 emit & consume, source-as-publisher.
- **T-COMPLIANCE** Platform compliance — Android 14/15/16 mandates, Developer Verification, Restricted Settings, predictive back, edge-to-edge.
- **T-COMPANION** Multi-device — Wear OS, ADB pair / Wireless Debugging, library sync.
- **T-A11Y** Accessibility & i18n — per-app locale, TalkBack, large-screen, reduced motion, color contrast.
- **T-OBS** Observability — install audit log, crash correlation, ANR rollback prompt, Prometheus-style local metrics.
- **T-TEST** Testing — golden-flow Espresso, ApkInspector unit fixtures, CI matrix on multiple SDKs.
- **T-DOCS** Documentation — manifest spec, threat model per source, "advanced flow" walkthrough, release-notes template.
- **T-DIST** Distribution & packaging — F-Droid third-party repo emission, Minisign signed catalog, IzzyOnDroid mirror, RB badge.
- **T-PLUGIN** Plugin ecosystem — source plugins, mise-style 4-callback contract, GOG-Galaxy-style integration SDK.
- **T-OFFLINE** Offline & resilience — cached catalog, partial sync, resume-interrupted-download, queue persistence across process death.
- **T-MIGRATE** Migration & upgrade — DataStore migrations, lockfile portability, library export/restore.

---

### Shipped through v0.2.3 — reference list (do not re-do)

Every numbered item from this list shipped in some v0.2.x. Cross-references are kept intact so later tiers can still reference them.

- **v0.2.0 (2026-04-25)** — items **1** (update-ownership claim), **2** (`PACKAGE_SOURCE_STORE`), **3** (installer attribution), **4** (lineage-aware signature verification via apksig), **7** (decoded `STATUS_FAILURE_*` messages), **8** (`FOREGROUND_SERVICE_DATA_SYNC` + WorkManager FGS-type override), **12** (monochrome adaptive icon), **13** (`POST_NOTIFICATIONS` permission declared), **16** (Network Security Config root-CA SPKI pinning), **22** (install audit log on disk).
- **v0.2.1 (2026-05-01)** — items **10** (edge-to-edge audit), **11** (predictive back enabled), **14** (system-bar contrast tokens + `values-v27` split), **15** (Developer Verification preflight + warning UX), **17** (OkHttp ≥ 4.12.0 build-fail comparator), **18** (active secret storage migrated to Tink AEAD; `security-crypto` retained as migration bridge only), **19** (`dataExtractionRules` audit), **20** (multi-org / multi-source UI), **21** (search + fuzzy filter), **23** (DataStore migration framework hook), `AppIdCache` for cold-start update detection, channel labels, stale-release indicator, release-notes section, log clear button, `CancellationException` correctness fix.
- **v0.2.2 (2026-05-01)** — item **5** (`requestUserPreapproval()` two-phase install flow on API 34+).
- **v0.2.3 (2026-04-29)** — item **34** (permission diff before update; `CardStatus.PermissionReview` + `PermissionDiffBlock`), item **35** (per-app "ignore updates" via `IgnoreListStore`), item **37** (cancel in-flight download via coroutine cancellation + cancel button on `Working` cards), item **62** (save APK without installing — MediaStore on API 29+, app-scoped Downloads on API 26–28), item **24** (README threat-model section), `IgnoreListStore.kt` + installer/source-claim hardening pass + signature-verification cleanup.

---

## Next — v0.3.0 "Multi-source + companion devices"

Theme distribution: **T-SOURCES × 4, T-COMPANION × 3, T-CATALOG × 4, T-INSTALL × 2, T-PLUGIN × 1, T-OFFLINE × 2, T-A11Y × 2, T-COMPLIANCE × 1.**

**Frame:** v0.3 turns LAS from a single-GitHub-user catalog into a multi-source store. The plugin contract lands here. The Wear OS surface and the desktop ADB-pair sibling land here. The Android Developer Verification "advanced sideloading flow" walkthrough (item 73) lands here — by August 2026 every unverified APK we surface needs an in-app explainer for the new system flow, or our Catalog cards become tombstones the user can't act on. Some "Later" items get pulled forward if the v0.2 release feedback warrants.

### Theme: Sources & plugin architecture

25. **Source plugin contract (4-callback)** [I 5 / E 3] — define `interface SourcePlugin { suspend fun listApps(): List<DiscoveredApp>; suspend fun getReleases(applicationId): List<Release>; suspend fun resolveDownloadUrl(release): String; suspend fun verify(release): VerifyResult }`. Initial impls: `GitHubReleasesPlugin` (current code), `GitLabReleasesPlugin`, `IzzyOnDroidPlugin`, `FDroidIndexV2Plugin`. Borrowed from mise's 4-script contract and GOG Galaxy 2.0's integration SDK shape. Sources: [Agent C §1.13, §1.16, §2 plugin patterns].
26. **F-Droid index v2 consume** [I 4 / E 3] — full `index-v2.json` parser via kotlinx.serialization. Repo-add UX: paste URL with `?fingerprint=...` query param → TOFU-with-fingerprint validation. Signed-index validation via JAR signing of `entry.jar`. Surfaces anti-features as filter chips. Sources: [34, 35, 36].
27. **F-Droid index v2 emit (`Publish my catalog as an F-Droid repo`)** [I 5 / E 4] — opt-in setting that turns the LAS catalog into a third-party F-Droid repo (`index-v2.json` + signed `entry.jar` + `repo/` tree of APKs). Hostable on GitHub Pages. **The cheapest moat in the project**: every F-Droid client (F-Droid client, Droid-ify, Neo Store, Obtainium) consumes our catalog with zero further work. `pip install fdroidserver`-equivalent Kotlin pipeline (or shell out to `fdroid update` if a Python sidecar is acceptable). Sources: [Agent A §13, 34, 37, F-Droid index spec, A117, A118].
28. **Multi-source aggregation in Catalog** [I 4 / E 3] — Catalog grid groups apps by `applicationId`; if the same app is published in multiple sources, show all + let the user pin "preferred source" per app (F-Droid #2724 / Droid-ify #713). Conflict resolution: pinned source wins; if pinned source has no version that meets minSdk, fall back. Sources: [E97, Droid-ify #713, Neo-Store #722].

### Theme: Companion devices

29. **Wear OS Tile + Complication** [I 3 / E 4] — pair via `CapabilityClient.addLocalCapability("local_android_store_phone")`. Tile shows "N updates available"; Complication for chronic dial users. `MessageClient` back-channel "start update on phone" (no on-watch APK install — Wear OS doesn't sideload APKs). `androidx.wear.tiles:1.5.0` + `protolayout:1.3.0`. Pin protobuf-javalite ≥ 4.28.2 (CVE-2024-7254). Sources: [38, 39, 40, Agent D §5, §15].
30. **Desktop sibling — ADB pair + push (`las-pair`)** [I 4 / E 4] — small Kotlin/JVM CLI: discovers the phone via mDNS `_adb-tls-pairing._tcp.local.`, renders a QR with `WIFI:T:ADB;S:<name>;P:<password>;;` schema, drives `adb pair` then `adb install` of any APK from the user's catalog. Shipped as a JAR + native-image launcher. Solves Sideloadly's pain ([Agent C §1.9]) on Android. Sources: [41, 42].
31. **Multi-device push** [I 3 / E 3] — once paired, "install on all devices" mass action — pick N devices, queue installs, single status sheet. Sideloadly pattern. Sources: [Agent C §3.7].

### Theme: Catalog UX (deeper)

32. **Channel labels (stable / beta / alpha / nightly)** [I 4 / E 2] — derive from `prerelease=true` plus tag substring (`/(alpha|beta|rc|nightly|dev)/i`); per-app channel pin in app detail. Borrowed from Snap (track / risk) and APKMirror's vocabulary. Sources: [Agent B §2.5, Agent C §2 channels].
33. **Anti-features taxonomy display** [I 3 / E 2] — when consuming F-Droid index v2 (item 26), surface F-Droid's 10 anti-features (`Ads`, `Tracking`, `NonFreeNet`, `NonFreeAdd`, `NonFreeDep`, `NonFreeAssets`, `UpstreamNonFree`, `NoSourceSince`, `KnownVuln`, `DisabledAlgorithm`, plus the 2024 `TetheredNet`) as filter chips on Catalog and as red/yellow badges on app rows. Sources: [44].
34. **Permission diff before update** [I 4 / E 2] — ✅ **Done in v0.2.3.** When a queued update requests *new* dangerous permissions vs the installed version, the install is held behind a `CardStatus.PermissionReview` state with the `PermissionDiffBlock` composable rendering the diff inline; user must tap "Install anyway" to proceed. `developerVerificationNotice` and `newDangerousPermissions` are cleared on success. Sources: [Agent A §4 Neo Store CHANGELOG 1.2.5, Droid-ify v0.7.1].
35. **Per-app "ignore updates" / hide from view** [I 2 / E 1] — ✅ **Done in v0.2.3.** `IgnoreListStore` (DataStore) tracks `applicationId`s the user has marked "ignore updates"; `buildCardState()` keeps those apps in `Installed` rather than flipping to `UpdateAvailable`. Toggle exposed on each app card. Sources: [F-Droid #1908, Neo-Store #262].

### Theme: Offline & resilience

36. **Resume-interrupted-download** [I 4 / E 2] — OkHttp `Range: bytes=N-`; persist partial on `cacheDir/apks/.partial/`; surface "Resume download" on the card. Accrescent #10. Sources: [Accrescent #10, Obtainium implicit].
37. **Cancel in-flight download** [I 3 / E 1] — ✅ **Done in v0.2.1.** Coroutine-cancellation through the OkHttp call; X button on `Working` cards. `CancellationException` re-thrown so cancellation propagates correctly. Sources: [E124, Obtainium #950].

### Theme: Accessibility & i18n

38. **Per-app language preferences** [I 3 / E 2] — `LocaleManager.setApplicationLocales(LocaleList.forLanguageTags(...))` per-row. Auto-generate `LocaleConfig` via `androidResources { generateLocaleConfig = true }`. Future-proofs future translation work. Sources: [45].
39. **Large-screen / fold layout** [I 3 / E 3] — `WindowSizeClass` driven layout: list + detail two-pane on `Expanded`. Catalog adapts to fold hinge using `androidx.window:1.3+`. Accrescent #328 / Neo Store #297. Sources: [Accrescent #328].

### Theme: Compliance (forward-dated)

73. **Advanced sideloading flow explainer + per-source verification badge** [I 5 / E 3] — Android's "advanced sideloading flow" launches August 2026; on certified devices, every install of an APK whose `(applicationId, signing cert)` is not registered with Google triggers a multi-step authentication + 24-hour wait period before the user can install. v0.2.1's Developer Verification preflight (item 15) detects the verifier; v0.3 adds the *user-facing* explainer: per-source "Verification status" badge (Verified / Unverified / Unknown), an in-app explainer when the user taps the badge, a one-tap deep-link into the system advanced flow, and a Settings toggle "Hide unverified sources" for users who prefer the cliff to the warning. Without this, every Catalog card from a hobbyist publisher becomes a tombstone the user can't act on. Sources: [21, 51, 65, 213, 214].

---

## Later — v0.4.0 / v0.5.0 / v0.6.0

Items here are intentionally NOT scoped to a single version yet. They get assigned during v0.3.0 retro based on user feedback and platform changes.

### Theme: Theming + visual identity (was v0.4.0 in v0.1's roadmap — kept here, deepened)

40. **Light theme + accent picker** [I 3 / E 3] — Catppuccin Latte light variant; accent picker (Mauve / Sapphire / Green / Yellow / Red / Pink / Teal / Lavender). Per-source accent color override (AltStore source-tint pattern). Sources: [Agent C §1.8, Droid-ify #1088].
41. **Material You / dynamic color (Android 12+)** [I 2 / E 2] — opt-in. Off by default — our visual identity is Catppuccin, not the user's wallpaper. Sources: [Aurora 4.6.3 release].
42. **Per-source branding (icon, header image, tint, news feed)** [I 3 / E 3] — adopt the AltStore `.altsource` source-shape: source has `iconURL`, `headerURL`, `tintColor`, `featuredApps`, `news[]`. Catalog grouped by source becomes a publisher feed. Source: [Agent C §1.8 AltStore patterns, §3].

### Theme: Distribution & verification

43. **Reproducible-build badge** [I 4 / E 4] — for catalog APKs that ship `META-INF/version-control-info.textproto` (AGP 8.3+), optionally rebuild from the referenced commit + `apksigcopier` to transplant the publisher's signature, byte-compare. Green shield + linked proof log. Borrow `rbtlog` ([codeberg.org/IzzyOnDroid/rbtlog](https://codeberg.org/IzzyOnDroid/rbtlog)). Sources: [46, 47, A116].
44. **Minisign-signed catalog manifest** [I 3 / E 2] — sign LAS's own manifest format with Ed25519/Minisign. Tiny key, tiny signature, no GPG. F-Droid index v2 emission stays JAR-signed for client compat. Sources: [48, 49].
45. **APK SHA-256 lockfile (`las.lock`)** [I 4 / E 2] — capture `(applicationId, versionCode, apkSha256, certSha256, sourceUrl, manifestSha256)` per installed app. Cargo-style. Reproducible-restore guarantee for "set up a new device with the same library". Sources: [Agent C §2 hash-pinning, Agent C §1.14].

### Theme: Background updates (deeper)

46. **WorkManager periodic check** [I 5 / E 2] — moved to v0.4 because v0.2 is hardening-only; the actual scheduled check is a feature, not a mandate. 24h `PeriodicWorkRequest`, `UNMETERED + battery-not-low + storage-not-low`. Persistent across reboots. Surfaces as a notification when at least one update is queued. (Was v0.2 in v0.1's roadmap; deferred because the FGS-type plumbing in items 8–9 is the pre-req and lands first.) Sources: [50].
47. **Per-app cadence + global cap** [I 3 / E 2] — Snap-style per-app `auto / notify / pinned / held-until=DATE`; global cap of N updates per day. Sources: [Agent C §1.6].
48. **Crash-correlated rollback prompt** [I 4 / E 4] — hook `ApplicationExitInfo`; if a freshly-installed update produces N ANRs/crashes within M minutes, prompt one-tap rollback to the kept-on-disk previous APK. *Platform `RollbackManager` is `@SystemApi` so we can't drive a real rollback — what we ship is `uninstall + reinstall previous APK`, which loses data. Surface that loss-of-data warning honestly.* Sources: [Agent D §1.6, TestFlight pattern, Agent C §1.17].
49. **Persistent download queue across process death** [I 3 / E 2] — borrowed from Cydia/Sileo's queue model: stage N actions, "Confirm" runs them as one batched session. Sources: [Agent C §1.11].

### Theme: Power-user paths

50. **Shizuku-mode silent install** [I 4 / E 4] — opt-in tier-2 install path: detect Shizuku binder, offer a "no-prompt install" toggle in Settings. Uses `ShizukuBinderWrapper(SystemServiceHelper.getSystemService("package"))` → `IPackageInstaller` AIDL. Shell UID 2000 = no `REQUEST_INSTALL_PACKAGES` needed; bypasses the per-package update-ownership prompt for *third-party* installers. Persistent-across-reboots requires Sui/Magisk; otherwise user re-pairs Shizuku via `adb shell sh /sdcard/Android/data/moe.shizuku.privileged.api/start.sh` after each reboot. Sources: [52, 53, 54, Droid-ify].
51. **Manifest viewer / signing block viewer / tracker scan** [I 3 / E 3] — TrollStore-pattern transparency. Each catalog detail page has "Show raw `AndroidManifest.xml`" + "Show signing block" + "Tracker scan" (via local Exodus DB clone). AppManager already does this — power user crowd notices it immediately. Sources: [Agent A §10, Agent C §3.5, §3.6].
52. **Batch operations (multi-select install / uninstall / update)** [I 3 / E 3] — Cydia/Sileo queue UX. Long-press card → enter selection mode; bottom action bar with "Update all selected", "Uninstall all selected". Sources: [Agent C §1.11, Accrescent #91].
53. **VirusTotal scan badge** [I 3 / E 2] — cache by APK SHA-256 (free VT public API, 4 lookups/min). Surface "0/72 detections" or "3/72" with link to detail. Off by default (privacy: hash leaks to a third-party). Chocolatey pattern. Sources: [Agent C §1.4, Obtainium #462].

### Theme: Catalog UX (deeper)

54. **Variant matrix UI** [I 4 / E 4] — APKMirror-style table: ABI × DPI × min-SDK × signature-id columns. Auto-highlight the row matching the connected device's `Build.SUPPORTED_ABIS[0]` + `displayMetrics.densityDpi` + `Build.VERSION.SDK_INT`. Required when we add split / XAPK / APKM support (item 55). Sources: [Agent B §1.2, §2.1].
55. **Split APK / APK-set / XAPK / APKM / AAB bundle install** [I 4 / E 4] — adopt the **Ackpine** library or hand-roll `ZippedApkSplits`. Per-split selection UI. AAB requires shelling out to bundletool's `extract-apks` (or pre-extracting in CI before catalog publication). Top-of-list community ask. Sources: [Obtainium #795, #682, Agent D §1.7, §1.8, A98].
56. **`PackageInstaller.requestArchive()` UX** [I 3 / E 3] — Android 15+. "Archive" replaces APK with a stub icon, keeps user data; tapping the stub re-fetches and re-installs. **First-mover gap** — Obtainium #2042 / Droid-ify #842 still open or just-closed. Source: [55].
57. **Categories + collections + favorites** [I 3 / E 2] — namespaced tags (Cydia `purpose::*`, Itch's user collections). User-defined collections that survive reinstall. Sources: [F-Droid #1002, F-Droid #94, Droid-ify #34, Agent C §1.16].
58. **Release-notes display + cumulative "what's new"** [I 4 / E 2] — markdown render of `release.body`; concatenate notes for `installedVersion+1..latest` for "since you last installed" diff. Mandatory whatsNew validator on F-Droid index emit (item 27). Sources: [F-Droid #2534, Obtainium #572, Agent C §3 release-notes].
59. **Dead-repo / archived-source warning** [I 2 / E 1] — surface "this repo has had no commit in 12+ months" or "this repo is archived" badge from GitHub API. Obtainium #1753. Sources: [Obtainium #1753].

### Theme: Companion / federation

60. **Library export / lockfile-style restore** [I 4 / E 3] — one-tap "export library" → encrypted blob (or plain `.las-versions` lockfile + sources.json). Restore on a new device pulls APKs from sources to re-create the same set. F-Droid #1484 / Droid-ify #145 / Accrescent #556 / Obtainium #2013. Sources: [E99, F-Droid #1484, Accrescent #556].
61. **Source-of-sources meta-feed** [I 2 / E 2] — a top-level curated `sources-of-sources.json` that LAS knows about (LAS-curated, not auto-discovered). User adds a meta-feed once and discovers individual sources from inside the app. Cydia "Recommended Sources" pattern. Sources: [Agent C §1.11, §2 source-of-sources].

### Theme: Power-user paths (continued)

62. **Save APK without installing** [I 2 / E 1] — ✅ **Done in v0.2.3.** `CatalogViewModel.saveApk()` + `saveToDownloads()` writes via MediaStore `Downloads` on API 29+, app-scoped `Downloads/` on API 26–28. Long-press → "Save APK". Obtainium #29. Sources: [Obtainium #29].
63. **GitHub fine-grained PAT support + OAuth** [I 3 / E 3] — Obtainium discussion #2722, #2644. Move from classic PAT to fine-grained; document required scopes. Sources: [Obtainium #2722, #2644].
64. **SOCKS5 / Orbot proxy support** [I 2 / E 2] — OkHttp `Proxy(SOCKS, ...)` configurable in Settings. Obtainium #121. Sources: [Obtainium #121].

### Theme: Wear / TV / Auto

65. **Android TV / DPad navigation** [I 4 / E 3] — Compose TV API (`androidx.tv.material3`); leanback-friendly card grid. **Top-of-list ask across three trackers** (Obtainium #281 = 40 reactions, Neo-Store #160, F-Droid #1767 = €50 bounty). Sources: [E118, Obtainium #281, F-Droid #1767, Neo-Store #160].
66. **WearOS APK push (companion)** [I 2 / E 4] — push an APK to a paired Wear OS device via the `MessageClient` channel; Wear OS sideload manager runs the actual install on the watch. Reddit r/WearOS 1pc262b. Sources: [E155].

### Theme: Accessibility (deeper)

67. **TalkBack audit + content-description sweep** [I 3 / E 2] — every interactive element has `Modifier.semantics { contentDescription = ... }`; status badges read out cleanly; announce queued operations. Audit using TalkBack on a test device.
68. **Reduced-motion / prefers-reduced-motion** [I 2 / E 1] — honor `Settings.Global.ANIMATOR_DURATION_SCALE == 0` to disable card-shimmer / progress-pulse animations. Sources: [Agent A §13 stylistic patterns].
69. **High-contrast color tokens** [I 2 / E 2] — Catppuccin tokens with WCAG AA contrast verified; "high contrast" toggle that promotes Subtext → Text on any 12 pt body run.

### Theme: Distribution / packaging

70. **F-Droid main-repo submission** [I 3 / E 4] — submit LAS itself to f-droid.org main repo. Requires reproducible-build recipe (`fdroiddata` metadata YAML, `Builds:` block, `AllowedAPKSigningKeys:` with our cert SHA-256). Differentiates from "yet another GitHub-only sideload tool". Sources: [F-Droid Build Metadata Reference, A116].
71. **IzzyOnDroid mirror submission** [I 3 / E 1] — IzzyOnDroid mirrors developer-signed APKs and runs them through `rbtlog` for the green RB shield. Submission is a small PR. Sources: [A116, A39].
72. **Documented threat model per source** [I 3 / E 2] — each LAS source carries a one-paragraph `threatModel` markdown blob: who controls the cert, what happens on key leak, what's verified. TrollStore pattern. Sources: [Agent C §3.12].

---

## Under Consideration

These are real signals but uncertain fit, novel risk, or platform-fragility. Not committed. Each is annotated with the question we'd need to answer before moving it to Later.

- **U1. Telegram / SourceForge / itch.io as sources.** [Q: do users *of LAS specifically*, who are GitHub-first, actually want non-GitHub sources beyond GitLab + IzzyOnDroid?] Source: [Obtainium #1, #1423].
- **U2. Aurora-Store-style anonymous Play Store frontend.** [Q: re-distributing Play APKs at scale violates Play TOS; the legal path is "fetch and install on the user's device only", which is exactly what Aurora does. Worth the legal surface?] Source: [Agent A §7].
- **U3. AppCoins / monetization rails.** Rejected on philosophy grounds (Universal Rules: "No telemetry beyond direct connection to GitHub"); kept here only to make rejection explicit. Source: [Agent B §4].
- **U4. ML-based "apps you might like".** [Q: would a co-installation graph from anonymized opt-in users provide signal worth the privacy surface?] Source: [Agent C §1.15 Steam friends-rec].
- **U5. Sandboxed web-app launcher (Native Alpha pattern).** [Q: is this our scope, or someone else's?] Source: [E96, r/androidapps 1kv5hbb].
- **U6. Remote install via web browser → device push.** APKPure Universal Manager has this. [Q: if v0.3 ships ADB-pair desktop sibling, do we need a web-side path too?] Source: [Agent B §1.3].
- **U7. Decentralized / Nostr-backed app distribution (Zap.Store).** [Q: does federation solve a problem GitHub Releases hasn't already solved for our user?] Source: [E122, A102].
- **U8. AppCenter / TestFlight-style "tester groups".** [Q: do we have multi-user enough demand?] Source: [Agent C §1.17].
- **U9. Plexus / Exodus integration for Google-Play-Services compatibility scoring.** [Q: most LAS apps are sideload-friendly already; how often does this signal actually fire?] Source: [Agent A §7 Aurora].
- **U10. F-Droid main-repo inclusion of LAS itself.** Listed above as item 70 (committed Later); kept here to flag the open question of whether the F-Droid team will accept a project whose primary feature is third-party-source aggregation. Source: [E134].
- **U11. Per-device install profile (work / personal).** [Q: real demand or imagined?] Source: [Agent C §1.13 mise].
- **U12. Federated review feed via source `reviewsUrl`.** [Q: opens a review-bombing surface; do we want to be in that game?] Source: [Agent C §2 ratings].
- **U13. Pre-registration / "watch this repo for first release".** GitHub Releases Atom feed makes this trivial — but is it just notification spam? Source: [Agent B §3.14].
- **U14. ChannelClient / Wear OS APK install over BLE.** [Q: Google's Wear APK Install passed Play tests; if the platform supports it, do we get it for free, or do we need watch-side Compose surface?] Source: [E155].
- **U15. Steam-style "library generations" for per-batch rollback.** [Q: does Nix's generation model translate to mobile, or is per-app rollback enough?] Source: [Agent C §1.7].

## Rejected (with reasons)

These came up in research and will not ship. Stated up-front to prevent silent resurrection.

- **R1. AAB direct install in the app.** Android cannot install AAB directly. Pre-extract via bundletool in CI before catalog publication; install the resulting split APKs. *(Item 55 covers the split-APK install path; AAB-as-a-format is rejected.)*
- **R2. Telemetry / analytics SDK (any kind).** Violates the Universal Rule "No telemetry beyond direct connection to GitHub." Crash logs are local-only; we never ship them off-device.
- **R3. In-app monetization / ad-supported tier.** Violates project philosophy. The Amazon Appstore evidence ([Agent B §1.5]) shows the trust-erosion path.
- **R4. SafetyNet attestation.** Removed in 2024. Play Integrity is its replacement and is not adopted here — it requires Play Services on the device and would gatekeep the very GrapheneOS / de-Googled audience the project serves. Sources: [162, 163].
- **R5. Self-update via in-app `dex`-loading.** Stock Android forbids it; Google Play Protect explicitly flags it as a malware indicator. Self-update only via the same `PackageInstaller.Session` flow our publishers use.
- **R6. APK runtime fetch (one APK loads a second APK from the network).** Same reason as R5 — the staging-loader pattern is exactly the malware vector banking trojans use. Our threat model bans it.
- **R7. Cydia Substrate / Frida / Xposed style runtime hooks.** Out of scope. MMRL covers this category — point users there. Source: [A91].
- **R8. Bundle the Tor binary for SOCKS5.** Item 64 (proxy support) is on; bundling Tor itself is not. Document the Orbot integration path.
- **R9. iOS port.** Not Android; categorically out of scope.
- **R10. Generic "any URL is a source" HTML scraper.** Obtainium ships this. We are deliberately the opinionated GitHub-Releases-first store. Re-add only if v0.3's plugin contract makes it trivial.
- **R11. Single-Activity → multi-Activity refactor "for separation of concerns".** Project philosophy says single-Activity Compose. Reject premature.
- **R12. Hilt / Dagger / Koin / DI framework adoption.** Hand-rolled `ServiceLocator` is part of stated philosophy. Reject premature.
- **R13. Retrofit / Moshi.** OkHttp + kotlinx.serialization is sufficient for the API surface we have. Reject premature.
- **R14. Co-Authored-By trailers anywhere.** Universal rule.
- **R15. Anything that installs apps without showing the user the system dialog at least once.** Stock Android does not allow it without device-owner. Shizuku (item 50) is the documented escape hatch and surfaces the choice to the user.
- **R16. Comprehensive automated test suite.** The user's project-wide rule is "no tests unless explicitly requested." We add unit fixtures only when a regression is detected and the fix needs a guard. CI matrix on Android API 26 / 31 / 33 / 34 / 35 (build-only, not instrumentation) is the minimum and is part of every milestone DoD; that is the floor, not a ceiling.

---

## Key dependencies + cross-cutting constraints

- **Kotlin 2.1, AGP 8.7.3, Compose BOM 2024.12.01, Material 3** — current. Bump on every Android Studio Ladybug+ point release.
- **minSdk 26 floor** is hard. Don't break.
- **targetSdk 35 today; targetSdk 36 for v0.3+** to opt into predictive-back default-on, strict intent matching, and Android 16 install-dialog redesign behaviors. Aurora Store moved to targetSdk 36 in 4.7.1 [Agent A §7].
- **Java 17 in CI** (`actions/setup-java@v4` with `temurin`); Android Studio JBR 21 locally.
- **`KEYSTORE_BASE64` + `STORE_PASSWORD` + `KEY_ALIAS` + `KEY_PASSWORD`** secrets must remain configured. Cert SHA-256 (`9c6a9276…e6ebd3a0d`) is the LAS publisher key — log it in CHANGELOG on every release for transparency.
- **OkHttp ≥ 4.12.0** (CVE-2023-0833 ban).
- **Wear Tiles ≥ 1.4.1 / ProtoLayout ≥ 1.2.1** (CVE-2024-7254 protobuf-javalite) — only when item 29 lands.
- **`androidx.security:security-crypto` is deprecated.** v0.2 item 18 moved active secret storage to Tink; the dependency is retained only for the legacy EncryptedSharedPreferences migration bridge and should be removed after that window.
- **Developer Verification regional rollout dates: BR / ID / SG / TH = Sept 30, 2026; global through 2027** [21]. Item 15 must ship in v0.2.0 or earlier.
- **F-Droid client lives at gitlab.com/fdroid/fdroidclient** (the GitHub mirror is downstream). Sister-project URLs: gitlab.com/fdroid/repomaker, gitlab.com/fdroid/fdroidserver. Cross-reference when item 27 lands.

---

## Versioning policy

- **v0.x.0 = milestone** with a changelog entry, signed APK + sha256 sidecar, release notes summarizing every numbered item in the tier.
- **v0.x.y = bug-fix** with a single-line CHANGELOG entry. No new tier items move.
- **CHANGELOG.md, README badge, `app/build.gradle.kts` versionName/versionCode, ROADMAP version-line, repo `CLAUDE.md` version-history line — must all match.** The user's "Release vX.Y.Z" recipe is the source of truth.

---

## Self-audit summary (Phase 5)

- **Coverage check** — every category from the brief is represented:
  - **Security:** items 1, 4, 16–19, 18b, 43–45, 50, 51, 53, 70–72, R5, R6.
  - **Accessibility:** items 38, 67–69; T-A11Y theme.
  - **i18n / l10n:** items 38, 47.
  - **Observability / telemetry:** items 22, 48 (no off-device telemetry — see R2).
  - **Testing:** intentionally light per project policy (R16); CI matrix on multiple SDKs is the milestone DoD floor.
  - **Docs:** items 24, 72 + T-DOCS theme; manifest spec is part of item 25's plugin contract.
  - **Distribution / packaging:** items 27, 43–45, 70, 71.
  - **Plugin ecosystem:** items 25, 27 (consume + emit), 26, 28, 61.
  - **Mobile (Wear / TV / Auto):** items 29, 30, 65, 66, U14.
  - **Offline / resilience:** items 36, 37, 49, U-resilience implicit.
  - **Multi-user / collab:** intentionally light — single-user catalog by design — but sharing surfaces in items 27, 60, 61.
  - **Migration paths:** item 23 (DataStore migrations), item 60 (lockfile), item 18 / 18b (security-crypto migration + drop).
  - **Upgrade strategy:** versioning policy above + item 23.
  - **Compliance (Developer Verification):** items 15 (shipped v0.2.1), 73 (v0.3).
- **Source traceability** — every numbered item links to at least one Appendix entry.
- **Tier placement justification** — each item has effort × impact + reason in tier text.
- **Internal consistency** — items shipped through v0.2.3 are annotated in place with ✅ markers and never appear in two tiers; rejects (R1–R16) explicitly state their reason; Under Consideration items each carry the open question.
- **Reconciliation against shipped state (v2.4 pass)** — every item from the v2.3 "Now" tier is either ✅ (1, 2, 3, 4, 5, 7, 8, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20, 21, 22, 23, 24) or carry-forward (6, 9, 18b). Cross-tier items now annotated ✅: **34** (v0.2.3), **35** (v0.2.3), **37** (v0.2.1), **62** (v0.2.3).
- **Adversarial review pass** — ran a hostile-reviewer scan and addressed: (a) "you didn't talk about reproducible builds" → items 43, 70, 71. (b) "you skipped the looming Developer Verification existential risk" → items 15 (shipped) + new **item 73** (advanced-sideloading-flow user explainer for the August 2026 launch) + updated strategic frame. (c) "your background-update story was a one-liner" → items 8, 9, 25, 36, 46–49. (d) "you didn't address Material 3 Expressive / edge-to-edge / predictive back" → items 10–12, 41. (e) "you didn't address split APK / XAPK" → items 54, 55. (f) "no plugin model" → items 25, 28, 61. (g) "you marked items shipped without verifying the code" → reconciliation pass walked `app/src/main/kotlin/com/sysadmin/lasstore/` directly (`IgnoreListStore.kt`, `AppIdCache.kt`, `InstallAuditLog.kt`, `CatalogViewModel.saveApk` + `saveToDownloads`, `CardStatus.PermissionReview`, `PermissionDiffBlock`, `setRequestUpdateOwnership` + `setPackageSource(PACKAGE_SOURCE_STORE)` in `PackageInstallerService`, `apksig` 8.7.3 in `build.gradle.kts`, Network Security Config XML).

---

## Appendix — Sources

URLs grouped by source class. Numbered references are used inline above as `[N]`. Agent letters in `[A§…]`, `[E…]` reference the underlying research-agent reports.

### A. Android platform docs / specs (1–55)

1. https://developer.android.com/reference/android/content/pm/PackageInstaller.SessionParams
2. https://developer.android.com/reference/android/content/pm/PackageInstaller.SessionParams#setRequireUserAction(int)
3. https://developer.android.com/about/versions/14/changes/fgs-types-required
4. https://developer.android.com/reference/android/content/pm/PackageInstaller.SessionParams#setPackageSource(int)
5. https://developer.android.com/reference/android/content/pm/PackageInstaller.SessionParams#setInstallerPackageName(java.lang.String)
6. https://source.android.com/docs/setup/create/app-ownership
7. https://developer.android.com/reference/android/content/pm/PackageInstaller.InstallConstraints
8. https://developer.android.com/reference/android/content/pm/PackageInstaller.Session
9. https://developer.android.com/reference/android/content/pm/PackageInstaller
10. https://developer.android.com/reference/android/content/pm/PackageInstaller.SessionParams#setOriginatingUid(int)
11. https://developer.android.com/reference/android/content/pm/InstallSourceInfo
12. https://developer.android.com/reference/android/content/pm/PackageManager
13. https://developer.android.com/reference/android/content/pm/SigningInfo
14. https://developer.android.com/reference/android/content/pm/PackageInstaller.Session#openWrite(java.lang.String,%20long,%20long)
15. https://source.android.com/docs/security/features/apksigning/v2
16. https://source.android.com/docs/security/features/apksigning/v3
17. https://source.android.com/docs/security/features/apksigning/v3-1
18. https://source.android.com/docs/security/features/apksigning/v4
19. https://android.googlesource.com/platform/tools/apksig/
20. https://mvnrepository.com/artifact/com.android.tools.build/apksig
21. https://developer.android.com/developer-verification
22. https://www.esper.io/blog/android-13-sideloading-restriction-harder-malware-abuse-accessibility-apis
23. https://www.xda-developers.com/android-14-new-apis-app-stores/
24. https://developer.android.com/develop/background-work/background-tasks/persistent/getting-started/define-work
25. https://developer.android.com/develop/background-work/background-tasks/persistent/how-to/long-running
26. https://developer.android.com/develop/ui/views/layout/edge-to-edge
27. https://developer.android.com/guide/navigation/predictive-back-gesture
28. https://developer.android.com/develop/ui/views/launch/icon_design_adaptive
29. https://developer.android.com/privacy-and-security/security-config
30. https://security.snyk.io/package/maven/com.squareup.okhttp3:okhttp
31. https://developer.android.com/jetpack/androidx/releases/security
32. https://github.com/ed-george/encrypted-shared-preferences
33. https://proandroiddev.com/goodbye-encryptedsharedpreferences-a-2026-migration-guide-4b819b4a537a
34. https://gitlab.com/fdroid/wiki/-/wikis/Metadata/JSON-v2-Index
35. https://f-droid.org/en/docs/Anti-Features/
36. https://f-droid.org/en/docs/Signing_Process/
37. https://f-droid.org/tutorials/add-repo/
38. https://developer.android.com/training/wearables/data/data-layer
39. https://developer.android.com/jetpack/androidx/releases/wear-tiles
40. https://developer.android.com/jetpack/androidx/releases/wear-protolayout
41. https://developer.android.com/tools/adb#wireless
42. https://gist.github.com/benigumocom/a6a87fc1cb690c3c4e3a7642ebf2be6f
43. https://developer.android.com/develop/ui/compose/system/insets
44. https://f-droid.org/docs/Anti-Features/
45. https://developer.android.com/guide/topics/resources/app-languages
46. https://f-droid.org/docs/Reproducible_Builds/
47. https://codeberg.org/IzzyOnDroid/rbtlog
48. https://jedisct1.github.io/minisign/
49. https://wiki.debian.org/Teams/Apt/Spec/AptSign
50. https://developer.android.com/topic/libraries/architecture/workmanager
51. https://android-developers.googleblog.com/2026/03/android-developer-verification.html
52. https://github.com/RikkaApps/Shizuku-API
53. https://shizuku.rikka.app/
54. https://www.xda-developers.com/implementing-shizuku/
55. https://www.androidauthority.com/android-15-app-archiving-demo-3425621/

### B. Press / blogs (56–72)

56. https://www.androidauthority.com/android-package-installer-ui-update-3622220/
57. https://www.androidauthority.com/how-android-sideloading-restrictions-may-work-3595355/
58. https://www.androidauthority.com/android-15-restricted-settings-sideloading-3481098/
59. https://www.androidauthority.com/google-android-sideloading-claims-misleading-f-droid-3611139/
60. https://www.androidauthority.com/google-sideloading-android-developer-verification-rules-3602811/
61. https://www.androidauthority.com/play-integrity-sideloading-detection-3480639/
62. https://www.androidauthority.com/how-android-app-verification-works-3603559/
63. https://www.androidauthority.com/android-sideloading-24-hours-adb-3650540/
64. https://9to5google.com/2025/08/25/android-apps-developer-verification/
65. https://9to5google.com/2026/03/30/android-developer-verifier-app/
66. https://medium.com/@solrudev/painless-building-of-an-android-package-installer-app-d5a09b5df432
67. https://blog.esper.io/adb-29-how-to-downgrade-rollback-app/
68. https://commonsware.com/Q/pages/chap-pkg-001.html
69. https://www.helpnetsecurity.com/2026/03/31/android-developer-verification-requirement/
70. https://hackaday.com/2025/10/06/google-confirms-non-adb-apk-installs-will-require-developer-registration/
71. https://www.theregister.com/2025/08/26/android_developer_verification_sideloading/
72. https://lwn.net/Articles/1034989/

### C. OSS competitors (73–115)

73. https://github.com/ImranR98/Obtainium — Obtainium primary repo
74. https://github.com/ImranR98/Obtainium/issues/281 — Android TV (40 reactions)
75. https://github.com/ImranR98/Obtainium/issues/102 — GitHub Actions source
76. https://github.com/ImranR98/Obtainium/issues/109 — Import link share button
77. https://github.com/ImranR98/Obtainium/issues/462 — VirusTotal
78. https://github.com/ImranR98/Obtainium/issues/950 — Cancel download
79. https://github.com/ImranR98/Obtainium/issues/580 — Parallel downloads
80. https://github.com/ImranR98/Obtainium/issues/1105 — Gentle background updates
81. https://github.com/ImranR98/Obtainium/issues/1550 — Background auto-upgrade
82. https://github.com/ImranR98/Obtainium/issues/2102 — Self-update
83. https://github.com/ImranR98/Obtainium/issues/795 — AAB support
84. https://github.com/ImranR98/Obtainium/issues/682 — XAPK support
85. https://github.com/ImranR98/Obtainium/issues/29 — Save APK without installing
86. https://github.com/ImranR98/Obtainium/issues/2042 — App Archive support
87. https://github.com/ImranR98/Obtainium/issues/255 — Cert pinning (closed via AppVerifier)
88. https://github.com/ImranR98/Obtainium/issues/707 — Regex for version detection
89. https://github.com/ImranR98/Obtainium/issues/1791 — Superuser install
90. https://github.com/ImranR98/Obtainium/issues/121 — SOCKS5 / Orbot
91. https://github.com/ImranR98/Obtainium/issues/2013 — Multi-device sync
92. https://github.com/ImranR98/Obtainium/issues/1753 — Detect dead repo
93. https://github.com/ImranR98/Obtainium/issues/2199 — Background install autonomy
94. https://github.com/ImranR98/Obtainium/issues/651 — Verify Play APK signed by Google
95. https://github.com/ImranR98/Obtainium/discussions/2722 — Fine-grained PAT
96. https://github.com/ImranR98/Obtainium/discussions/2644 — OAuth
97. https://github.com/ImranR98/Obtainium/discussions/2699 — Will Obtainium survive Dev Verification?
98. https://github.com/Iamlooker/Droid-ify — Droid-ify primary repo
99. https://github.com/Droid-ify/client/issues/215 — Pull-to-refresh
100. https://github.com/Droid-ify/client/issues/713 — Prioritize repos
101. https://github.com/Droid-ify/client/issues/1245 — Keep Android Open
102. https://github.com/Droid-ify/client/issues/431 — Anti-feature reasons
103. https://github.com/Droid-ify/client/issues/87 — Fuzzy search
104. https://github.com/Droid-ify/client/issues/848 — CVE notifications
105. https://github.com/Droid-ify/client/issues/18 — Custom git repos
106. https://github.com/NeoApplications/Neo-Store — Neo Store primary repo
107. https://github.com/NeoApplications/Neo-Store/issues/722 — Per-app preferred repo
108. https://github.com/NeoApplications/Neo-Store/issues/297 — Install UI
109. https://github.com/NeoApplications/Neo-Store/issues/121 — Blacklist
110. https://github.com/NeoApplications/Neo-Store/issues/393 — Sync progress
111. https://github.com/accrescent/accrescent — Accrescent primary repo
112. https://accrescent.app/features
113. https://github.com/accrescent/accrescent/issues/688 — More app details
114. https://github.com/accrescent/accrescent/issues/534 — Take over updates
115. https://github.com/accrescent/accrescent/issues/91 — Update-all button

### D. F-Droid ecosystem (116–135)

116. https://gitlab.com/fdroid/fdroidclient
117. https://gitlab.com/fdroid/repomaker
118. https://gitlab.com/fdroid/fdroidserver
119. https://gitlab.com/fdroid/fdroidclient/-/work_items/1843 — Update notification parsing
120. https://gitlab.com/fdroid/fdroidclient/-/work_items/1484 — Import/export installed apps
121. https://gitlab.com/fdroid/fdroidclient/-/work_items/1908 — Hide certain apps
122. https://gitlab.com/fdroid/fdroidclient/-/work_items/450 — Delta APK updates
123. https://gitlab.com/fdroid/fdroidclient/-/work_items/2276 — Flag dead-update apps
124. https://gitlab.com/fdroid/fdroidclient/-/work_items/1767 — Android TV (€50 bounty)
125. https://gitlab.com/fdroid/fdroidclient/-/work_items/1560 — Reproducibly built indicator
126. https://gitlab.com/fdroid/fdroidclient/-/work_items/2724 — Block auto-update on permission/anti-feature change
127. https://gitlab.com/fdroid/fdroidclient/-/work_items/1601 — Import/export repo list
128. https://gitlab.com/fdroid/fdroidclient/-/work_items/1968 — Storage error message
129. https://gitlab.com/fdroid/fdroidclient/-/work_items/2534 — Cumulative whatsNew
130. https://gitlab.com/fdroid/fdroidclient/-/work_items/336 — Full-text search
131. https://gitlab.com/fdroid/fdroidclient/-/work_items/957 — Similar apps
132. https://f-droid.org/2025/05/21/making-reproducible-builds-visible.html
133. https://gitlab.com/IzzyOnDroid/repo
134. https://f-droid.org/en/2026/01/23/fdroid-in-2025-strengthening-our-foundations-in-a-changing-mobile-landscape.html
135. https://f-droid.org/en/docs/Inclusion_Policy/

### E. Adjacent + commercial stores (136–170)

136. https://gitlab.com/AuroraOSS/AuroraStore — Aurora Store
137. https://github.com/MuntashirAkon/AppManager — AppManager
138. https://github.com/MMRLApp/MMRL — Magisk Modules manager
139. https://github.com/solrudev/Ackpine — Kotlin Coroutines wrapper for PackageInstaller
140. https://github.com/altstoreio/AltStore — AltStore (iOS sideload)
141. https://docs.altstore.io/distribute-your-apps/make-a-source — `.altsource` schema
142. https://github.com/Sileo/Sileo — Sileo (modern Cydia)
143. https://www.apkmirror.com/apk/apkmirror/apkmirror-installer-official/ — APKMirror Installer
144. https://apkpure.com/xapk.html — XAPK format
145. https://en.aptoide.com/company/faq/is-aptoide-safe-trusted — Aptoide trust badges
146. https://docs.flatpak.org/en/latest/manifests.html — Flatpak manifest
147. https://docs.flathub.org/docs/for-app-authors/verification — Flathub publisher verification
148. https://snapcraft.io/docs/channels — Snap channels
149. https://learn.microsoft.com/windows/package-manager/package/manifest — winget manifest
150. https://github.com/ScoopInstaller/Scoop — Scoop
151. https://docs.brew.sh/Cask-Cookbook — Homebrew Cask
152. https://docs.brew.sh/Brew-Livecheck — Homebrew livecheck
153. https://github.com/microsoft/winget-pkgs — winget manifests repo
154. https://github.com/jdx/mise — mise (4-callback plugin contract)
155. https://github.com/gogcom/galaxy-integrations-python-api — GOG Galaxy 2.0 integration SDK
156. https://github.com/opa334/TrollStore — TrollStore (entitlement surfacing)
157. https://partner.steamgames.com/doc/store/application/branches — Steam branches
158. https://crates.io — Cargo registry
159. https://www.npmjs.com — npm registry
160. https://rustsec.org — Rust advisory DB
161. https://github.com/advisories — GitHub Advisory DB
162. https://developer.android.com/google/play/integrity/verdicts — Play Integrity verdicts
163. https://developer.android.com/google/play/integrity — Play Integrity overview
164. https://developer.android.com/guide/playcore/in-app-updates — Play in-app updates
165. https://developer.android.com/guide/playcore/asset-delivery — Play Asset Delivery
166. https://security.googleblog.com/2026/02/keeping-google-play-android-app-ecosystem-safe-2025.html — Play Protect 2025 numbers
167. https://www.malwarebytes.com/blog/news/2025/08/77-malicious-apps-removed-from-google-play-store
168. https://www.bitdefender.com/en-us/blog/labs/malicious-google-play-apps-bypassed-android-security
169. https://www.ghacks.net/2025/11/07/google-play-store-hosted-239-malicious-apps-that-were-downloaded-40-million-times/
170. https://en.wikipedia.org/wiki/RuStore — RuStore (regional store)

### F. Community signal (171–210)

171. https://www.reddit.com/r/degoogle/comments/1ralfa2 — Sideloading lockdown action thread (3128 pts)
172. https://www.reddit.com/r/Android/comments/1ntf11g — F-Droid warns on dev verification (2846 pts)
173. https://www.reddit.com/r/revancedapp/comments/1n9uuyx — "Don't call it sideloading" (1722 pts)
174. https://www.reddit.com/r/degoogle/comments/1q0y26j — Obtainium dev-verifier prompt (645 pts)
175. https://www.reddit.com/r/GrapheneOS/comments/1q2jolh — "Project run by some guy" trust thread (313 pts)
176. https://www.reddit.com/r/degoogle/comments/1n7gefd — F-Droid update lag (288 pts)
177. https://www.reddit.com/r/androidapps/comments/1qdiih5 — App-store consolidation (43 pts; "Discoverium fork has search")
178. https://www.reddit.com/r/androidapps/comments/1ov72h5 — Obtainium for FOSS apps (13 pts; supply-chain worry)
179. https://www.reddit.com/r/degoogle/comments/1s0nwna — Closed-source via Obtainium (10 pts)
180. https://www.reddit.com/r/androiddev/comments/1srijfl — Key rotation pain (4 pts)
181. https://news.ycombinator.com/item?id=45017028 — Dev verification (3050 pts)
182. https://news.ycombinator.com/item?id=42026251 — Obtainium HN launch (233 pts)
183. https://news.ycombinator.com/item?id=45776269 — Theoretical circumvention of verification (196 pts)
184. https://news.ycombinator.com/item?id=36074646 — Aurora accounts blocked (189 pts)
185. https://news.ycombinator.com/item?id=29534663 — Affordable Play alternative (87 pts)
186. https://stackoverflow.com/questions/4604239 — Install programmatically (249 votes)
187. https://stackoverflow.com/questions/19959890 — Conflicting signature (140 votes)
188. https://stackoverflow.com/questions/42668595 — Inconsistent signatures install (72 votes)
189. https://stackoverflow.com/questions/75112572 — v3 vs v3.1 proof of rotation
190. https://stackoverflow.com/questions/77159385 — APK v3.1 lineage clarification
191. https://stackoverflow.com/questions/73787102 — Update after key rotation
192. https://stackoverflow.com/questions/75075494 — Universal-APK key rotation upgrade
193. https://github.com/offa/android-foss — awesome-android-foss app-store section
194. https://github.com/pluja/awesome-privacy
195. https://github.com/timschneeb/awesome-shizuku
196. https://github.com/ZeeFoss/Awesome-FOSS-Apps-for-Android
197. https://github.com/nnosal/my-obtainium — Pre-built obtainium.json template
198. https://github.com/zaneschepke/fdroid — GH-Actions F-Droid repo pipeline
199. https://github.com/gotsunami/docker-fdroid — Self-host F-Droid Docker
200. https://github.com/warren-bank/fdroid — Simple binary repo template
201. https://github.com/zapstore/zapstore — Decentralized Nostr-backed store
202. https://github.com/rainxchzed/Github-Store — GitHub-Releases store competitor
203. https://forum.f-droid.org/t/google-will-require-developer-verification-to-install-android-apps-including-sideloading/33123
204. https://github.com/yuliskov/SmartTube/issues/4151 — Background updaters with Shizuku
205. https://www.reddit.com/r/WearOS/comments/1pc262b — Wear APK Install passes Play tests (436 pts)
206. https://github.com/microsoft/WSA/discussions/536 — WSA discontinuation
207. https://en.wikipedia.org/wiki/Aptoide
208. https://9to5google.com/2024/09/12/android-15-sideloaded-apps-restrictions/
209. https://www.androidpolice.com/android-15-sideloading-restrictions-dont-apply-to-third-party-app-stores/
210. https://github.com/Droid-ify/client/issues/1088 — Material You by default (Droid-ify)

### G. LocalAndroidStore + sibling (211–215)

211. https://github.com/SysAdminDoc/LocalAndroidStore — this repo
212. https://github.com/SysAdminDoc/LocalChromeStore — sibling project
213. https://www.androidauthority.com/android-developer-verification-rollout-sideloading-flow-3653395/ — Advanced sideloading flow + 24-hour wait period (April 2026)
214. https://thehackernews.com/2026/03/android-developer-verification-rollout.html — Verification rollout timeline (April 2026 verifier app, August 2026 advanced flow, Sept 30 2026 BR/ID/SG/TH enforcement)
215. https://github.com/solrudev/Ackpine — InstallConstraints helper (referenced by item 6)

---

> **End of roadmap.** Re-read on every milestone retro. If an item is silently missing in two consecutive retros, demote it to Rejected with a stated reason.

## Research-Driven Additions

### P1

- [ ] P1 — Externalize user-facing copy before localization
  Why: Compose screens hardcode their text and `strings.xml` is effectively manifest-only, blocking meaningful pseudolocale, RTL, and translation work.
  Evidence: `app/src/main/kotlin/com/sysadmin/lasstore/ui/`, `app/src/main/res/values/strings.xml`; existing items 38 and 67–69.
  Touches: all active Compose screens, string resources, locale configuration, formatting/plural tests.
  Acceptance: Active user-facing text, plurals, dates, quantities, and content descriptions come from resources; `en-XA` and RTL builds have no clipped critical actions at 200% font scale; no new hardcoded user-facing strings pass lint.
  Complexity: M

### P2

- [ ] P2 — Establish an Android 17 and current dependency upgrade lane
  Why: The app targets API 35 on AGP 8.7.3/Kotlin 2.1 while Android 17 uses API 37 and core Kotlin, coroutines, serialization, Tink, and OkHttp lines have moved materially.
  Evidence: root/app Gradle files and version catalog; Android 17 setup/behavior guidance; AGP roadmap; Kotlin 2.4.10, coroutines 1.11, serialization 1.11, Tink 1.23, and OkHttp 5.4 release notes as of 2026-07-29.
  Touches: Gradle wrapper/catalog/build files, ProGuard rules, networking/serialization/crypto call sites, API 26–37 verification matrix, dependency lock/SBOM docs.
  Acceptance: Upgrades land in bisectable batches with release-note migrations documented; compile/target API 37 behavior changes are resolved; min API 26 remains supported; unit, lint, release build, and connected API 26/35/37 checks pass; a machine-readable dependency inventory is reproducible.
  Complexity: L

- [ ] P2 — Remove the duplicate catalog implementation after parity verification
  Why: `CatalogExperience.kt`/`ReleaseCard.kt` are active while `CatalogScreen.kt`/`AppCard.kt` remain compiled, creating two divergent accessibility, copy, and state surfaces.
  Evidence: `AppRoot.kt` routing and catalog UI source references.
  Touches: `AppRoot.kt`, `CatalogExperience.kt`, `ReleaseCard.kt`, `CatalogScreen.kt`, `AppCard.kt`, UI previews/tests.
  Acceptance: A reference search and build prove only one catalog/card implementation remains; every install/update/ignore/save/launch/uninstall/error/empty/loading state has parity coverage; previews and tests use the production components.
  Complexity: S

- [ ] P2 — Add explicit adoption for apps installed outside LocalAndroidStore
  Why: When no LAS app record or publisher pin exists, the first inspected same-package app installed by another source is allowed through the normal update/reinstall path without an explicit provenance/adoption decision.
  Evidence: `app/src/main/kotlin/com/sysadmin/lasstore/ui/catalog/CatalogViewModel.kt:376-542` treats a missing pin as accepted and checks only whether the package is installed; after the normal path succeeds it records the package in `AppIdCache`. `PackageInstallerService.kt:314-337` requests update ownership only on first install, and Android documents update ownership as a no-op on updates at https://developer.android.com/reference/android/content/pm/PackageInstaller.SessionParams. Neo Store has the analogous request at https://github.com/NeoApplications/Neo-Store/issues/823.
  Touches: `data/AppIdCache.kt`, `data/InstallStateRepo.kt`, signer/pin enrollment, `CatalogViewModel.kt`, active catalog card/adoption dialog, queue eligibility, install audit records, and foreground/queued tests.
  Acceptance: An externally installed same-package fixture is labeled `Installed elsewhere`/`Unmanaged` and shows current package version/signer/source; the app cannot enqueue it or treat it as LAS-managed until the user confirms adoption; adoption records source, signer, and provenance before later updates; declining adoption leaves explicit foreground review/manual install available but blocks unattended management; a different signer remains blocked by the existing signature checks.
  Complexity: M

- [ ] P2 — Surface repository pagination truncation instead of silently dropping source content
  Why: A GitHub user or organization with more than 1,000 repositories receives an apparently successful but incomplete catalog because pagination stops after ten 100-repository pages.
  Evidence: `app/src/main/kotlin/com/sysadmin/lasstore/data/GitHubClient.kt:388-403` breaks when `page > 10` with only a comment, and the GitHub repository API documents 100 as the per-page maximum at https://docs.github.com/en/rest/repos/repos.
  Touches: `GitHubClient.kt` result types and pagination policy, source refresh status/copy, `DiscoveryUseCase.kt`, settings/source limits, request-budget tests and README behavior documentation.
  Acceptance: A 1,001-repository fixture never reports a complete successful source while silently omitting page 11; the client either continues within an explicit bounded policy or returns a typed `Truncated` result with fetched/omitted counts and a way to narrow/continue; normal sources at or below the bound retain current behavior and request budgets remain bounded.
  Complexity: S

- [ ] P2 — Add explicit historical-release browsing and version selection
  Why: The gateway exposes only the latest stable release or the first non-draft item in the first ten prerelease results, so a user cannot deliberately restore or inspect an older GitHub release except through the separate crash-rollback path.
  Evidence: `app/src/main/kotlin/com/sysadmin/lasstore/data/GitHubClient.kt:58-64,163-189` defines only `latestRelease()` and requests `/releases/latest` or `releases?per_page=10`; current `AppInfo`/catalog state has no release-history selection. Obtainium’s enhancement tracker includes older-release requests at https://github.com/ImranR98/Obtainium/issues/2936, while the existing roadmap rollback item covers only a locally retained previous APK.
  Touches: `GitHubGateway`/`GitHubClient.kt`, release/asset identity and cache models, `domain/AppInfo.kt`, catalog detail/history UI, `CatalogViewModel.kt`, install audit and downgrade policy, bounded retention, and release-history tests.
  Acceptance: A user can open a bounded, paged release history, inspect version code/date/prerelease/digest/signer evidence, and explicitly choose an older release; normal refresh and background update continue to target latest; selected older releases require the existing downgrade confirmation and trust/permission gates, are audit-recorded, and never download or install without the explicit action.
  Complexity: M

## Audit Findings — 2026-08-10

### P1

- [ ] P1 — Ignore stale queued-update results after a replacement
  Category: reliability
  Where: `app/src/main/kotlin/com/sysadmin/lasstore/install/QueuedUpdatePayload.kt:32`; `install/BackgroundUpdateScheduler.kt:40-58,112-129`; `install/QueuedUpdateStatusStore.kt:42-56,180-211`; `install/QueuedInstallResultReceiver.kt:25-54`.
  Problem: A queued work name is only `source/owner/repo`, and status has no generation or attempt UUID. `ExistingWorkPolicy.REPLACE` can stop an old worker while its PackageInstaller callback or result receiver is still in flight; that old payload can then mark the new queue Installed/Failed and write old-version pin/cache/audit state.
  Evidence: `workName` excludes tag/version/asset identity, status keys are keyed only by `workName`, and the receiver unconditionally calls `markInstalled`/`markFailed` for any valid payload. No callback compares its payload to a persisted current generation.
  Fix: Persist a unique queue generation/attempt ID, include it in WorkManager input, JobScheduler extras, PackageInstaller result data, and status records, and ignore terminal results whose generation is no longer current. Abandon stale sessions and serialize installation per application ID.
  Acceptance: Queue release v2, replace it with v3 while v2 is downloading/committing, then deliver v2's late callback. Only v3 can mutate final status, cache, pin, and audit; v2 is marked stale/abandoned.
  Confidence: Likely
  Effort: L

- [ ] P1 — Reconcile a queued install after process death instead of reporting a false failure
  Category: reliability
  Where: `app/src/main/kotlin/com/sysadmin/lasstore/install/QueuedUpdateWorker.kt:30-58`; `install/QueuedUpdateRunner.kt:162-175,203-225`; `install/PackageInstallerService.kt:99-121`; `install/ForegroundInstallStore.kt:299-318`.
  Problem: The WorkManager path uses `installApk()` without a durable queued-install result record. If the process dies after PackageInstaller commits but before the worker receives/resumes the callback, the restarted worker can see the candidate version already installed and return `queued_non_upgrade` failure. The status store then reports Failed even though Android completed the update, and no matching durable finalizer repairs the queue result.
  Evidence: `QueuedUpdateWorker` marks failure for any `QueuedUpdateResult.Failed`; the runner explicitly returns failure when `meta.versionCode <= installedInfo.versionCode`; the foreground callback and durable operation are not registered for the queue worker path in a way that the next worker reconciles.
  Fix: Persist a queue operation record containing package/version/signer/session/generation before commit, and on restart reconcile PackageInstaller sessions plus installed package/version/signer before deciding to retry. A matching installed artifact must mark the queue Installed and update pin/cache/audit exactly once.
  Acceptance: A forced process-death/fake-callback test completes the PackageInstaller commit, reruns the worker, and observes Installed—not Failed—with matching cache, pin, and audit state; an unmatched version/signer remains retryable or failed with a clear reason.
  Confidence: Verified
  Effort: L

- [ ] P1 — Replace background API 26–33 user-action hangs with an explicit pending state
  Category: reliability
  Where: `app/src/main/kotlin/com/sysadmin/lasstore/install/QueuedUpdateWorker.kt:30-34`; `install/QueuedUpdateRunner.kt:203-209`; `install/PackageInstallerService.kt:338-355`; `install/QueuedInstallResultReceiver.kt:25-31`.
  Problem: On API 26–33 the WorkManager path calls the normal PackageInstaller flow with `useInstallConstraints=false`. Android can return `STATUS_PENDING_USER_ACTION`, but the callback tries to start the confirmation Activity directly from an application/background context and does not catch launch failure or persist an `awaiting_user_action` state. Background-start restrictions or a missing handler can leave the worker suspended or crash the callback while the UI remains Running/Queued.
  Evidence: The worker selects the non-constraint branch on all pre-34 devices, and both pending-action handlers call `startActivity()` directly; only the receiver's fallback path has a `runCatching` wrapper.
  Fix: Stage the verified APK and persist a durable `awaiting_user_action` state/session, then issue a notification/deep link for a foreground user action; do not start UI from the background worker. Catch missing-handler/security failures, reconcile the session on return, and make retry/cancel explicit.
  Acceptance: API 26, 29, and 33 tests queue an update while the app is backgrounded. No unhandled ActivityNotFound/SecurityException or hung worker occurs; the card/notification explains that user action is required and completion/failure is reconciled.
  Confidence: Needs-repro
  Effort: L

- [ ] P1 — Re-enqueue or reconcile queued UIDT jobs after reboot
  Category: reliability
  Where: `app/src/main/kotlin/com/sysadmin/lasstore/install/BackgroundUpdateScheduler.kt:86-109`; `install/QueuedUpdateStatusStore.kt:63-70`; manifest/startup wiring under `app/src/main/AndroidManifest.xml` and `data/ServiceLocator.kt`.
  Problem: UIDT `JobInfo` is built without persistence, and there is no boot receiver or app-start re-enqueue/reconciliation path. The status store persists `Queued`, so after a device reboot the JobScheduler work disappears while the catalog continues to show a pending update indefinitely.
  Evidence: `buildUidtJobInfo()` never calls `setPersisted`; the manifest has no boot completion receiver, and startup initializes/reads status but does not schedule missing jobs or mark them actionable.
  Fix: Persist queue payload/generation independently and re-enqueue missing UIDT/WorkManager work on boot and app launch, or intentionally transition the status to a recoverable “needs reschedule” state. Reconcile any PackageInstaller session before creating duplicate work.
  Acceptance: Schedule a queue, reboot before execution, and verify after boot/opening the app that exactly one job is restored or the card offers a clear retry; no queued item remains silently pending forever.
  Confidence: Verified
  Effort: M

### P2

- [ ] P2 — Make catalog refresh single-flight and prevent stale cache overwrites
  Category: reliability
  Where: `app/src/main/kotlin/com/sysadmin/lasstore/ui/catalog/CatalogViewModel.kt:159,231-272`; `data/CatalogCache.kt:94-111`.
  Problem: Initialization, pull-to-refresh, and the refresh button can launch overlapping refresh coroutines, and completion has no generation/latest-result guard. Concurrent writes for one source use the fixed `${target.name}.tmp` path, so an older refresh can overwrite a newer UI result or race another writer through the same temp file.
  Evidence: `refresh()` creates an untracked `viewModelScope.launch` for every call, while `writeAtomically()` always uses one temp filename per target. The refresh control is only disabled after state propagation, leaving a rapid double action window.
  Fix: Own a refresh Job or mutex and/or attach a monotonically increasing generation; cancel/ignore stale results. Serialize cache writes per target and use unique temp files with cleanup so only the latest successful snapshot becomes visible.
  Acceptance: Two deterministic refreshes complete out of order and the final cards/cache correspond to the latest generation; no temp-file collision, partial JSON, or stale spinner/result is observed.
  Confidence: Likely
  Effort: M

- [ ] P2 — Allocate collision-free UIDT JobScheduler IDs
  Category: reliability
  Where: `app/src/main/kotlin/com/sysadmin/lasstore/install/BackgroundUpdateScheduler.kt:96-99,132-137`.
  Problem: Job IDs are a 50,000-slot hash bucket derived from `workName`, with no persisted collision table. Different queued apps can produce the same component-scoped ID; scheduling the second can replace/update the first, and canceling one can cancel the other.
  Evidence: With the production formula, `queued-update-source-owner-repo-100` and `queued-update-source-owner-repo-5565` both map to bucket 472 and job ID 420472. `enqueue()` and `cancel()` use only this computed ID.
  Fix: Persist a collision-aware allocation keyed by the full logical work identity, reuse an ID only for that exact identity, and allocate a new free ID when a hash collides. Release IDs only after terminal/cancelled reconciliation.
  Acceptance: A unit test schedules the two colliding names and proves distinct `JobInfo.id` values, both jobs remain scheduled, and canceling one leaves the other intact across restart.
  Confidence: Verified
  Effort: M

- [ ] P2 — Restrict split-config detection to APK assets
  Category: correctness
  Where: `app/src/main/kotlin/com/sysadmin/lasstore/domain/DiscoveryUseCase.kt:316-325,357-373`.
  Problem: `isSplitConfig()` is applied to every GitHub release asset, not only APKs. A normal metadata asset such as `config.json` matches `SPLIT_CONFIG`, sets `splitSetPresent`, and causes a release containing `base.apk` plus `config.json` to be rejected as a split set, hiding an otherwise installable app.
  Evidence: The regex matches `config.` regardless of extension, and `splitSetPresent` is computed from the complete `assets` list before the `.apk` filter. Existing tests cover `split_config.*.apk` but not `base.apk` plus `config.json`.
  Fix: Apply split detection only to case-insensitive `.apk` assets and require the strict split naming form (for example `split_config.<abi|locale>.apk`); ignore JSON, metadata, signatures, and unrelated release files.
  Acceptance: A regression test with `base.apk` and `config.json` selects `base.apk`; a real `base.apk` plus `split_config.*.apk` set remains rejected until bundle support exists.
  Confidence: Verified
  Effort: S

- [ ] P2 — Do not resurrect removed or filter-excluded repositories in partial snapshots
  Category: correctness
  Where: `app/src/main/kotlin/com/sysadmin/lasstore/domain/DiscoveryUseCase.kt:228-271`.
  Problem: When any release lookup fails, `mergeWithSnapshot()` appends every cached app whose owner/repo is not in the current live result. That includes repositories removed from the source, archived/forked repositories, and repositories excluded by the current topic filter, so a successful repo-list response can still display stale installable cards.
  Evidence: `liveKeys` contains only `liveApps` (found releases), and `cachedRemainder` is `snapshot.apps - liveKeys`; it is not constrained to the current candidate repository set or filter. `source.filterByTopic` is applied earlier at lines 151-156.
  Fix: Retain cached entries only for candidates whose release lookup failed transiently; explicitly drop repos absent from the successful list and those excluded by the current source filter. Mark retained entries stale and enforce a maximum age.
  Acceptance: A snapshot containing a retired repo and a topic-excluded repo is refreshed with a successful current repo list plus one transient lookup failure. Only the failed current candidate may appear as stale; removed/excluded repos disappear.
  Confidence: Likely
  Effort: M

- [ ] P2 — Surface settings persistence failures and prevent false “saved” state
  Category: reliability
  Where: `app/src/main/kotlin/com/sysadmin/lasstore/ui/settings/SettingsViewModel.kt:41-54`; `data/SettingsStore.kt:52-67`; `data/SecretStore.kt:39-50`.
  Problem: `save()` performs DataStore update and per-source secret writes without catch/finally, saving state, or rollback. A DataStore/Tink/disk failure can partially persist the registry/PATs, silently cancel the coroutine, and leave the previous UI state with no error; the success timestamp is written only on the happy path and there is no in-flight guard.
  Evidence: Lines 47-50 are sequential writes with no error handling, and line 53 is the only UI feedback. The Settings screen has no error/saving state and allows another save while the first is running.
  Fix: Add explicit Saving/Saved/Error state, cancellation-aware error handling, and a retryable recovery path. Use a transaction/outbox or rollback strategy for registry plus secrets, and disable/coalesce concurrent saves; only emit success after every write is verified.
  Acceptance: Inject failure in DataStore and each secret write. The UI shows an actionable error, never says “Registry saved,” and restart recovers either the previous complete configuration or a clearly marked partial/retry state; successful saves are shown only after all writes finish.
  Confidence: Verified
  Effort: M

- [ ] P2 — Preserve and expose malformed multi-source settings instead of silently falling back
  Category: reliability
  Where: `app/src/main/kotlin/com/sysadmin/lasstore/data/SettingsStore.kt:41-50,69-72`.
  Problem: Any malformed or incompatible `github_sources_v1` JSON is converted to `null`, after which the flow synthesizes a single legacy/default source. Opening and saving Settings can therefore hide the user's multi-source registry and overwrite it with a default without warning.
  Evidence: `decodeSources()` catches all decoding failures and returns null; the flow treats null exactly like a missing key at line 48. There is no corrupt-payload backup, error state, or distinction between absent and malformed data.
  Fix: Distinguish missing, malformed, and valid values; preserve the raw payload in a recoverable backup, expose a migration/recovery error, and require an intentional user action before replacing corrupt multi-source data.
  Acceptance: A malformed persisted JSON fixture keeps the raw payload recoverable, renders an actionable recovery state, and cannot be silently replaced by the default source merely by opening/saving unrelated settings.
  Confidence: Needs-repro
  Effort: M

- [ ] P2 — Reconcile catalog state after external uninstall and permission-settings returns
  Category: correctness
  Where: `app/src/main/kotlin/com/sysadmin/lasstore/ui/catalog/CatalogViewModel.kt:221-231,862-867`; `MainActivity.kt:23-35`; active catalog callbacks in `ui/catalog/CatalogExperience.kt:84-129`.
  Problem: Uninstall opens an external system Activity and only logs the intent; there is no ActivityResult or lifecycle-resume reconciliation. Likewise, the install-permission strip is refreshed only at ViewModel initialization or when the user manually taps refresh. Returning from either external flow can leave an Installed/Open action or permission warning stale until a manual catalog refresh.
  Evidence: `uninstall()` calls `openAppInfo()` and returns without updating/reloading state. `MainActivity` has no `onResume`/lifecycle observer, and the active catalog only calls `refreshInstallPermission()` from explicit refresh actions.
  Fix: Observe lifecycle/result return from external settings, refresh `canRequestInstalls()`, reconcile affected package state, and trigger a guarded catalog refresh when the Activity resumes. Preserve scroll/search state while updating the card.
  Acceptance: On a connected API-matrix device, uninstall a card through system settings and return; it becomes NotInstalled without manual refresh. Grant install permission and return; the strip disappears immediately, with no duplicate refresh jobs.
  Confidence: Verified
  Effort: M

- [ ] P2 — Validate and safely handle all external intents
  Category: security
  Where: `app/src/main/kotlin/com/sysadmin/lasstore/ui/catalog/CatalogViewModel.kt:862-879`; `app/src/main/kotlin/com/sysadmin/lasstore/install/PackageInstallerService.kt:33-55`.
  Problem: `openRepo()` parses an API-provided URL and calls `startActivity()` without requiring HTTPS/GitHub host, resolving a handler, or catching `ActivityNotFoundException`/`SecurityException`. The installer permission, delete, and launch helpers also call `startActivity()` directly, so a malformed/malicious repository URL, a race with uninstall, or an OEM without the settings handler can crash the UI instead of showing a recoverable message.
  Evidence: `openRepo()` has no validation or try/catch at lines 876-879. `openInstallPermissionSettings()`, `openAppInfo()`, and `launch()` only check for a null launch intent; their `startActivity()` calls are unguarded.
  Fix: Allow only `https` GitHub repository hosts for repository links; use `resolveActivity()` and wrap every external launch in a typed failure handler. Reconcile package state after a failed/raced launch and show an actionable warning rather than propagating an exception.
  Acceptance: Tests inject malformed schemes, non-GitHub URLs, missing handlers, and an uninstall race; the app never crashes, no arbitrary handler is launched, and the user receives a retry/settings message. Valid GitHub links and installed launchers still open.
  Confidence: Needs-repro
  Effort: M

- [ ] P2 — Avoid silent APK overwrite on API 26–28 Save APK
  Category: ux
  Where: `app/src/main/kotlin/com/sysadmin/lasstore/ui/catalog/CatalogViewModel.kt:1130-1168`.
  Problem: The API 26–28 path copies to a deterministic filename with `overwrite = true`, while API 29+ inserts a separate MediaStore item. Re-saving the same release on legacy devices destroys an existing user/downloaded copy without confirmation and gives different collision semantics by API level.
  Evidence: `source.copyTo(File(downloads, filename), overwrite = true)` is the complete legacy branch; there is no existence check, confirmation, version suffix, or user-facing overwrite message.
  Fix: Use collision-safe names (or an explicit overwrite confirmation) consistently across API levels, preserve an existing file until the new copy succeeds, and tell the user the exact destination.
  Acceptance: Two saves on API 26–28 preserve the first file or require an explicit confirmation; API 29+ behavior is equivalent and covered by a regression test.
  Confidence: Verified
  Effort: S

- [ ] P2 — Make support-bundle exports unique and safe to share repeatedly
  Category: reliability
  Where: `app/src/main/kotlin/com/sysadmin/lasstore/data/SupportBundle.kt:59-82,158-169`; export action in `ui/log/LogScreen.kt:112-145`.
  Problem: Every export deletes every existing support ZIP, and the output name has only second-level timestamp precision. Creating a second bundle invalidates the URI from a still-open share chooser or reader and same-second exports can target the same path.
  Evidence: `create()` loops over all `.zip` files and deletes them before writing `las-support-${fileTimestamp()}.zip`; `LogScreen` immediately shares the returned FileProvider URI and does not retain/clean it through the share lifecycle.
  Fix: Generate unique names using milliseconds/random suffixes, retain a bounded number/age of old bundles, and never delete a file that may still be shared. Clean up only known stale files after a safe retention period.
  Acceptance: Two rapid exports produce distinct readable files/URIs; the first remains readable while the second is shared; a retention test proves the cache is bounded without deleting a currently shared bundle.
  Confidence: Verified
  Effort: S

- [ ] P2 — Bound legacy log reads before materializing them in memory
  Category: reliability
  Where: `app/src/main/kotlin/com/sysadmin/lasstore/data/Logger.kt:114-142`.
  Problem: If a persisted log file is not valid JSONL, `readEntries()` calls `file.readText()` on the entire file before redaction. Current writes rotate at 256 KB, but a legacy/malformed file can be arbitrarily large and cause startup memory pressure or OOM while constructing the single legacy entry.
  Evidence: The fallback branch is selected when `decoded.isEmpty()` and `text` is nonblank, after the whole file has already been loaded. No file-size cap or streaming tail is applied in this path.
  Fix: Read only a bounded tail/stream window before parsing or legacy wrapping, preserve a truncation marker, and handle oversize/corrupt files without allocating their full contents.
  Acceptance: A test places a 100 MB malformed legacy diagnostics/crash file in app-private storage; Logger initializes within the memory budget, retains only the bounded tail, and never OOMs.
  Confidence: Verified
  Effort: S

- [ ] P2 — Give diagnostic list entries collision-free Compose keys
  Category: reliability
  Where: `app/src/main/kotlin/com/sysadmin/lasstore/ui/log/LogScreen.kt:196-204`.
  Problem: The LazyColumn key is `${category.name}-${ts}-${tag}-${message.hashCode()}`. Two identical events recorded in the same millisecond, or a hash collision, create duplicate keys and can throw during composition on the diagnostics screen—the exact screen needed to inspect failures.
  Evidence: `InstallAuditLog`/`Logger` entries have no persisted unique ID, and the UI derives the entire key from timestamp, tag, message hash, and category. A tight loop can produce identical timestamps and messages.
  Fix: Add/use a stable unique event ID from persistence, or combine a stable source ID with a guaranteed unique index while preserving expansion state. Do not rely on `hashCode()` for identity.
  Acceptance: A Compose test renders two identical same-timestamp entries without an exception, and reordering/refreshing preserves the correct expanded row.
  Confidence: Verified
  Effort: S

- [ ] P2 — Bound queue-status persistence and keep disk I/O off the catalog click path
  Category: perf
  Where: `app/src/main/kotlin/com/sysadmin/lasstore/install/BackgroundUpdateScheduler.kt:40-59`; `install/QueuedUpdateStatusStore.kt:171-211`.
  Problem: `markQueued()` runs synchronously before scheduling, and `save()` performs `SharedPreferences.commit()` plus a full `prefs.all` load on every transition. Terminal statuses are never pruned, so the preference file and StateFlow reload grow with every historical repository; the first queue action can block the main thread and eventually jank or ANR the catalog.
  Evidence: `CatalogViewModel.queueBackgroundUpdate()` calls `backgroundUpdates.enqueue()` directly from the UI action, which calls `markQueued()`; `save()` commits and reloads every `status.*` key, with no retention/deletion policy.
  Fix: Move status persistence/reload to a controlled IO/DataStore repository, coalesce updates, and prune terminal entries by age/count or when a source is removed. Keep active/pending records durable and bounded.
  Acceptance: A stress test with 10,000 historical statuses keeps storage/state within the defined bound, preserves active statuses, and verifies the catalog click path does not perform synchronous disk I/O on the main thread.
  Confidence: Likely
  Effort: M

- [ ] P2 — Roll back queued state when both UIDT and WorkManager scheduling fail
  Category: reliability
  Where: `app/src/main/kotlin/com/sysadmin/lasstore/install/BackgroundUpdateScheduler.kt:40-59,112-129`; call site `ui/catalog/CatalogViewModel.kt:709-742`.
  Problem: `enqueue()` marks the item Queued, catches UIDT failure, then calls `enqueueWorker()` outside any catch. WorkManager initialization/database/disk failure can throw on the UI thread after the durable status is already Queued, crashing the Activity and leaving a queue that will never run.
  Evidence: Only `scheduleUidt()` is wrapped in `runCatching`; `WorkManager.getInstance(...).enqueueUniqueWork(...)` is unguarded, and the ViewModel assumes the Boolean return means scheduling completed.
  Fix: Wrap the fallback scheduler and status persistence as one failure-aware operation. On exception, mark a typed Failed/NeedsReschedule state, log the cause without secrets, and return false so the ViewModel can show retry guidance; apply equivalent protection to cancel.
  Acceptance: Inject WorkManager initialization/enqueue failure; no Activity crash occurs, the card is not left Queued, and the user sees an actionable retry/error state. Successful UIDT fallback remains Queued exactly once.
  Confidence: Likely
  Effort: M

- [ ] P2 — Treat publisher-pin replacement as a high-risk audit warning
  Category: ux
  Where: `app/src/main/kotlin/com/sysadmin/lasstore/ui/log/LogScreen.kt:566-575`.
  Problem: The audit event `publisher_pin_replaced` is mapped to `Info` because only `publisher_pin_recovery_authorized` appears in the warning set. The Activity screen therefore renders the actual trust-record replacement as an ordinary informational/check entry, weakening the visual signal for a destructive security decision.
  Evidence: The `when (event)` branch lists `publisher_pin_recovery_authorized` but not `publisher_pin_replaced`; all other events fall through to `LogLevel.Info`.
  Fix: Map both authorization and replacement to a high-risk warning/severity, or introduce a dedicated trust-change severity with explicit copy explaining old pin → new pin and the independent confirmation.
  Acceptance: A `publisher_pin_replaced` entry is rendered with warning/high-risk icon, color, accessible label, and copy that distinguishes it from an ordinary install/info event.
  Confidence: Verified
  Effort: S

- [ ] P2 — Move secret and trust-record I/O off the Compose/ViewModel main path
  Category: perf
  Where: `app/src/main/kotlin/com/sysadmin/lasstore/ui/settings/SettingsViewModel.kt:27-35`; `ui/catalog/CatalogViewModel.kt:612-680`; synchronous reads/writes in `data/SecretStore.kt:90-110,124-141` and `data/InstallAuditLog.kt:147-163`.
  Problem: Settings collection runs on the default Main dispatcher and synchronously reads/decrypts every source PAT. Publisher pin replacement is also called from a Compose click and synchronously performs Tink read/write/readback, audit I/O, and cache work. Slow Keystore/disk or multiple sources can block frames and make a security dialog appear frozen.
  Evidence: `SettingsViewModel.init` launches without a dispatcher and calls `sl.settings.getPat()` inside the collector; `replacePublisherPin()` has no coroutine/IO boundary around `setPin`, readback, audit, or cache calls.
  Fix: Execute secret/durable I/O on `Dispatchers.IO` behind explicit in-flight state, keep the UI responsive/disabled during the operation, and publish success/error back to StateFlow. Avoid decrypting all PATs on the main collector.
  Acceptance: StrictMode or delayed-backend tests show no disk/crypto work on the main thread; settings and trust-recovery screens remain responsive, disable duplicate actions, and render success/error only after the IO operation completes.
  Confidence: Verified
  Effort: M

- [ ] P2 — Split catalog orchestration from the UI ViewModel and queued pipeline
  Category: maintainability
  Where: `app/src/main/kotlin/com/sysadmin/lasstore/ui/catalog/CatalogViewModel.kt` (1,172 lines; refresh/install/trust/queue/save/uninstall methods at `231-1185`); duplicated artifact/install sequencing in `install/QueuedUpdateRunner.kt:88-235`.
  Problem: One UI ViewModel owns catalog refresh, package-state reconciliation, downloads, APK inspection, permission review, PackageInstaller sessions, trust recovery, background queue scheduling, MediaStore export, and external intents. The queued runner independently repeats download → inspect → package/pin checks → install → cache/audit sequencing, so platform/error fixes can drift between foreground and background paths—as already shown by their different durable-recovery and audit handling.
  Evidence: The same security-sensitive operations appear in both files (`github.download`, `apkInspector.inspectResult`, `secrets.getPin/setPin`, `appIdCache`, `audit`, and installer calls), while the ViewModel also contains all UI mutation and lifecycle actions. There is no shared workflow boundary for the common artifact verification/finalization contract.
  Fix: Extract testable application services for catalog refresh, artifact download/inspection policy, install finalization/trust/cache/audit, and queue scheduling; leave the ViewModel as state/event coordination and have foreground/queued workflows call the shared services. Keep platform-specific PackageInstaller adapters behind interfaces.
  Acceptance: A dependency/reference audit shows one shared artifact-verification/finalization path used by foreground and queued installs; unit tests can exercise it without Compose; the ViewModel is reduced to UI orchestration and no behavior diverges between the two workflows.
  Confidence: Verified
  Effort: L

- [ ] P2 — Distinguish “no enabled sources” from an empty catalog
  Category: ux
  Where: `app/src/main/kotlin/com/sysadmin/lasstore/ui/catalog/CatalogViewModel.kt:231-255`; `ui/catalog/CatalogExperience.kt:121-130,524-575`; enabled-source controls in `ui/settings/SettingsScreen.kt:348-361`.
  Problem: Refresh filters out disabled sources, but the empty state does not know whether there are zero enabled sources or whether enabled sources returned no releases. With every source disabled, the user sees “No releases on the shelf” and generic Settings text instead of the direct cause and a settings action.
  Evidence: `enabledSources` is counted only for logging; `CatalogEmpty` receives only `errorMessage`, and no catalog issue is created for an empty enabled-source list.
  Fix: Add an explicit zero-enabled-sources state with copy such as “No sources enabled,” a Settings CTA, and a refresh/re-entry path. Keep true no-release and error states distinct.
  Acceptance: Disable all sources and refresh: the empty view identifies the disabled-source cause and opens Settings; enable one source and refresh: the normal empty/catalog state replaces it.
  Confidence: Verified
  Effort: S

- [ ] P2 — Clear stale “Registry saved” feedback when drafts change
  Category: ux
  Where: `app/src/main/kotlin/com/sysadmin/lasstore/ui/settings/SettingsScreen.kt:64-78,129-155,180-194`; `SettingsViewModel.kt:15-20`.
  Problem: `savedAt` is set after a successful save but is never cleared when local `drafts` change. The user can add/edit/remove/disable a source and still see “Registry saved · N sources ready to sync,” which describes the previous persisted state while the screen shows unsaved values.
  Evidence: The banner condition is only `state.savedAt > 0L`; every draft mutation updates local `drafts` without informing the ViewModel or changing `savedAt`.
  Fix: Track dirty state against the last persisted normalized sources and PATs; clear/replace the saved banner on any edit, show Saving/Error states, and use copy that distinguishes local draft from persisted registry.
  Acceptance: Edit a field or toggle after saving and verify the saved banner disappears/reports unsaved changes; save again and verify it returns only after persistence succeeds with the current source count.
  Confidence: Verified
  Effort: S

- [ ] P2 — Redact credentials before truncating support/log text
  Category: security
  Where: `app/src/main/kotlin/com/sysadmin/lasstore/data/SupportBundle.kt:39-54`; callers in `data/Logger.kt:49-55,88-94` and support export at `112-124`.
  Problem: `SupportRedactor.redact()` first truncates to `maxChars` and only then applies credential regexes. A bearer token or PAT that begins near the truncation boundary is shortened below the regex minimum and its visible prefix can be written to logs or exported support bundles.
  Evidence: With a credential positioned across the configured boundary, the current implementation returns a bounded string containing the remaining `Bearer abc` prefix after truncation; the existing tests cover complete tokens but not boundary placement.
  Fix: Redact the full input before truncating, or use a secret-aware boundary scanner that redacts any credential crossing the cut while preserving the size bound. Apply the same order to persisted logs and bundle entries.
  Acceptance: Boundary fixtures for Bearer, GitHub PAT, URL query, and named credentials prove neither full secrets nor usable prefixes appear in logger files or exported ZIP entries, while output remains bounded.
  Confidence: Verified
  Effort: S

- [ ] P2 — Keep instrumentation screenshots private and clean them up
  Category: security
  Where: `app/src/androidTest/kotlin/com/sysadmin/lasstore/ui/log/LogScreenInstrumentedTest.kt:79-105`; `app/src/androidTest/kotlin/com/sysadmin/lasstore/ui/catalog/PublisherTrustRecoveryDialogInstrumentedTest.kt:75-103`.
  Problem: Connected tests insert screenshots into public `MediaStore.Images.Media.EXTERNAL_CONTENT_URI` under `Pictures/LocalAndroidStoreTest` and never delete the returned URI. Every run leaves diagnostic/trust screenshots accessible in the user's gallery and can leak source/package/fingerprint details.
  Evidence: Both `saveScreenshot()` methods insert and publish `IS_PENDING=0`; their cleanup paths clear logs/audit data but do not retain or delete the image URI. The test suite invokes these helpers.
  Fix: Write screenshots to app-private cache or test-only storage and delete them in `finally`/`@After`; expose a separate explicit artifact-copy option for CI when needed. Do not publish test artifacts to the user's MediaStore by default.
  Acceptance: Repeated connected test runs leave no `Pictures/LocalAndroidStoreTest` artifacts and all screenshots are removed after the test; an opt-in CI artifact path remains available without public gallery writes.
  Confidence: Verified
  Effort: S

- [ ] P2 — Resolve the release-version mismatch in public documentation
  Category: docs
  Where: `README.md:4` shows badge version `0.2.2`; `app/build.gradle.kts:19-20` declares `versionCode = 5`/`versionName = "0.2.3"`; `ROADMAP.md:5,267` and the release history identify v0.2.3 and require these values to match.
  Problem: The published README badge tells users the app is v0.2.2 while the built APK and roadmap identify v0.2.3. This undermines release identification, support diagnostics, and reproducibility even though the source version is otherwise consistent.
  Evidence: Direct read of the four version sources shows the README badge is the only `0.2.2` value in the release identity set; the roadmap's own versioning policy explicitly requires the badge, Gradle, changelog, and version history to match.
  Fix: Update the README badge and any release-facing metadata together in the release process, then add a check that extracts the badge/versionName/versionCode and fails on divergence. Do not alter version numbers as part of this audit item unless the release owner confirms the intended version.
  Acceptance: A release metadata check reports one agreed version (`0.2.3` for the current build) across README, Gradle, changelog, and roadmap; the badge link/alt text matches the built APK.
  Confidence: Verified
  Effort: S

- [ ] P2 — Add live API/device verification before release sign-off (pre-existing baseline; unaudited)
  Category: testing
  Where: Baseline command `.\gradlew.bat connectedDebugAndroidTest --no-daemon --console=plain --max-workers=1`; `scripts/verify-trust-matrix.ps1:1-119`; emulator inventory and `adb devices -l`.
  Problem: The full connected suite could not run: Gradle built the test APKs but failed with `com.android.builder.testing.api.DeviceException: No connected devices!`; `adb devices -l` was empty, and the script's default API 26/35/37 AVD names were not installed. Consequently process-death/install behavior, PackageInstaller branches, MediaStore/API-level behavior, TalkBack/focus, font scale, RTL, theme/nested-surface rendering, and the real trust matrix remain unaudited.
  Evidence: The exact command failed before test execution. Available AVD names were `clearcut-api37-ps16k` and `issue-sweep-api36`, not the script defaults. The harness launches with `-WindowStyle Hidden` but does not pass emulator `-no-window` or redirect emulator stdout/stderr, so it is not yet safe under the repository's operator-display isolation requirement.
  Fix: Provision isolated API 26/35/37 emulators or run the matrix in CI, make the harness truly headless (`-no-window`, redirected logs, bounded workers, deterministic cleanup), and execute the full connected suite including accessibility, 200% font scale, RTL, trust, install recovery, and secondary-screen tests.
  Acceptance: A documented headless command provisions/uses the three API levels, runs `connectedDebugAndroidTest` for each without any visible window, reports test counts/results and artifacts, and leaves no emulator/process behind. Keep this item open until the live matrix is actually green.
  Confidence: Verified
  Effort: L

- [ ] P2 — Make asynchronous status and error changes discoverable to TalkBack
  Category: a11y
  Where: `app/src/main/kotlin/com/sysadmin/lasstore/ui/catalog/CatalogExperience.kt:98-107`; status/message rendering in `ui/catalog/ReleaseCard.kt:247-260,523-570` and catalog refresh state in `CatalogViewModel.kt:231-272`.
  Problem: Refresh errors, queued notices, install progress/failure, permission review, and completion messages are rendered as ordinary `Text` without a live region or explicit accessibility announcement. A TalkBack user can activate an action and receive no announcement while the visible card changes or a warning strip appears.
  Evidence: The active catalog places `state.warning`/`state.catalogNotice` directly in composables and card messages are plain text; no `liveRegion`, `announceForAccessibility`, or equivalent deduplicated announcement exists in these paths.
  Fix: Add polite live-region semantics for non-blocking status and error changes, assertive only for safety-critical failures, and deduplicate announcements so recomposition does not repeat them. Include the affected app name and next action.
  Acceptance: Compose semantics tests observe live-region properties/announcements for refresh error, queued, install success, and install failure; a TalkBack pass reads each transition once and does not announce progress on every percent update.
  Confidence: Needs-repro
  Effort: M

- [ ] P2 — Remove the duplicate accessibility toggle in trust recovery
  Category: a11y
  Where: `app/src/main/kotlin/com/sysadmin/lasstore/ui/catalog/PublisherTrustRecoveryDialog.kt:205-226`.
  Problem: The recovery confirmation row is clickable and also contains a separately clickable `Checkbox`. Accessibility services can expose two toggle targets for one acknowledgement, and a row/label activation can toggle twice or leave focus semantics ambiguous.
  Evidence: The parent `Row` uses `.clickable { independentlyVerified = !independentlyVerified }` while the child `Checkbox` has its own `onCheckedChange`. There is no merged/toggleable role semantics that makes the row one control.
  Fix: Use one semantic control: either a `Row.toggleable(role = Role.Checkbox)` with a non-clickable visual Checkbox, or a single Checkbox with a properly associated/merged label. Ensure keyboard and TalkBack activation toggles exactly once.
  Acceptance: Semantics tests expose one focusable checkbox with its full label and checked state; row click, checkbox click, Space, and TalkBack double-tap each produce one state transition.
  Confidence: Verified
  Effort: S

- [ ] P2 — Prevent brand/header clipping at large font scales and narrow widths
  Category: visual
  Where: `app/src/main/kotlin/com/sysadmin/lasstore/ui/catalog/CatalogExperience.kt:215-234`.
  Problem: The active hero renders the full “LocalAndroidStore” brand in `displaySmall` with `maxLines = 1` and `TextOverflow.Clip` inside a weighted column. At 200% font scale, RTL, or a narrow device, the name can be clipped without an accessible alternative, reducing hierarchy and leaving the adjacent refresh control crowded.
  Evidence: The text has no wrapping/measurement fallback and the existing connected suite was unavailable, so the high-font-scale rendering was not observed on a device.
  Fix: Allow the brand to wrap or use a measured typography/layout fallback that preserves the full accessible text and refresh target; test the smallest supported width, 200% font scale, and RTL.
  Acceptance: Screenshot/semantics tests at 200% font scale and RTL show the complete accessible brand, no clipped critical text, and a reachable 48dp refresh control.
  Confidence: Needs-repro
  Effort: S
```

</details>
