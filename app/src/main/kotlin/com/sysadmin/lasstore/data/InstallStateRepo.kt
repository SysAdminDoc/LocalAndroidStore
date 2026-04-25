package com.sysadmin.lasstore.data

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build

data class InstalledInfo(
    val applicationId: String,
    val versionName: String?,
    val versionCode: Long,
)

class InstallStateRepo(private val context: Context) {
    fun isInstalled(applicationId: String): Boolean = info(applicationId) != null

    fun info(applicationId: String): InstalledInfo? {
        val pm = context.packageManager
        return runCatching {
            val pkg = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getPackageInfo(applicationId, PackageManager.PackageInfoFlags.of(0L))
            } else {
                @Suppress("DEPRECATION") pm.getPackageInfo(applicationId, 0)
            }
            val vc: Long = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                pkg.longVersionCode
            } else {
                @Suppress("DEPRECATION") pkg.versionCode.toLong()
            }
            InstalledInfo(applicationId, pkg.versionName, vc)
        }.getOrNull()
    }
}
