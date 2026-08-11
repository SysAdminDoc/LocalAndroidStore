package com.sysadmin.lasstore.ui.log

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.sysadmin.lasstore.R
import com.sysadmin.lasstore.data.InstallAuditLog
import com.sysadmin.lasstore.data.LogLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class AuditEntryPresentationTest {
    @Test
    fun publisherPinReplacementIsMarkedAsHighRiskWarning() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val entry = InstallAuditLog.Entry(
            ts = 1L,
            event = "publisher_pin_replaced",
            applicationId = "com.example.app",
            source = "owner/repo",
            tagName = "v2",
            previousCertSha256 = "old",
            installedCertSha256 = "new",
        )

        val presented = entry.asLogEntry(context)

        assertEquals(LogLevel.Warn, presented.level)
        assertTrue(presented.highRisk)
        assertTrue(
            presented.message.startsWith(context.getString(R.string.audit_publisher_pin_replaced)),
        )
    }

    @Test
    fun ordinaryInstallAuditEventsRemainInformational() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val entry = InstallAuditLog.Entry(
            ts = 1L,
            event = "install_ok",
            applicationId = "com.example.app",
            source = "owner/repo",
            tagName = "v2",
        )

        val presented = entry.asLogEntry(context)

        assertEquals(LogLevel.Info, presented.level)
        assertFalse(presented.highRisk)
    }
}
