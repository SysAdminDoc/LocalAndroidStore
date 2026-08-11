package com.sysadmin.lasstore.wear

object WearProtocol {
    const val PHONE_CAPABILITY = "local_android_store_phone"
    const val UPDATE_COUNT_PATH = "/local-android-store/update-count"
    const val REQUEST_REFRESH_PATH = "/local-android-store/request-refresh"

    fun encodeUpdateCount(count: Int): ByteArray =
        count.coerceAtLeast(0).toString().toByteArray(Charsets.UTF_8)

    fun decodeUpdateCount(payload: ByteArray): Int? =
        payload.toString(Charsets.UTF_8).toIntOrNull()?.coerceAtLeast(0)
}
