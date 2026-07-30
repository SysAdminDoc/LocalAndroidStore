package com.sysadmin.lasstore.install

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageInstaller
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class InstallResultReceiverInstrumentedTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private var registration: InstallResultRegistration? = null

    @After
    fun cleanup() {
        registration?.let {
            ForegroundInstallResultRouter.detach(it.capability)
            InstallResultRegistry(context).cancel(it)
        }
    }

    @Test
    fun receiverIsExplicitlyNonExported() {
        val info = context.packageManager.getReceiverInfo(
            ComponentName(context, InstallResultReceiver::class.java),
            0,
        )

        assertNotNull(info)
        assertFalse(info.exported)
    }

    @Test
    fun matchingTerminalResultIsSingleUse() {
        val registry = InstallResultRegistry(context)
        val current = registry.register(
            sessionId = 42,
            applicationId = "com.example.app",
            route = InstallResultRoute.Foreground,
        )
        registration = current
        val deliveries = AtomicInteger()
        val delivered = CountDownLatch(1)
        ForegroundInstallResultRouter.attach(current.capability) { _, _ ->
            deliveries.incrementAndGet()
            delivered.countDown()
        }
        val result = installResultIntent(context, current)
            .putExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_SUCCESS)
            .putExtra(PackageInstaller.EXTRA_SESSION_ID, current.sessionId)
            .putExtra(PackageInstaller.EXTRA_PACKAGE_NAME, current.applicationId)

        context.sendBroadcast(result)
        assertEquals(true, delivered.await(5, TimeUnit.SECONDS))
        assertNull(registry.find(current.capability))

        context.sendBroadcast(result)
        Thread.sleep(250)
        assertEquals(1, deliveries.get())
    }

    @Test
    fun mismatchedSessionIsRejectedWithoutConsumingCapability() {
        val registry = InstallResultRegistry(context)
        val current = registry.register(
            sessionId = 73,
            applicationId = "com.example.app",
            route = InstallResultRoute.Foreground,
        )
        registration = current
        val deliveries = AtomicInteger()
        ForegroundInstallResultRouter.attach(current.capability) { _, _ ->
            deliveries.incrementAndGet()
        }
        val spoofed = installResultIntent(context, current)
            .putExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_SUCCESS)
            .putExtra(PackageInstaller.EXTRA_SESSION_ID, 74)
            .putExtra(PackageInstaller.EXTRA_PACKAGE_NAME, current.applicationId)

        context.sendBroadcast(spoofed)
        Thread.sleep(250)

        assertEquals(0, deliveries.get())
        assertEquals(current, registry.find(current.capability))
    }

    @Test
    fun cancellingSessionInvalidatesItsCapability() {
        val registry = InstallResultRegistry(context)
        val current = registry.register(
            sessionId = 91,
            applicationId = "com.example.app",
            route = InstallResultRoute.Queued,
        )
        registration = current

        registry.cancelSession(current.sessionId)

        assertNull(registry.find(current.capability))
    }
}
