package com.sysadmin.lasstore.install

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.IntentSender
import android.content.pm.PackageInstaller
import android.icu.util.ULocale
import android.net.Uri
import android.os.Build
import android.os.Process
import android.provider.Settings
import androidx.annotation.RequiresApi
import com.sysadmin.lasstore.data.Logger
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

private val PACKAGE_NAME_PATTERN = Regex(
    "^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+$",
)

class PackageInstallerService(
    private val context: Context,
    private val logger: Logger,
) {
    private val resultRegistry = InstallResultRegistry(context)
    private val shizukuInstaller = ShizukuInstaller(context, logger)

    private data class InstallerHandle(
        val packageInstaller: PackageInstaller,
        val shizuku: ShizukuInstaller.Handle? = null,
    ) {
        val isSilent: Boolean get() = shizuku != null
    }

    /**
     * Whether the current app is allowed to drive the system installer dialog.
     * On Android 8.0+ this is the per-app "Install unknown apps" toggle the user must enable.
     */
    fun canRequestInstalls(): Boolean = isSilentInstallActive() ||
        context.packageManager.canRequestPackageInstalls()

    fun shizukuStatus(): ShizukuStatus = shizukuInstaller.status()

    fun shizukuSilentInstallEnabled(): Boolean = shizukuInstaller.settings.isEnabled()

    fun setShizukuSilentInstallEnabled(enabled: Boolean): Boolean =
        shizukuInstaller.settings.setEnabled(enabled)

    fun requestShizukuPermission(): Boolean = shizukuInstaller.requestPermission()

    fun isSilentInstallActive(): Boolean =
        shizukuSilentInstallEnabled() && shizukuStatus() == ShizukuStatus.Ready

    /** Open Shizuku's manager when it is installed, without making it a hard dependency. */
    fun openShizukuManager(): ExternalLaunchResult {
        val intent = context.packageManager
            .getLaunchIntentForPackage(SHIZUKU_MANAGER_PACKAGE)
            ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ?: return ExternalLaunchResult.Failed(
                "Install and start Shizuku before enabling no-prompt installs.",
            )
        return launchExternalIntent(
            intent = intent,
            failureMessage = "Could not open Shizuku.",
        )
    }

    /** Open the system Settings page where the user grants "Install unknown apps". */
    fun openInstallPermissionSettings(): ExternalLaunchResult {
        val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES)
            .setData(Uri.parse("package:${context.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return launchExternalIntent(
            intent = intent,
            failureMessage = "Could not open Android's install-permission settings.",
        )
    }

    /** Open the system app-info screen so the user can hit Uninstall. */
    fun openAppInfo(applicationId: String): ExternalLaunchResult {
        if (!PACKAGE_NAME_PATTERN.matches(applicationId)) {
            return ExternalLaunchResult.Failed(
                "Could not open app settings: the package id is invalid.",
            )
        }
        val intent = Intent(Intent.ACTION_DELETE)
            .setData(Uri.parse("package:$applicationId"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return launchExternalIntent(
            intent = intent,
            failureMessage = "Could not open Android's uninstall screen.",
        )
    }

    /** Request Android 15+ to remove the APK while retaining the app's user data and launcher stub. */
    fun requestArchive(applicationId: String): ArchiveRequestResult {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            return ArchiveRequestResult.Failed("App archiving requires Android 15 or newer.")
        }
        if (!PACKAGE_NAME_PATTERN.matches(applicationId)) {
            return ArchiveRequestResult.Failed("Could not archive the app: the package id is invalid.")
        }
        return try {
            context.packageManager.packageInstaller.requestArchive(
                applicationId,
                archiveOperationPendingIntent(applicationId, ACTION_ARCHIVE_RESULT).intentSender,
            )
            ArchiveRequestResult.Requested
        } catch (throwable: Throwable) {
            logger.warn("Installer", "Could not archive $applicationId: ${throwable.message}")
            ArchiveRequestResult.Failed(
                throwable.message ?: "Android could not archive this app.",
            )
        }
    }

    /** Ask Android to dispatch an archived-package restore request to LocalAndroidStore. */
    fun requestUnarchive(applicationId: String): ArchiveRequestResult {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            return ArchiveRequestResult.Failed("App archiving requires Android 15 or newer.")
        }
        if (!PACKAGE_NAME_PATTERN.matches(applicationId)) {
            return ArchiveRequestResult.Failed("Could not restore the app: the package id is invalid.")
        }
        return try {
            context.packageManager.packageInstaller.requestUnarchive(
                applicationId,
                archiveOperationPendingIntent(applicationId, ACTION_UNARCHIVE_RESULT).intentSender,
            )
            ArchiveRequestResult.Requested
        } catch (throwable: Throwable) {
            logger.warn("Installer", "Could not request restore for $applicationId: ${throwable.message}")
            ArchiveRequestResult.Failed(
                throwable.message ?: "Android could not request this app's restore.",
            )
        }
    }

    /** Complete the system's short unarchive hand-off before network/install work begins. */
    @RequiresApi(Build.VERSION_CODES.VANILLA_ICE_CREAM)
    fun reportUnarchivalStatus(unarchiveId: Int, status: Int): Boolean = runCatching {
        context.packageManager.packageInstaller.reportUnarchivalStatus(
            unarchiveId,
            status,
            0L,
            null,
        )
    }.onFailure { throwable ->
        logger.warn("Installer", "Could not report unarchive status: ${throwable.message}")
    }.isSuccess

    /** Open Android 13+'s per-app language page for an installed catalog app. */
    fun openAppLanguageSettings(applicationId: String): ExternalLaunchResult {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            return ExternalLaunchResult.Failed(
                "Per-app language settings require Android 13 or newer.",
            )
        }
        val intent = appLanguageSettingsIntent(applicationId)
            ?: return ExternalLaunchResult.Failed(
                "Could not open app language settings: the package id is invalid.",
            )
        return launchExternalIntent(
            intent = intent,
            failureMessage = "Could not open Android's app-language settings.",
        )
    }

    /**
     * Open Android's public developer-settings entry point for the advanced sideloading flow.
     * Android's final developer-registration decision remains owned by PackageInstaller when the
     * APK install is committed; no public third-party intent exists for bypassing that flow.
     */
    fun openAdvancedSideloadingSettings(): ExternalLaunchResult {
        return launchExternalIntent(
            intent = advancedSideloadingIntent(),
            failureMessage = "Could not open Android's developer settings.",
        )
    }

    /** Launch the installed app's main activity. */
    fun launch(applicationId: String): ExternalLaunchResult {
        val intent = context.packageManager.getLaunchIntentForPackage(applicationId)
            ?: return ExternalLaunchResult.Failed(
                "Couldn't launch $applicationId — no exported launcher activity was found.",
            )
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return launchExternalIntent(
            intent = intent,
            failureMessage = "Couldn't launch $applicationId.",
        )
    }

    private fun launchExternalIntent(
        intent: Intent,
        failureMessage: String,
    ): ExternalLaunchResult {
        val result = safeLaunchExternalIntent(
            intent = intent,
            canResolve = { candidate -> candidate.resolveActivity(context.packageManager) != null },
            start = { candidate -> context.startActivity(candidate) },
            failureMessage = failureMessage,
        )
        if (result is ExternalLaunchResult.Failed) {
            logger.warn("Installer", result.message)
        }
        return result
    }

    /**
     * Drive a [PackageInstaller.Session] to install [apk]. The user will see the system
     * confirmation dialog — that is required on stock Android (we are not device-owner).
     *
     * Suspends until the system reports success/failure/cancel via the status receiver.
     *
     * @param firstInstall true if no prior version of [applicationId] is currently installed.
     *                     When true on Android 14+ we claim update-ownership so that no other
     *                     installer can silently overwrite our pinned APK. No-op on subsequent
     *                     updates (the platform only honors the claim on first install).
     * @param referrerUri  the upstream URL the APK was downloaded from. Surfaces in the system
     *                     "App info → Installed from" UI for forensics.
     */
    suspend fun installApk(
        apk: File,
        applicationId: String,
        firstInstall: Boolean = true,
        referrerUri: Uri? = null,
        unarchiveId: Int? = null,
        onSessionCreated: (Int) -> Unit = {},
        operationId: String? = null,
    ): InstallResult = suspendCancellableCoroutine { cont ->
        val installer = newInstallerHandle()
        val pi = installer.packageInstaller
        val params = buildSessionParams(
            firstInstall = firstInstall,
            referrerUri = referrerUri,
            applicationId = applicationId,
            silentInstall = installer.isSilent,
            unarchiveId = unarchiveId,
        )

        val sessionId = try {
            pi.createSession(params)
        } catch (t: Throwable) {
            logger.error("Installer", "createSession failed", t)
            cont.resume(InstallResult.Failure(t.message ?: "createSession failed"))
            return@suspendCancellableCoroutine
        }
        try {
            onSessionCreated(sessionId)
        } catch (t: Throwable) {
            runCatching { pi.abandonSession(sessionId) }
            cont.resume(InstallResult.Failure(t.message ?: "install state persistence failed"))
            return@suspendCancellableCoroutine
        }

        val registration = registerForegroundResult(
            sessionId = sessionId,
            applicationId = applicationId,
            route = InstallResultRoute.Foreground,
            operationId = operationId,
            cont = cont,
        )

        cont.invokeOnCancellation {
            ForegroundInstallResultRouter.detach(registration.capability)
            resultRegistry.cancel(registration)
            runCatching { pi.abandonSession(sessionId) }
        }

        try {
            streamAndCommit(installer, sessionId, apk, installResultIntent(context, registration))
        } catch (t: Throwable) {
            logger.error("Installer", "session commit failed", t)
            ForegroundInstallResultRouter.detach(registration.capability)
            resultRegistry.cancel(registration)
            runCatching { pi.abandonSession(sessionId) }
            if (cont.isActive) cont.resume(InstallResult.Failure(t.message ?: "session commit failed"))
        }
    }

    /**
     * Item 5 (API 34+): Create a session with the known [applicationId] and request
     * pre-approval from the user before the APK is downloaded.
     *
     * Shows the system pre-approval bottom sheet ("Allow [label] to be updated?"). If approved,
     * the same session should be committed via [commitSession] — the platform will not prompt
     * again. The pre-approval is valid for a limited time (order of minutes).
     *
     * Only meaningful for known updates (applicationId available from [AppIdCache]).
     * Falls back to [PreapprovalSessionResult.Declined] on any API error.
     */
    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    suspend fun createSessionAndRequestPreapproval(
        applicationId: String,
        label: String,
        referrerUri: Uri? = null,
        onSessionCreated: (Int) -> Unit = {},
        operationId: String? = null,
    ): PreapprovalSessionResult = suspendCancellableCoroutine { cont ->
        if (isSilentInstallActive()) {
            cont.resume(PreapprovalSessionResult.Declined)
            return@suspendCancellableCoroutine
        }
        val installer = newInstallerHandle()
        val pi = installer.packageInstaller
        // Pre-approval requires knowing the package name in advance.
        val params = buildSessionParams(
            firstInstall = false,
            referrerUri = referrerUri,
            applicationId = applicationId,
            silentInstall = installer.isSilent,
        )
        val sessionId = try {
            pi.createSession(params)
        } catch (t: Throwable) {
            logger.error("Installer", "createSession for preapproval failed", t)
            if (cont.isActive) cont.resume(PreapprovalSessionResult.Declined)
            return@suspendCancellableCoroutine
        }
        try {
            onSessionCreated(sessionId)
        } catch (t: Throwable) {
            runCatching { pi.abandonSession(sessionId) }
            if (cont.isActive) cont.resume(PreapprovalSessionResult.Declined)
            return@suspendCancellableCoroutine
        }

        val registration = resultRegistry.register(
            sessionId = sessionId,
            applicationId = applicationId,
            route = InstallResultRoute.Preapproval,
            operationId = operationId,
        )
        ForegroundInstallResultRouter.attach(registration.capability) { _, resultIntent ->
            ForegroundInstallResultRouter.detach(registration.capability)
            val status = resultIntent.getIntExtra(PackageInstaller.EXTRA_STATUS, STATUS_UNKNOWN)
            if (cont.isActive) {
                if (status == PackageInstaller.STATUS_SUCCESS) {
                    cont.resume(PreapprovalSessionResult.Approved(sessionId))
                } else {
                    runCatching { pi.abandonSession(sessionId) }
                    cont.resume(PreapprovalSessionResult.Declined)
                }
            }
        }
        cont.invokeOnCancellation {
            ForegroundInstallResultRouter.detach(registration.capability)
            resultRegistry.cancel(registration)
            runCatching { pi.abandonSession(sessionId) }
        }

        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        val pending = PendingIntent.getBroadcast(
            context,
            sessionId,
            installResultIntent(context, registration),
            flags,
        )

        try {
            val details = PackageInstaller.PreapprovalDetails.Builder()
                .setPackageName(applicationId)
                .setLabel(label)
                .setLocale(ULocale.getDefault())
                .build()
            openSession(installer, sessionId).use { session ->
                session.requestUserPreapproval(details, pending.intentSender)
            }
        } catch (t: Throwable) {
            logger.warn("Installer", "requestUserPreapproval unavailable: ${t.message}")
            ForegroundInstallResultRouter.detach(registration.capability)
            resultRegistry.cancel(registration)
            runCatching { pi.abandonSession(sessionId) }
            if (cont.isActive) cont.resume(PreapprovalSessionResult.Declined)
        }
    }

    /**
     * Stream [apk] into an existing session (previously created and pre-approved via
     * [createSessionAndRequestPreapproval]) and commit it. The platform will not show a
     * confirmation dialog again since the session was already pre-approved.
     */
    suspend fun commitSession(
        sessionId: Int,
        applicationId: String,
        apk: File,
        operationId: String? = null,
    ): InstallResult =
        suspendCancellableCoroutine { cont ->
            val installer = findSessionHandle(sessionId)
            if (installer == null) {
                cont.resume(InstallResult.Failure("Could not reopen install session $sessionId"))
                return@suspendCancellableCoroutine
            }
            val pi = installer.packageInstaller
            val registration = registerForegroundResult(
                sessionId = sessionId,
                applicationId = applicationId,
                route = InstallResultRoute.Foreground,
                operationId = operationId,
                cont = cont,
            )

            cont.invokeOnCancellation {
                ForegroundInstallResultRouter.detach(registration.capability)
                resultRegistry.cancel(registration)
                runCatching { pi.abandonSession(sessionId) }
            }

            try {
                streamAndCommit(installer, sessionId, apk, installResultIntent(context, registration))
            } catch (t: Throwable) {
                logger.error("Installer", "commitSession failed", t)
                ForegroundInstallResultRouter.detach(registration.capability)
                resultRegistry.cancel(registration)
                runCatching { pi.abandonSession(sessionId) }
                if (cont.isActive) cont.resume(InstallResult.Failure(t.message ?: "commitSession failed"))
            }
        }

    /** Abandon an open session — call on download failure or cancellation. */
    fun abandonSession(sessionId: Int) {
        resultRegistry.cancelSession(sessionId)
        abandonWithAllInstallers(sessionId)
    }

    fun hasOpenSession(sessionId: Int): Boolean =
        handlesForSession().any { handle ->
            runCatching {
                handle.packageInstaller.mySessions.any { it.sessionId == sessionId }
            }.getOrDefault(false)
        }

    /**
     * Android 14+: stage [apk], then ask PackageInstaller to commit only after gentle
     * update constraints are met. The supplied [statusIntent] must target a manifest
     * BroadcastReceiver because the final status can arrive long after this call returns.
     */
    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    fun queueInstallAfterConstraints(
        apk: File,
        applicationId: String,
        firstInstall: Boolean,
        referrerUri: Uri?,
        resultData: Intent,
        operationId: String? = null,
        onSessionCreated: (Int) -> Unit = {},
    ): InstallResult {
        val installer = newInstallerHandle()
        val pi = installer.packageInstaller
        val params = buildSessionParams(
            firstInstall = firstInstall,
            referrerUri = referrerUri,
            applicationId = applicationId,
            silentInstall = installer.isSilent,
        )
        val sessionId = try {
            pi.createSession(params)
        } catch (t: Throwable) {
            logger.error("Installer", "createSession for constrained install failed", t)
            return InstallResult.Failure(t.message ?: "createSession failed")
        }
        try {
            onSessionCreated(sessionId)
        } catch (t: Throwable) {
            runCatching { pi.abandonSession(sessionId) }
            return InstallResult.Failure(t.message ?: "install state persistence failed")
        }

        val registration = try {
            resultRegistry.register(
                sessionId = sessionId,
                applicationId = applicationId,
                route = InstallResultRoute.Queued,
                operationId = operationId,
            )
        } catch (t: Throwable) {
            runCatching { pi.abandonSession(sessionId) }
            return InstallResult.Failure(t.message ?: "result capability persistence failed")
        }

        return try {
            streamAndCommitAfterConstraints(
                installer,
                sessionId,
                apk,
                installResultIntent(context, registration, resultData),
            )
            InstallResult.Queued(sessionId)
        } catch (t: Throwable) {
            logger.error("Installer", "constrained install queue failed", t)
            resultRegistry.cancel(registration)
            runCatching { pi.abandonSession(sessionId) }
            InstallResult.Failure(t.message ?: "constrained install queue failed")
        }
    }

    /**
     * Stage a queued install without Android 14+ gentle constraints. The manifest receiver owns
     * the result, so API 26–33 workers never attempt to launch a confirmation Activity from a
     * background context when PackageInstaller requests user action.
     */
    fun queueInstallWithoutConstraints(
        apk: File,
        applicationId: String,
        firstInstall: Boolean,
        referrerUri: Uri?,
        resultData: Intent,
        operationId: String? = null,
        onSessionCreated: (Int) -> Unit = {},
    ): InstallResult {
        val installer = newInstallerHandle()
        val pi = installer.packageInstaller
        val params = buildSessionParams(
            firstInstall = firstInstall,
            referrerUri = referrerUri,
            applicationId = applicationId,
            silentInstall = installer.isSilent,
        )
        val sessionId = try {
            pi.createSession(params)
        } catch (t: Throwable) {
            logger.error("Installer", "createSession for queued install failed", t)
            return InstallResult.Failure(t.message ?: "createSession failed")
        }
        try {
            onSessionCreated(sessionId)
        } catch (t: Throwable) {
            runCatching { pi.abandonSession(sessionId) }
            return InstallResult.Failure(t.message ?: "install state persistence failed")
        }

        val registration = try {
            resultRegistry.register(
                sessionId = sessionId,
                applicationId = applicationId,
                route = InstallResultRoute.Queued,
                operationId = operationId,
            )
        } catch (t: Throwable) {
            runCatching { pi.abandonSession(sessionId) }
            return InstallResult.Failure(t.message ?: "result capability persistence failed")
        }

        return try {
            streamAndCommit(
                installer,
                sessionId,
                apk,
                installResultIntent(context, registration, resultData),
            )
            InstallResult.Queued(sessionId)
        } catch (t: Throwable) {
            logger.error("Installer", "queued install commit failed", t)
            resultRegistry.cancel(registration)
            runCatching { pi.abandonSession(sessionId) }
            InstallResult.Failure(t.message ?: "queued install commit failed")
        }
    }

    // ── Shared helpers ────────────────────────────────────────────────────────

    private fun archiveOperationPendingIntent(applicationId: String, action: String): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            applicationId.hashCode() xor action.hashCode(),
            Intent(context, ArchiveOperationReceiver::class.java)
                .setAction(action)
                .setPackage(context.packageName)
                .putExtra(PackageInstaller.EXTRA_PACKAGE_NAME, applicationId),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
        )

    private fun newInstallerHandle(): InstallerHandle {
        if (shizukuSilentInstallEnabled()) {
            shizukuInstaller.createHandle()?.let { handle ->
                return InstallerHandle(
                    packageInstaller = handle.packageInstaller,
                    shizuku = handle,
                )
            }
        }
        return InstallerHandle(context.packageManager.packageInstaller)
    }

    /**
     * A queued session may belong to either the app installer or Android shell after a process
     * restart. Try both identities so a temporary Shizuku outage does not make recovery forget a
     * durable session.
     */
    private fun handlesForSession(): List<InstallerHandle> = buildList {
        shizukuInstaller.createHandle()?.let { handle ->
            add(InstallerHandle(handle.packageInstaller, handle))
        }
        add(InstallerHandle(context.packageManager.packageInstaller))
    }

    private fun findSessionHandle(sessionId: Int): InstallerHandle? = handlesForSession()
        .firstOrNull { handle ->
            runCatching {
                openSession(handle, sessionId).use { }
            }.isSuccess
        }

    private fun openSession(handle: InstallerHandle, sessionId: Int): PackageInstaller.Session =
        handle.shizuku?.let { shizukuInstaller.openSession(it, sessionId) }
            ?: handle.packageInstaller.openSession(sessionId)

    private fun abandonWithAllInstallers(sessionId: Int) {
        handlesForSession().forEach { handle ->
            runCatching { handle.packageInstaller.abandonSession(sessionId) }
        }
    }

    private fun buildSessionParams(
        firstInstall: Boolean,
        referrerUri: Uri?,
        applicationId: String? = null,
        silentInstall: Boolean = false,
        unarchiveId: Int? = null,
    ): PackageInstaller.SessionParams {
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        params.setAppPackageName(applicationId)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            params.setRequireUserAction(
                if (silentInstall) {
                    PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED
                } else {
                    PackageInstaller.SessionParams.USER_ACTION_REQUIRED
                },
            )
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE && !silentInstall) {
            params.setInstallerPackageName(context.packageName)
        }
        params.setOriginatingUid(Process.myUid())
        if (referrerUri != null) params.setReferrerUri(referrerUri)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            params.setPackageSource(PackageInstaller.PACKAGE_SOURCE_STORE)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM && unarchiveId != null) {
            params.setUnarchiveId(unarchiveId)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE && firstInstall && !silentInstall) {
            params.setRequestUpdateOwnership(true)
        }
        return params
    }

    private fun registerForegroundResult(
        sessionId: Int,
        applicationId: String,
        route: InstallResultRoute,
        operationId: String?,
        cont: kotlinx.coroutines.CancellableContinuation<InstallResult>,
    ): InstallResultRegistration {
        val registration = resultRegistry.register(
            sessionId = sessionId,
            applicationId = applicationId,
            route = route,
            operationId = operationId,
        )
        ForegroundInstallResultRouter.attach(registration.capability) { ctx, intent ->
            val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -999)
            val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: ""
            when (status) {
                PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                    val confirm = intent.pendingUserActionIntent()
                    if (confirm != null) {
                        confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        val result = safeLaunchExternalIntent(
                            intent = confirm,
                            canResolve = { candidate ->
                                candidate.resolveActivity(ctx.packageManager) != null
                            },
                            start = { candidate -> ctx.startActivity(candidate) },
                            failureMessage = "Could not open the Android install confirmation.",
                        )
                        if (result is ExternalLaunchResult.Failed) {
                            logger.warn("Installer", result.message)
                        }
                    }
                }
                PackageInstaller.STATUS_SUCCESS -> {
                    ForegroundInstallResultRouter.detach(registration.capability)
                    if (cont.isActive) {
                        if (intent.getBooleanExtra(EXTRA_AUDIT_PENDING, false)) {
                            cont.resume(
                                InstallResult.Failure(
                                    message = "Android completed the install, but LocalAndroidStore " +
                                        "could not write durable audit evidence. The operation remains " +
                                        "pending recovery.",
                                    auditPending = true,
                                )
                            )
                        } else {
                            cont.resume(InstallResult.Success)
                        }
                    }
                }
                PackageInstaller.STATUS_FAILURE,
                PackageInstaller.STATUS_FAILURE_ABORTED,
                PackageInstaller.STATUS_FAILURE_BLOCKED,
                PackageInstaller.STATUS_FAILURE_CONFLICT,
                PackageInstaller.STATUS_FAILURE_INCOMPATIBLE,
                PackageInstaller.STATUS_FAILURE_INVALID,
                PackageInstaller.STATUS_FAILURE_STORAGE,
                PackageInstaller.STATUS_FAILURE_TIMEOUT -> {
                    ForegroundInstallResultRouter.detach(registration.capability)
                    if (cont.isActive) cont.resume(
                        InstallResult.Failure(
                            message = decodeFailure(ctx, status, message),
                            status = status,
                        )
                    )
                }
            }
        }
        return registration
    }

    private fun streamAndCommit(
        installer: InstallerHandle,
        sessionId: Int,
        apk: File,
        statusIntent: Intent,
    ) {
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        val pending: PendingIntent = PendingIntent.getBroadcast(context, sessionId, statusIntent, flags)
        val sender: IntentSender = pending.intentSender
        openSession(installer, sessionId).use { session ->
            writeArtifact(session, apk)
            session.commit(sender)
        }
    }

    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    private fun streamAndCommitAfterConstraints(
        installer: InstallerHandle,
        sessionId: Int,
        apk: File,
        statusIntent: Intent,
    ) {
        val pi = installer.packageInstaller
        openSession(installer, sessionId).use { session ->
            writeArtifact(session, apk)
        }
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        val pending = PendingIntent.getBroadcast(context, sessionId, statusIntent, flags)
        val constraints = PackageInstaller.InstallConstraints.Builder()
            .setAppNotForegroundRequired()
            .setDeviceIdleRequired()
            .setNotInCallRequired()
            .build()
        pi.commitSessionAfterInstallConstraintsAreMet(
            sessionId,
            pending.intentSender,
            constraints,
            CONSTRAINT_TIMEOUT_MILLIS,
        )
    }

    /** Stream a monolithic APK or every selected split from a private staging directory. */
    private fun writeArtifact(
        session: PackageInstaller.Session,
        artifact: File,
    ) {
        val files = if (artifact.isDirectory) {
            artifact.listFiles()
                .orEmpty()
                .filter { file -> file.isFile && file.name.endsWith(".apk", ignoreCase = true) }
                .sortedWith(
                    compareBy<File> { file ->
                        if (file.name.equals("base.apk", ignoreCase = true)) 0 else 1
                    }.thenBy { file -> file.name },
                )
        } else {
            listOf(artifact)
        }
        if (files.isEmpty()) throw java.io.IOException("No APKs were prepared for installation.")
        files.forEach { file ->
            file.inputStream().use { input ->
                val sessionName = if (files.size == 1) "base.apk" else file.name
                session.openWrite(sessionName, 0, file.length()).use { out ->
                    input.copyTo(out)
                    session.fsync(out)
                }
            }
        }
    }

    private companion object {
        val CONSTRAINT_TIMEOUT_MILLIS: Long = TimeUnit.HOURS.toMillis(24)
        const val STATUS_UNKNOWN = -999
        const val SHIZUKU_MANAGER_PACKAGE = "moe.shizuku.privileged.api"
    }

}

internal fun appLanguageSettingsIntent(applicationId: String): Intent? {
    if (!PACKAGE_NAME_PATTERN.matches(applicationId)) return null
    return Intent(Settings.ACTION_APP_LOCALE_SETTINGS)
        .setData(Uri.fromParts("package", applicationId, null))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
}

internal fun advancedSideloadingIntent(): Intent = Intent(
    Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS,
).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

sealed interface InstallResult {
    data object Success : InstallResult
    data class Queued(val sessionId: Int) : InstallResult
    data class Failure(
        val message: String,
        val status: Int? = null,
        val auditPending: Boolean = false,
    ) : InstallResult
}

sealed interface ArchiveRequestResult {
    data object Requested : ArchiveRequestResult
    data class Failed(val message: String) : ArchiveRequestResult
}

sealed interface PreapprovalSessionResult {
    /** Pre-approval granted; use [sessionId] with [PackageInstallerService.commitSession]. */
    data class Approved(val sessionId: Int) : PreapprovalSessionResult
    /** User declined or API not available; caller should reset card to UpdateAvailable. */
    data object Declined : PreapprovalSessionResult
}

/**
 * Translate a [PackageInstaller] EXTRA_STATUS code + EXTRA_STATUS_MESSAGE into a single
 * user-facing string. Replaces Android's generic "App not installed" with concrete causes.
 * Includes device ABI and free-storage context for actionable failure messages (Item 7).
 */
internal fun decodeFailure(context: Context, status: Int, systemMessage: String): String {
    val cause = when (status) {
        PackageInstaller.STATUS_FAILURE_ABORTED ->
            "Install cancelled."
        PackageInstaller.STATUS_FAILURE_BLOCKED ->
            "Install blocked by the system. The device may be in a restricted state " +
                "(work profile, parental controls, or kiosk mode)."
        PackageInstaller.STATUS_FAILURE_CONFLICT ->
            "A different version of this app is already installed and the signatures don't " +
                "match. Uninstall the existing copy first."
        PackageInstaller.STATUS_FAILURE_INCOMPATIBLE -> {
            val abi = android.os.Build.SUPPORTED_ABIS.firstOrNull() ?: "unknown"
            val sdk = android.os.Build.VERSION.SDK_INT
            "This APK isn't compatible with your device (ABI: $abi, SDK: $sdk). " +
                "It may require a different CPU architecture or a newer Android version."
        }
        PackageInstaller.STATUS_FAILURE_INVALID ->
            "The APK file is corrupt, unsigned, or its signing certificate doesn't match " +
                "the installed copy."
        PackageInstaller.STATUS_FAILURE_STORAGE -> {
            val freeMb = android.os.StatFs(android.os.Environment.getDataDirectory().path)
                .availableBytes / (1024 * 1024)
            "Not enough storage to install. Free up space and try again (available: ${freeMb} MB)."
        }
        PackageInstaller.STATUS_FAILURE_TIMEOUT ->
            "Install timed out waiting for the device to become idle, not in-call, and for the target app to leave the foreground."
        else ->
            "Install failed."
    }
    return if (systemMessage.isBlank()) cause else "$cause ($systemMessage)"
}
