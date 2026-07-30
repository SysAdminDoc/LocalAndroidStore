package com.sysadmin.lasstore.install

import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.PermissionInfo
import android.os.Build
import com.sysadmin.lasstore.data.ApkMetadata

object PermissionDiff {
    /**
     * Returns dangerous permissions the APK adds vs the installed package.
     * Empty means either there is no installed package or no new dangerous permission.
     */
    fun newDangerousPermissions(context: Context, meta: ApkMetadata): List<String> {
        val pm = context.packageManager
        val installedPerms = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getPackageInfo(
                    meta.applicationId,
                    PackageManager.PackageInfoFlags.of(PackageManager.GET_PERMISSIONS.toLong()),
                )
            } else {
                @Suppress("DEPRECATION")
                pm.getPackageInfo(meta.applicationId, PackageManager.GET_PERMISSIONS)
            }
        }.getOrNull()?.requestedPermissions?.toSet() ?: return emptyList()

        return meta.requestedPermissions.filter { permission ->
            permission !in installedPerms && isDangerous(context, permission)
        }
    }

    private fun isDangerous(context: Context, permission: String): Boolean = runCatching {
        val info = context.packageManager.getPermissionInfo(permission, 0)
        (info.protectionLevel and PermissionInfo.PROTECTION_MASK_BASE) ==
            PermissionInfo.PROTECTION_DANGEROUS
    }.getOrDefault(false)
}
