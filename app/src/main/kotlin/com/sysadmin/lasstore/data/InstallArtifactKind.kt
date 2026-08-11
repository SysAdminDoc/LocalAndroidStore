package com.sysadmin.lasstore.data

/** File formats that a configured source may publish as an installable release artifact. */
enum class InstallArtifactKind {
    APK,
    ZIP_APK_SET,
    AAB,
    UNSUPPORTED,
}

internal fun installArtifactKind(name: String): InstallArtifactKind {
    return when (name.substringAfterLast('.', missingDelimiterValue = "").lowercase()) {
        "apk" -> InstallArtifactKind.APK
        "apks", "apkset", "xapk", "apkm" -> InstallArtifactKind.ZIP_APK_SET
        "aab" -> InstallArtifactKind.AAB
        else -> InstallArtifactKind.UNSUPPORTED
    }
}

internal fun isInstallableArtifactName(name: String): Boolean =
    installArtifactKind(name) != InstallArtifactKind.UNSUPPORTED
