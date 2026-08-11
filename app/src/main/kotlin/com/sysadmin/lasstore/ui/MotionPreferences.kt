package com.sysadmin.lasstore.ui

import android.content.Context
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext

internal fun reducedMotionForAnimatorScale(scale: Float): Boolean = scale <= 0f

private fun animatorDurationScale(context: Context): Float = runCatching {
    Settings.Global.getFloat(
        context.contentResolver,
        Settings.Global.ANIMATOR_DURATION_SCALE,
        1f,
    )
}.getOrDefault(1f)

/** Reads Android's accessibility/developer animation preference and follows live changes. */
@Composable
internal fun rememberReducedMotion(): Boolean {
    val context = LocalContext.current
    var reducedMotion by remember(context) {
        mutableStateOf(reducedMotionForAnimatorScale(animatorDurationScale(context)))
    }

    DisposableEffect(context) {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                reducedMotion = reducedMotionForAnimatorScale(animatorDurationScale(context))
            }
        }
        val resolver = context.contentResolver
        resolver.registerContentObserver(
            Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE),
            false,
            observer,
        )
        onDispose { resolver.unregisterContentObserver(observer) }
    }

    return reducedMotion
}
