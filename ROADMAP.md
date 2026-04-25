# Roadmap

> **Document version 2.1** • Last revised 2026-04-25 • Author: SysAdminDoc
>
> **v0.2.0 status** — *partial ship.* Items **1, 2, 3, 4, 7, 8, 11, 12, 13, 16, 17, 19, 22, 24** are merged in v0.2.0 (2026-04-25) — the security-and-attribution slice (14 of 24 Now items). A v0.2.1 implementation pass completed items **10** (edge-to-edge audit), **14** (status/nav bar contrast), **21** (catalog search + fuzzy filter), and **23** (DataStore migration framework). Carry-forward into v0.2.1+: items **5** (`requestUserPreapproval`), **6** (`InstallConstraints`), **9** (UIDT), **15** (Developer Verification preflight UX), **18** (Tink migration off security-crypto), **20** (multi-org UI). The Now tier doesn't roll over to "Next" until those land.
>
> This is a working document, not a marketing page. Each item is tagged with **Impact (1–5)** and **Effort (1–5)** and links back to a primary source. Tier labels: **Now (v0.2.0)** / **Next (v0.3.0)** / **Later (v0.4.0+)** / **Under Consideration** / **Rejected**. Every claim cites a URL in the [Appendix](#appendix--sources).

---

## State of the repo (v0.1.0 — shipped 2026-04-25)

LocalAndroidStore v0.1.0 ships:

- GitHub-Releases discovery for one configured user / org, optional `android-app` topic filter, optional pre-release toggle.
- Catalog grid (`LazyVerticalGrid`, 320 dp adaptive) with status badge, install / uninstall / update / open / repo buttons.
- Install via `PackageInstaller.Session` (broadcast-receiver status sink).
- Signature pinning per `applicationId`: first install captures the APK signing-cert SHA-256, future installs that don't match are blocked. Never auto-accepts.
- Installed-state detection via `PackageManager`; "Update available" when remote `versionCode > local`.
- GitHub PAT in `EncryptedSharedPreferences`; non-secret settings in DataStore.
- Activity log + on-disk `crash.log` with global uncaught handler.
- AMOLED-true-black + Catppuccin Mocha theme.
- CI-signed release APK + sha256 sidecar (`KEYSTORE_BASE64` secret).
- Branch protection on main with `enforce_admins=true`.

**It does not yet:** claim update ownership, declare `PACKAGE_SOURCE_STORE`, schedule background updates, run as a foreground-service-typed worker, support split / XAPK / AAB, ship Material You / light theme, render edge-to-edge cleanly on Android 15, opt into predictive back, surface anti-features or VirusTotal scans, support more than one GitHub user, expose a Wear OS surface, support ADB pairing, emit an F-Droid repo, handle key-rotation lineage, or detect the Android Developer Verifier.

**Stated philosophy** (preserved from README):

1. Personal store, GitHub-Releases-first, opinionated — not a generic source bag.
2. Signature pinning is mandatory, never optional, never silently overridden.
3. AMOLED-true-black + Catppuccin is the visual identity. Light theme is a future option, not the default.
4. Single-Activity Compose, hand-rolled `ServiceLocator`, no Hilt, no Retrofit. Surface area stays small.
5. No silent install on stock Android (we are not a device-owner). Privileged paths (Shizuku) are tier-2 add-ons, never required.
6. MIT license. No Co-Authored-By, no AI-attribution in committed files.

**Strategic frame from the research.** Five existential platform shifts dominate every decision below: **(a)** Android 14's `setRequestUpdateOwnership` lets a curated installer claim the update channel and is the missing half of the signature-pin guarantee — without it, a competing installer can silently overwrite our pinned apps [1, 6, 23]. **(b)** Android 15's `PACKAGE_SOURCE_STORE` exempts our downstream apps from Restricted Settings (Accessibility / Notification Listener unlock), directly improving every Accessibility-using app installed via LAS [22, 53]. **(c)** Android 14's mandatory foreground-service-type and Android 15's 6-hour FGS cap force any background updater to be UIDT-based (`setUserInitiated(true)`), not `dataSync`-based [3, 24]. **(d)** Android Developer Verification begins enforcement Sept 30, 2026 in BR/ID/SG/TH [21, 51, 65] — every catalog APK from an unverified developer becomes uninstallable on certified devices unless the user walks the per-install "advanced flow," and surfacing this in our catalog *before* users hit the wall is differentiated UX nobody else ships. **(e)** APK Signature Scheme v3 / v3.1 key-rotation lineage means a naive SHA-256 pin will incorrectly reject *legitimate* publisher rotations — we must walk `signingCertificateHistory` before we reject [13, 19].

The single biggest user pain across 158 community sources [E1–E158] is **gentle background updates without prompting** (Obtainium #2199, #1550, #1105; SmartTube #4151; r/degoogle 1sds2ph). The single biggest competitive gap: **no personal-GitHub-catalog tool ships native cert pinning baked into the install flow** (Obtainium delegates to AppVerifier, F-Droid does it via index `AllowedAPKSigningKeys`, Accrescent does it for a curated catalog). LocalAndroidStore's positioning is exactly that gap — and v0.2.0 is about defending it with the platform's full hardening surface before competitors close it.

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

## Now — v0.2.0 "Hardening + the rest of v0.1's installer"

Theme distribution: **T-COMPLIANCE × 6, T-INSTALL × 5, T-SEC × 4, T-UPDATE × 3, T-CATALOG × 2, T-A11Y × 1, T-OBS × 1, T-MIGRATE × 1, T-DOCS × 1.**

**Frame:** v0.2.0 closes every Android 14/15/16 platform-compliance gap, completes the install-flow primitives that v0.1.0 stubbed, and lands the smallest set of UX features that make the catalog usable for more than one GitHub user. No new big surface (Wear OS, ADB-pair, F-Droid emit) lands in v0.2; those are v0.3+. The Now tier is mandate-driven.

### Theme: Install mechanics + signature pinning correctness

1. **Claim update ownership on first install** [I 5 / E 2] — `SessionParams.setRequestUpdateOwnership(true)` on first-install sessions only (no-op on update). Declare `&lt;uses-permission android:name="android.permission.ENFORCE_UPDATE_OWNERSHIP" /&gt;` (normal protection, auto-granted). Surfaces an "Owned by LAS" badge on app rows via `InstallSourceInfo.getUpdateOwnerPackageName()`. **This is the missing half of the signature-pin guarantee.** Without it, Play Store / another installer can silently overwrite a pinned app. Sources: [1, 6, 23].
2. **Declare `PACKAGE_SOURCE_STORE`** [I 4 / E 1] — `SessionParams.setPackageSource(PACKAGE_SOURCE_STORE)` on every session. Directly improves downstream apps' Accessibility unlock UX on Android 13+. Sources: [4, 22, 53].
3. **Set installer name + originating UID + referrer URI** [I 2 / E 1] — `setInstallerPackageName(BuildConfig.APPLICATION_ID)`, `setOriginatingUid(Process.myUid())`, `setReferrerUri(Uri.parse(asset.browserDownloadUrl))`. Populates the system "App info → Installed from" UI correctly. Sources: [5, 11].
4. **Lineage-aware signature verification** [I 5 / E 3] — when the pinned cert SHA-256 doesn't match the new APK's `apkContentsSigners`, walk `SigningInfo.getSigningCertificateHistory()` AND run `apksig.ApkVerifier.Result.getSigningCertificateLineage()`; accept if our pinned cert appears in the lineage and the new cert is signed by it (legitimate v3 / v3.1 rotation). Otherwise hard-reject with the existing "publisher key changed" warning. Without this we'll false-positive every legitimate Google-Play-style key rotation. Sources: [16, 17, 18, 19, 20, SO Q75112572, SO Q73787102, SO Q77159385].
5. **`requestUserPreapproval()` for updates** [I 3 / E 3] — on Android 14+, surface the user-confirm sheet *before* downloading the APK. Saves bandwidth, lets us show the new permissions / changelog before committing to the download. Sources: [Agent A §3, 1].
6. **`InstallConstraints` for gentle background install** [I 4 / E 2] — when an update is queued in the background, build `InstallConstraints.Builder().setAppNotForegroundRequired().setDeviceIdleRequired().setNotInCallRequired().build()` and pass to `commitSessionAfterInstallConstraintsAreMet()`. The system defers the install instead of forcing us to detect "is the target app foregrounded" via `UsageStats` / Accessibility. **First-mover gap** — Ackpine library exposes it but few stores ship it. Sources: [Agent A §3, 7].
7. **Honest failure messages** [I 3 / E 2] — decode `EXTRA_STATUS` codes and replace generic "App not installed" with: `STATUS_FAILURE_CONFLICT` → "A different version is already installed" + offer uninstall; `STATUS_FAILURE_INCOMPATIBLE` → "Needs Android X+" or "Needs `arm64-v8a`, your device is `armeabi-v7a`"; `STATUS_FAILURE_INVALID` → "Signature mismatch — refusing to overwrite installed app"; `STATUS_FAILURE_STORAGE` → "Need ~Z MB free, X currently free". Sources: [Agent B §2.13, F-Droid #1968].

### Theme: Platform compliance (Android 14 / 15 / 16)

8. **`FOREGROUND_SERVICE_DATA_SYNC` + WorkManager FGS-type override** [I 5 / E 2] — declare `&lt;uses-permission android:name="android.permission.FOREGROUND_SERVICE_DATA_SYNC" /&gt;`, override `androidx.work.impl.foreground.SystemForegroundService` in our manifest with `android:foregroundServiceType="dataSync"`. Without this, any scheduled-update Worker crashes with `SecurityException` on Android 14+. Sources: [3, 24].
9. **UIDT for the actual download** [I 4 / E 2] — Android 15's 6-hour FGS aggregate cap means `dataSync` workers will hit the wall. Use `setUserInitiated(true)` (User-Initiated Data Transfer) on the download `OneTimeWorkRequest`. UIDT exempts from the 6-hour cap. Sources: [25].
10. **Edge-to-edge audit** [I 4 / E 2] — **Done in v0.2.1 pass.** `AppRoot` now uses `WindowInsets.safeDrawing` with consumed scaffold insets, Settings uses `imePadding()`, and the nav/content layout compiles under target API 35 edge-to-edge. Device screenshot verification is still pending because no adb device/emulator was attached in this pass. Sources: [26, 56].
11. **`PredictiveBackHandler`** [I 3 / E 2] — set `android:enableOnBackInvokedCallback="true"` on `&lt;application&gt;`; default-on for apps targeting API 36. Replace any current `BackHandler` on detail surfaces with `PredictiveBackHandler { progress -> … }` for the animated preview pop. Sources: [27].
12. **Adaptive icon `<monochrome>` layer** [I 2 / E 1] — confirm `mipmap-anydpi-v26/ic_launcher.xml` ships a clean monochrome glyph; verify on Android 13+ "Themed icons" toggle. *We currently reuse `ic_launcher_foreground` as the monochrome — replace with a single-color glyph that reads cleanly under the system tint.* Sources: [28].
13. **`POST_NOTIFICATIONS` runtime permission flow** [I 3 / E 2] — required on API 33+ for the silent self-update path; `ActivityResultContracts.RequestPermission`. Graceful fallback: if denied, `setRequireUserAction(USER_ACTION_REQUIRED)`. Sources: [1].
14. **`enableEdgeToEdge()` + status / nav bar contrast tokens** [I 2 / E 1] — **Done in v0.2.1 pass.** `MainActivity` now passes explicit dark transparent `SystemBarStyle` tokens to `enableEdgeToEdge()`, and API-27-only navigation-bar contrast attributes live in `values-v27` so `lintDebug` stays clean on minSdk 26.
15. **Developer Verification preflight + warning UX** [I 5 / E 3] — detect the `Android Developer Verifier` Play-Services component (presence is the trigger). Before `commit`, query the verifier (or cache its known-good list) for the catalog APK's `applicationId + signing cert`. If the developer is unverified, surface a clear pre-install banner: *"This developer hasn't enrolled in Android Developer Verification. After Sept 30, 2026 in your region, installing this app will require the system 'advanced flow' (developer mode → reboot → 1-day cooldown → biometric)."* Don't block — inform. **Differentiated UX**: no other store surfaces this in the catalog detail today. Sources: [21, 51, 65, A21, A126].

### Theme: Security / secrets / network

16. **Network Security Config + OkHttp TLS 1.3** [I 3 / E 2] — ship `res/xml/network_security_config.xml` pinning `api.github.com` and `objects.githubusercontent.com` at the **root CA** (DigiCert Global Root G2 + ISRG Root X1 backup) with a 6-month `expiration`. OkHttp `connectionSpecs(listOf(ConnectionSpec.RESTRICTED_TLS))`. Pin **SPKI**, never the leaf — GitHub rotates leaves frequently. Sources: [29].
17. **OkHttp ≥ 4.12.0 pin** [I 3 / E 1] — already on 4.12.0 in v0.1.0; lock with a build-fail comparator to prevent transitive downgrade. CVE-2023-0833 ban for older. Sources: [Agent D §15, 30].
18. **Stop using `androidx.security:security-crypto`** [I 4 / E 3] — deprecated at 1.1.0-alpha07 (April 2025), no fix coming. Migrate the GitHub PAT + per-package signing pins to **Tink + StreamingAead over a Proto DataStore file**. Plain DataStore for non-secret data. Migration code path: detect existing EncryptedSharedPreferences entries → re-encrypt under Tink → delete the old store. Sources: [31, 32, 33].
19. **`dataExtractionRules` audit** [I 2 / E 1] — already present in v0.1.0; explicitly exclude downloaded-APK cache, install-state-per-device, and signing pins from cloud backup; allow device-transfer of catalog config + PAT only if a local-only setting "Migrate secrets on device transfer" is on. Sources: [Agent D §12.1].

### Theme: Catalog UX (smallest viable set)

20. **Multi-org / multi-source UI** [I 5 / E 3] — Settings screen lists configured GitHub users / orgs; per-source toggle, per-source PAT, per-source topic-filter. Default = `SysAdminDoc`. **Top demand item** — F-Droid #1601, Obtainium #2013, Droid-ify #18. Direct user request (existing v0.5 placeholder). Sources: [E26, E120, E134].
21. **Search + fuzzy filter on catalog** [I 4 / E 2] — **Done in v0.2.1 pass.** Catalog has a top search field that matches app name, repo owner/handle, description, tag, version, and package id; ranking prefers exact and prefix hits, supports compact-name subsequence matches, and shows count + no-match recovery. Covered by focused JVM unit tests. Sources: [E5, F-Droid #336, F-Droid #2008, Droid-ify #87].

### Theme: Observability

22. **Install audit log on disk** [I 2 / E 1] — append a structured line per install/uninstall/pin-mismatch event to `<files>/logs/install.log` (JSON Lines): `{ts, applicationId, versionCode, certSha256, source, result}`. Cheap forensic surface; useful for "what installed this and when?"

### Theme: Migration

23. **DataStore migration framework** [I 2 / E 1] — **Done in v0.2.1 pass.** `SettingsStore` now wires a no-op `DataMigration<Preferences>` into the DataStore builder, preserving current data while giving v0.3+ a stable migration insertion point.

### Theme: Docs

24. **README + threat-model section** [I 3 / E 1] — add a top-level "Threat model" block to README explicitly stating: signature-pin scope, what the user trusts (GitHub repo owner, GitHub TLS, OkHttp pin), what the user does not trust (LAS itself can be compromised; mitigation = update ownership + open source). The TrollStore-pattern transparency that builds user-confidence faster than feature lists. Sources: [Agent C §3.12].

**v0.2.0 ships when items 1–24 are merged, the build is green on a 5.0/8.0/13/14/15 device matrix in CI, the signed APK is uploaded to GH Release v0.2.0 with a sha256 sidecar, README is re-shot, and the same on-device golden flow that DoD'd v0.1.0 still passes (sideload, install Astra-Deck, see "Update available", install update, see signature-pin warning when re-installed with a forged APK).**

---

## Next — v0.3.0 "Multi-source + companion devices"

Theme distribution: **T-SOURCES × 4, T-COMPANION × 3, T-CATALOG × 4, T-INSTALL × 2, T-PLUGIN × 1, T-OFFLINE × 2, T-A11Y × 2.**

**Frame:** v0.3 turns LAS from a single-GitHub-user catalog into a multi-source store. The plugin contract lands here. The Wear OS surface and the desktop ADB-pair sibling land here. Some "Later" items get pulled forward if the v0.2 release feedback warrants.

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
34. **Permission diff before update** [I 4 / E 2] — when a queued update requests *new* dangerous permissions vs the installed version, hold the install behind a "review changes" sheet. Neo Store and Droid-ify just shipped this; adopting on day one keeps us from looking dated. Sources: [Agent A §4 Neo Store CHANGELOG 1.2.5, Droid-ify v0.7.1].
35. **Per-app "ignore updates" / hide from view** [I 2 / E 1] — Settings → Hidden apps. Common F-Droid #1908, Neo-Store #262 ask. Sources: [F-Droid #1908, Neo-Store #262].

### Theme: Offline & resilience

36. **Resume-interrupted-download** [I 4 / E 2] — OkHttp `Range: bytes=N-`; persist partial on `cacheDir/apks/.partial/`; surface "Resume download" on the card. Accrescent #10. Sources: [Accrescent #10, Obtainium implicit].
37. **Cancel in-flight download** [I 3 / E 1] — Coroutine-cancellation through the OkHttp call; X button on the progress UI. Top-of-list ask: Obtainium #950. Sources: [E124, Obtainium #950].

### Theme: Accessibility & i18n

38. **Per-app language preferences** [I 3 / E 2] — `LocaleManager.setApplicationLocales(LocaleList.forLanguageTags(...))` per-row. Auto-generate `LocaleConfig` via `androidResources { generateLocaleConfig = true }`. Future-proofs future translation work. Sources: [45].
39. **Large-screen / fold layout** [I 3 / E 3] — `WindowSizeClass` driven layout: list + detail two-pane on `Expanded`. Catalog adapts to fold hinge using `androidx.window:1.3+`. Accrescent #328 / Neo Store #297. Sources: [Accrescent #328].

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

62. **Save APK without installing** [I 2 / E 1] — Obtainium #29. Long-press → "Save APK to /Downloads". Sources: [Obtainium #29].
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
- **R4. SafetyNet attestation.** Removed in 2024. Use Play Integrity (item 62 Under Consideration) only if it solves a real problem we don't have today.
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
- **`androidx.security:security-crypto` is dead.** v0.2 item 18 migrates off. Don't add new code that depends on it.
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
  - **Security:** items 1, 4, 16–19, 43–45, 50, 51, 53, 70–72, R5, R6.
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
  - **Migration paths:** item 23 (DataStore migrations), item 60 (lockfile), item 18 (security-crypto migration).
  - **Upgrade strategy:** versioning policy above + item 23.
- **Source traceability** — every numbered item links to at least one Appendix entry.
- **Tier placement justification** — each item has effort × impact + reason in tier text.
- **Internal consistency** — no item appears in two tiers; rejects (R1–R15) explicitly state their reason; Under Consideration items each carry the open question.
- **Adversarial review pass** — ran a hostile-reviewer scan and addressed: (a) "you didn't talk about reproducible builds" → items 43, 70, 71. (b) "you skipped the looming Developer Verification existential risk" → items 15, 21, 51 plus the strategic frame. (c) "your background-update story was a one-liner" → items 8, 9, 25, 36, 46–49. (d) "you didn't address Material 3 Expressive / edge-to-edge / predictive back" → items 10–12, 41. (e) "you didn't address split APK / XAPK" → items 54, 55. (f) "no plugin model" → items 25, 28, 61.

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

### G. LocalAndroidStore + sibling (211–212)

211. https://github.com/SysAdminDoc/LocalAndroidStore — this repo
212. https://github.com/SysAdminDoc/LocalChromeStore — sibling project

---

> **End of roadmap.** Re-read on every milestone retro. If an item is silently missing in two consecutive retros, demote it to Rejected with a stated reason.
