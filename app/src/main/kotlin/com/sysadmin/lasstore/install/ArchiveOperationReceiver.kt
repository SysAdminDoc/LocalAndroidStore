package com.sysadmin.lasstore.install

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import com.sysadmin.lasstore.MainActivity
import com.sysadmin.lasstore.data.ServiceLocator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

const val ACTION_ARCHIVE_RESULT = "com.sysadmin.lasstore.action.ARCHIVE_RESULT"
const val ACTION_UNARCHIVE_RESULT = "com.sysadmin.lasstore.action.UNARCHIVE_RESULT"
const val ACTION_RESTORE_ARCHIVED = "com.sysadmin.lasstore.action.RESTORE_ARCHIVED"

private const val EXTRA_UNARCHIVE_ID = "android.content.pm.extra.UNARCHIVE_ID"
private const val EXTRA_UNARCHIVE_PACKAGE_NAME = "android.content.pm.extra.UNARCHIVE_PACKAGE_NAME"
private const val EXTRA_UNARCHIVE_STATUS = "android.content.pm.extra.UNARCHIVE_STATUS"
private const val UNARCHIVE_ACTION = "android.intent.action.UNARCHIVE_PACKAGE"
private const val EXTRA_RESTORE_PACKAGE = "com.sysadmin.lasstore.extra.RESTORE_PACKAGE"

/** Receives archive-operation callbacks and Android's launcher-triggered restore hand-off. */
class ArchiveOperationReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val appContext = context.applicationContext
        when (intent.action) {
            ACTION_ARCHIVE_RESULT,
            ACTION_UNARCHIVE_RESULT -> handleStatus(appContext, intent)
            UNARCHIVE_ACTION -> handleRestoreRequest(appContext, intent)
        }
    }

    private fun handleStatus(context: Context, intent: Intent) {
        runCatching {
            ServiceLocator.init(context)
            val packageName = intent.getStringExtra(PackageInstaller.EXTRA_PACKAGE_NAME).orEmpty()
            val archiveStatus = intent.getIntExtra(EXTRA_UNARCHIVE_STATUS, Int.MIN_VALUE)
            val installStatus = intent.getIntExtra(
                PackageInstaller.EXTRA_STATUS,
                Int.MIN_VALUE,
            )
            if (
                intent.action == ACTION_UNARCHIVE_RESULT &&
                archiveStatus != Int.MIN_VALUE &&
                archiveStatus != PackageInstaller.UNARCHIVAL_OK
            ) {
                ServiceLocator.archiveRestores.pending()?.let { pending ->
                    if (pending.packageName == packageName) {
                        ServiceLocator.archiveRestores.clearIf(packageName, pending.unarchiveId)
                    }
                }
            }
            ServiceLocator.logger.info(
                "Archive",
                "${intent.action} for $packageName completed with " +
                    "status=${if (archiveStatus != Int.MIN_VALUE) archiveStatus else installStatus}",
            )
        }.onFailure { throwable ->
            runCatching { ServiceLocator.logger.warn("Archive", throwable.message.orEmpty()) }
        }
    }

    private fun handleRestoreRequest(context: Context, intent: Intent) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.VANILLA_ICE_CREAM) return
        val pendingResult = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                ServiceLocator.init(context)
                val packageName = intent.getStringExtra(EXTRA_UNARCHIVE_PACKAGE_NAME)
                    ?: intent.getStringExtra(PackageInstaller.EXTRA_PACKAGE_NAME)
                val unarchiveId = intent.getIntExtra(EXTRA_UNARCHIVE_ID, -1)
                if (packageName.isNullOrBlank() || unarchiveId < 0) {
                    ServiceLocator.logger.warn("Archive", "Rejected malformed unarchive request")
                    if (unarchiveId >= 0) {
                        ServiceLocator.installer.reportUnarchivalStatus(
                            unarchiveId,
                            PackageInstaller.UNARCHIVAL_GENERIC_ERROR,
                        )
                    }
                    return@launch
                }
                ServiceLocator.archiveRestores.set(packageName, unarchiveId)
                val launch = Intent(context, MainActivity::class.java)
                    .setAction(ACTION_RESTORE_ARCHIVED)
                    .putExtra(EXTRA_RESTORE_PACKAGE, packageName)
                    .addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK or
                            Intent.FLAG_ACTIVITY_CLEAR_TOP or
                            Intent.FLAG_ACTIVITY_SINGLE_TOP,
                    )
                try {
                    context.startActivity(launch)
                    ServiceLocator.installer.reportUnarchivalStatus(
                        unarchiveId,
                        PackageInstaller.UNARCHIVAL_OK,
                    )
                } catch (throwable: Throwable) {
                    ServiceLocator.archiveRestores.clearIf(packageName, unarchiveId)
                    ServiceLocator.logger.error(
                        "Archive",
                        "Could not start the archived-app restore flow",
                        throwable,
                    )
                    ServiceLocator.installer.reportUnarchivalStatus(
                        unarchiveId,
                        PackageInstaller.UNARCHIVAL_GENERIC_ERROR,
                    )
                }
            } catch (throwable: Throwable) {
                ServiceLocator.logger.error("Archive", "Could not accept unarchive request", throwable)
            } finally {
                pendingResult.finish()
            }
        }
    }
}

const val EXTRA_RESTORE_ARCHIVED_PACKAGE = EXTRA_RESTORE_PACKAGE
