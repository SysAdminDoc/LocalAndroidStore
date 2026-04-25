package com.sysadmin.lasstore.install

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.IntentSender
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.provider.Settings
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
     */
    suspend fun installApk(apk: File): InstallResult = suspendCancellableCoroutine { cont ->
        val pi = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        params.setAppPackageName(null)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_REQUIRED)
        }

        val sessionId = try {
            pi.createSession(params)
        } catch (t: Throwable) {
            logger.error("Installer", "createSession failed", t)
            cont.resume(InstallResult.Failure(t.message ?: "createSession failed"))
            return@suspendCancellableCoroutine
        }

        val token = "lasstore_$sessionId"
        val receiver = object : BroadcastReceiver() {
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
                            InstallResult.Failure("status=$status $message".trim())
                        )
                    }
                }
            }
        }
        val filter = IntentFilter(token)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            context.registerReceiver(receiver, filter)
        }

        cont.invokeOnCancellation {
            runCatching { context.unregisterReceiver(receiver) }
            runCatching { pi.abandonSession(sessionId) }
        }

        try {
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
                val pending: PendingIntent = PendingIntent.getBroadcast(
                    context, sessionId, statusIntent, flags
                )
                val sender: IntentSender = pending.intentSender
                session.commit(sender)
            }
        } catch (t: Throwable) {
            logger.error("Installer", "session commit failed", t)
            runCatching { context.unregisterReceiver(receiver) }
            runCatching { pi.abandonSession(sessionId) }
            if (cont.isActive) cont.resume(InstallResult.Failure(t.message ?: "session commit failed"))
        }
    }
}

sealed interface InstallResult {
    data object Success : InstallResult
    data class Failure(val message: String) : InstallResult
}
