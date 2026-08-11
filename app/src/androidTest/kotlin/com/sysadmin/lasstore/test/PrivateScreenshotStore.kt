package com.sysadmin.lasstore.test

import android.content.Context
import android.graphics.Bitmap
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File

/**
 * Keeps visual-test output inside the test app's cache. CI can opt in to a separate artifact
 * copy with the instrumentation argument `screenshot_artifact_dir`; no public MediaStore write
 * is ever performed.
 */
internal class PrivateScreenshotStore(context: Context) {
    private val directory = File(context.cacheDir, DIRECTORY_NAME)
    private val artifactDirectory = runCatching {
        InstrumentationRegistry.getArguments()
            .getString(ARTIFACT_DIRECTORY_ARGUMENT)
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?.let(::File)
    }.getOrNull()

    fun save(name: String, bitmap: Bitmap): File {
        directory.mkdirs()
        val output = File(directory, File(name).name)
        output.outputStream().use { stream ->
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)) {
                "Could not encode screenshot ${output.name}"
            }
        }
        artifactDirectory?.let { directory ->
            directory.mkdirs()
            output.copyTo(File(directory, output.name), overwrite = true)
        }
        return output
    }

    fun cleanup() {
        directory.listFiles()?.forEach { file -> file.delete() }
        directory.delete()
    }

    private companion object {
        const val DIRECTORY_NAME = "instrumentation-screenshots"
        const val ARTIFACT_DIRECTORY_ARGUMENT = "screenshot_artifact_dir"
    }
}
