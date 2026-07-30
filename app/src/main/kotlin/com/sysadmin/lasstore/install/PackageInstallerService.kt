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

class PackageInstallerService(
    private val context: Context,
    private val logger: Logger,
) {
    private val resultRegistry = InstallResultRegistry(context)

    /**
     * Whether the current app is allowed to drive the system installer dialog.
     * On Android 8.0+ this is the per-app "Install unknown apps" toggle the user must enable.
     */
    fun canRequestInstalls(): Boolean = context.packageManager.canRequestPackageInstalls()

    /** Open the system Settings page where the user grants "Install unknown apps". */
    fun openInstallPermissionSettings() {
        val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES)
            .setData(Uri.parse("package:${context.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    /** Open the system app-info screen so the user can hit Uninstall. */
    fun openAppInfo(applicationId: String) {
        val intent = Intent(Intent.ACTION_DELETE)
            .setData(Uri.parse("package:$applicationId"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    /** Launch the installed app's main activity. */
    fun launch(applicationId: String): Boolean {
        val intent = context.packageManager.getLaunchIntentForPackage(applicationId)
            ?: return false
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        return true
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
        onSessionCreated: (Int) -> Unit = {},
    ): InstallResult = suspendCancellableCoroutine { cont ->
        val pi = context.packageManager.packageInstaller
        val params = buildSessionParams(
            firstInstall = firstInstall,
            referrerUri = referrerUri,
            applicationId = applicationId,
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
            pi = pi,
            cont = cont,
        )

        cont.invokeOnCancellation {
            ForegroundInstallResultRouter.detach(registration.capability)
            resultRegistry.cancel(registration)
            runCatching { pi.abandonSession(sessionId) }
        }

        try {
            streamAndCommit(pi, sessionId, apk, installResultIntent(context, registration))
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
    ): PreapprovalSessionResult = suspendCancellableCoroutine { cont ->
        val pi = context.packageManager.packageInstaller
        // Pre-approval requires knowing the package name in advance.
        val params = buildSessionParams(
            firstInstall = false,
            referrerUri = referrerUri,
            applicationId = applicationId,
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
            pi.openSession(sessionId).use { session ->
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
    ): InstallResult =
        suspendCancellableCoroutine { cont ->
            val pi = context.packageManager.packageInstaller
            val registration = registerForegroundResult(
                sessionId = sessionId,
                applicationId = applicationId,
                route = InstallResultRoute.Foreground,
                pi = pi,
                cont = cont,
            )

            cont.invokeOnCancellation {
                ForegroundInstallResultRouter.detach(registration.capability)
                resultRegistry.cancel(registration)
                runCatching { pi.abandonSession(sessionId) }
            }

            try {
                streamAndCommit(pi, sessionId, apk, installResultIntent(context, registration))
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
        runCatching { context.packageManager.packageInstaller.abandonSession(sessionId) }
    }

    fun hasOpenSession(sessionId: Int): Boolean =
        context.packageManager.packageInstaller.mySessions.any { it.sessionId == sessionId }

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
    ): InstallResult {
        val pi = context.packageManager.packageInstaller
        val params = buildSessionParams(
            firstInstall = firstInstall,
            referrerUri = referrerUri,
            applicationId = applicationId,
        )
        val sessionId = try {
            pi.createSession(params)
        } catch (t: Throwable) {
            logger.error("Installer", "createSession for constrained install failed", t)
            return InstallResult.Failure(t.message ?: "createSession failed")
        }

        val registration = try {
            resultRegistry.register(
                sessionId = sessionId,
                applicationId = applicationId,
                route = InstallResultRoute.Queued,
            )
        } catch (t: Throwable) {
            runCatching { pi.abandonSession(sessionId) }
            return InstallResult.Failure(t.message ?: "result capability persistence failed")
        }

        return try {
            streamAndCommitAfterConstraints(
                pi,
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

    // ── Shared helpers ────────────────────────────────────────────────────────

    private fun buildSessionParams(
        firstInstall: Boolean,
        referrerUri: Uri?,
        applicationId: String? = null,
    ): PackageInstaller.SessionParams {
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        params.setAppPackageName(applicationId)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_REQUIRED)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            params.setInstallerPackageName(context.packageName)
        }
        params.setOriginatingUid(Process.myUid())
        if (referrerUri != null) params.setReferrerUri(referrerUri)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            params.setPackageSource(PackageInstaller.PACKAGE_SOURCE_STORE)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE && firstInstall) {
            params.setRequestUpdateOwnership(true)
        }
        return params
    }

    private fun registerForegroundResult(
        sessionId: Int,
        applicationId: String,
        route: InstallResultRoute,
        pi: PackageInstaller,
        cont: kotlinx.coroutines.CancellableContinuation<InstallResult>,
    ): InstallResultRegistration {
        val registration = resultRegistry.register(sessionId, applicationId, route)
        ForegroundInstallResultRouter.attach(registration.capability) { ctx, intent ->
            val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -999)
            val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: ""
            when (status) {
                PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                    val confirm = intent.pendingUserActionIntent()
                    if (confirm != null) {
                        confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        ctx.startActivity(confirm)
                    }
                }
                PackageInstaller.STATUS_SUCCESS -> {
                    ForegroundInstallResultRouter.detach(registration.capability)
                    if (cont.isActive) cont.resume(InstallResult.Success)
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
        pi: PackageInstaller,
        sessionId: Int,
        apk: File,
        statusIntent: Intent,
    ) {
        pi.openSession(sessionId).use { session ->
            apk.inputStream().use { input ->
                session.openWrite("base.apk", 0, apk.length()).use { out ->
                    input.copyTo(out)
                    session.fsync(out)
                }
            }
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
            } else {
                PendingIntent.FLAG_UPDATE_CURRENT
            }
            val pending: PendingIntent = PendingIntent.getBroadcast(context, sessionId, statusIntent, flags)
            val sender: IntentSender = pending.intentSender
            session.commit(sender)
        }
    }

    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    private fun streamAndCommitAfterConstraints(
        pi: PackageInstaller,
        sessionId: Int,
        apk: File,
        statusIntent: Intent,
    ) {
        pi.openSession(sessionId).use { session ->
            apk.inputStream().use { input ->
                session.openWrite("base.apk", 0, apk.length()).use { out ->
                    input.copyTo(out)
                    session.fsync(out)
                }
            }
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

    private companion object {
        val CONSTRAINT_TIMEOUT_MILLIS: Long = TimeUnit.HOURS.toMillis(24)
        const val STATUS_UNKNOWN = -999
    }

}

sealed interface InstallResult {
    data object Success : InstallResult
    data class Queued(val sessionId: Int) : InstallResult
    data class Failure(
        val message: String,
        val status: Int? = null,
    ) : InstallResult
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
