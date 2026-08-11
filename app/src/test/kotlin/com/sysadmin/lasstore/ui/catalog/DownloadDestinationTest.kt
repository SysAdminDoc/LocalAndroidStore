package com.sysadmin.lasstore.ui.catalog

import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadDestinationTest {
    @Test
    fun repeatedLegacyDownloadsReserveDistinctFilesWithoutOverwritingTheFirst() {
        val directory = Files.createTempDirectory("las-download-destination").toFile()
        val first = reserveUniqueDownloadFile(directory, "Example_v1.apk")
        first.writeText("first")
        val second = reserveUniqueDownloadFile(directory, "Example_v1.apk")
        second.writeText("second")

        assertEquals("Example_v1.apk", first.name)
        assertEquals("Example_v1 (1).apk", second.name)
        assertEquals("first", first.readText())
        assertEquals("second", second.readText())
        assertTrue(first.exists())
        assertTrue(second.exists())
    }
}
