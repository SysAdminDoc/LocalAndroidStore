package com.sysadmin.lasstore.install

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
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
import kotlin.coroutines.resume

class PackageInstallerService(
    private val context: Context,
    private val logger: Logger,
) {
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
        firstInstall: Boolean = true,
        referrerUri: Uri? = null,
    ): InstallResult = suspendCancellableCoroutine { cont ->
        val pi = context.packageManager.packageInstaller
        val params = buildSessionParams(firstInstall = firstInstall, referrerUri = referrerUri)

        val sessionId = try {
            pi.createSession(params)
        } catch (t: Throwable) {
            logger.error("Installer", "createSession failed", t)
            cont.resume(InstallResult.Failure(t.message ?: "createSession failed"))
            return@suspendCancellableCoroutine
        }

        val token = "lasstore_$sessionId"
        val receiver = installStatusReceiver(pi, sessionId, token, cont)
        registerReceiver(receiver, token)

        cont.invokeOnCancellation {
            runCatching { context.unregisterReceiver(receiver) }
            runCatching { pi.abandonSession(sessionId) }
        }

        try {
            streamAndCommit(pi, sessionId, apk, token)
        } catch (t: Throwable) {
            logger.error("Installer", "session commit failed", t)
            runCatching { context.unregisterReceiver(receiver) }
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

        val token = "lasstore_preapproval_$sessionId"
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -999)
                runCatching { ctx.unregisterReceiver(this) }
                if (cont.isActive) {
                    if (status == PackageInstaller.STATUS_SUCCESS) {
                        cont.resume(PreapprovalSessionResult.Approved(sessionId))
                    } else {
                        runCatching { pi.abandonSession(sessionId) }
                        cont.resume(PreapprovalSessionResult.Declined)
                    }
                }
            }
        }
        registerReceiver(receiver, token)
        cont.invokeOnCancellation {
            runCatching { context.unregisterReceiver(receiver) }
            runCatching { pi.abandonSession(sessionId) }
        }

        val statusIntent = Intent(token).setPackage(context.packageName)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        val pending = PendingIntent.getBroadcast(context, sessionId, statusIntent, flags)

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
            runCatching { context.unregisterReceiver(receiver) }
            runCatching { pi.abandonSession(sessionId) }
            if (cont.isActive) cont.resume(PreapprovalSessionResult.Declined)
        }
    }

    /**
     * Stream [apk] into an existing session (previously created and pre-approved via
     * [createSessionAndRequestPreapproval]) and commit it. The platform will not show a
     * confirmation dialog again since the session was already pre-approved.
     */
    suspend fun commitSession(sessionId: Int, apk: File): InstallResult =
        suspendCancellableCoroutine { cont ->
            val pi = context.packageManager.packageInstaller
            val token = "lasstore_$sessionId"
            val receiver = installStatusReceiver(pi, sessionId, token, cont)
            registerReceiver(receiver, token)

            cont.invokeOnCancellation {
                runCatching { context.unregisterReceiver(receiver) }
                runCatching { pi.abandonSession(sessionId) }
            }

            try {
                streamAndCommit(pi, sessionId, apk, token)
            } catch (t: Throwable) {
                logger.error("Installer", "commitSession failed", t)
                runCatching { context.unregisterReceiver(receiver) }
                runCatching { pi.abandonSession(sessionId) }
                if (cont.isActive) cont.resume(InstallResult.Failure(t.message ?: "commitSession failed"))
            }
        }

    /** Abandon an open session — call on download failure or cancellation. */
    fun abandonSession(sessionId: Int) {
        runCatching { context.packageManager.packageInstaller.abandonSession(sessionId) }
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

    private fun registerReceiver(receiver: BroadcastReceiver, token: String) {
        val filter = IntentFilter(token)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            context.registerReceiver(receiver, filter)
        }
    }

    private fun installStatusReceiver(
        pi: PackageInstaller,
        sessionId: Int,
        token: String,
        cont: kotlinx.coroutines.CancellableContinuation<InstallResult>,
    ) = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -999)
            val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: ""
            when (status) {
                PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                    val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                    if (confirm != null) {
                        confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        ctx.startActivity(confirm)
                    }
                }
                PackageInstaller.STATUS_SUCCESS -> {
                    runCatching { ctx.unregisterReceiver(this) }
                    if (cont.isActive) cont.resume(InstallResult.Success)
                }
                PackageInstaller.STATUS_FAILURE,
                PackageInstaller.STATUS_FAILURE_ABORTED,
                PackageInstaller.STATUS_FAILURE_BLOCKED,
                PackageInstaller.STATUS_FAILURE_CONFLICT,
                PackageInstaller.STATUS_FAILURE_INCOMPATIBLE,
                PackageInstaller.STATUS_FAILURE_INVALID,
                PackageInstaller.STATUS_FAILURE_STORAGE -> {
                    runCatching { ctx.unregisterReceiver(this) }
                    if (cont.isActive) cont.resume(
                        InstallResult.Failure(decodeFailure(ctx, status, message))
                    )
                }
            }
        }
    }

    private fun streamAndCommit(
        pi: PackageInstaller,
        sessionId: Int,
        apk: File,
        token: String,
    ) {
        pi.openSession(sessionId).use { session ->
            apk.inputStream().use { input ->
                session.openWrite("base.apk", 0, apk.length()).use { out ->
                    input.copyTo(out)
                    session.fsync(out)
                }
            }
            val statusIntent = Intent(token).setPackage(context.packageName)
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

}

sealed interface InstallResult {
    data object Success : InstallResult
    data class Failure(val message: String) : InstallResult
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
private fun decodeFailure(context: Context, status: Int, systemMessage: String): String {
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
        else ->
            "Install failed."
    }
    return if (systemMessage.isBlank()) cause else "$cause ($systemMessage)"
}
