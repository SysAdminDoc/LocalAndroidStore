package com.sysadmin.lasstore.data

import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest
import java.util.zip.ZipFile

data class ApkDigestSet(
    val apkSha256: String,
    val manifestSha256: String?,
)

fun digestApkFile(apk: File): ApkDigestSet {
    val apkSha256 = digestFile(apk)
    val manifestSha256 = ZipFile(apk).use { zip ->
        zip.getEntry("AndroidManifest.xml")?.let { entry ->
            zip.getInputStream(entry).use(::digestStream)
        }
    }
    return ApkDigestSet(apkSha256 = apkSha256, manifestSha256 = manifestSha256)
}

private fun digestFile(file: File): String = FileInputStream(file).use(::digestStream)

private fun digestStream(stream: java.io.InputStream): String {
    val digest = MessageDigest.getInstance("SHA-256")
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    while (true) {
        val count = stream.read(buffer)
        if (count < 0) break
        digest.update(buffer, 0, count)
    }
    return digest.digest().joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
}
