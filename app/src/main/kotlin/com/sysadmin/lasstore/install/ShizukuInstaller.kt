package com.sysadmin.lasstore.install

import android.content.Context
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.os.Build
import android.os.IBinder
import android.os.IInterface
import android.os.Process
import com.sysadmin.lasstore.data.Logger
import org.lsposed.hiddenapibypass.HiddenApiBypass
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuBinderWrapper
import rikka.shizuku.SystemServiceHelper

/** The state of the optional Shizuku transport, independent of the user's opt-in. */
enum class ShizukuStatus {
    Unavailable,
    PermissionRequired,
    Ready,
}

/**
 * Reflection-only bridge around Android's hidden package-installer AIDL.
 *
 * The hidden interfaces are intentionally not compile-time dependencies. The
 * public PackageInstaller.Session API is reused after its hidden session
 * constructor is supplied with a Shizuku-forwarded session binder.
 */
class ShizukuInstaller(
    private val context: Context,
    private val logger: Logger,
) {
    val settings = ShizukuSilentInstallStore(context)

    fun status(): ShizukuStatus = try {
        if (!Shizuku.pingBinder()) {
            ShizukuStatus.Unavailable
        } else if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
            ShizukuStatus.PermissionRequired
        } else {
            ShizukuStatus.Ready
        }
    } catch (throwable: Throwable) {
        logger.warn("Shizuku", "Could not inspect Shizuku state: ${throwable.message}")
        ShizukuStatus.Unavailable
    }

    fun requestPermission(requestCode: Int = DEFAULT_PERMISSION_REQUEST_CODE): Boolean {
        return try {
            if (!Shizuku.pingBinder()) return false
            if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) return true
            Shizuku.requestPermission(requestCode)
            true
        } catch (throwable: Throwable) {
            logger.warn("Shizuku", "Could not request Shizuku access: ${throwable.message}")
            false
        }
    }

    fun createHandle(): Handle? {
        if (status() != ShizukuStatus.Ready) return null
        return runCatching {
            enableHiddenPackageApiAccess()
            val packageManager = asInterface(
                interfaceName = IPACKAGE_MANAGER,
                binder = ShizukuBinderWrapper(
                    SystemServiceHelper.getSystemService("package"),
                ),
            )
            val packageInstaller = call(
                owner = IPACKAGE_MANAGER,
                target = packageManager,
                methodName = "getPackageInstaller",
            ) ?: error("IPackageManager.getPackageInstaller returned null")
            val remotePackageInstaller = asInterface(
                interfaceName = IPACKAGE_INSTALLER,
                binder = ShizukuBinderWrapper(asBinder(packageInstaller)),
            )
            val publicPackageInstaller = createPublicPackageInstaller(remotePackageInstaller)
            Handle(
                packageInstaller = publicPackageInstaller,
                remotePackageInstaller = remotePackageInstaller,
            )
        }.onFailure { throwable ->
            logger.warn(
                "Shizuku",
                "Shizuku package installer is unavailable; using Android's installer: " +
                    (throwable.message ?: throwable.javaClass.simpleName),
            )
        }.getOrNull()
    }

    fun openSession(handle: Handle, sessionId: Int): PackageInstaller.Session {
        enableHiddenPackageApiAccess()
        val remoteSession = call(
            owner = IPACKAGE_INSTALLER,
            target = handle.remotePackageInstaller,
            methodName = "openSession",
            parameterTypes = arrayOf(Int::class.javaPrimitiveType!!),
            arguments = arrayOf(sessionId),
        ) ?: error("IPackageInstaller.openSession returned null")
        val session = asInterface(
            interfaceName = IPACKAGE_INSTALLER_SESSION,
            binder = ShizukuBinderWrapper(asBinder(remoteSession)),
        )
        val constructor = PackageInstaller.Session::class.java.getConstructor(
            Class.forName(IPACKAGE_INSTALLER_SESSION),
        )
        return constructor.newInstance(session)
    }

    data class Handle(
        val packageInstaller: PackageInstaller,
        internal val remotePackageInstaller: Any,
    )

    private fun createPublicPackageInstaller(remotePackageInstaller: Any): PackageInstaller {
        val installerPackageName = if (Shizuku.getUid() == Process.ROOT_UID) {
            context.packageName
        } else {
            // A shell-backed Shizuku session is owned by Android's shell package.
            "com.android.shell"
        }
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            PackageInstaller::class.java.getConstructor(
                Class.forName(IPACKAGE_INSTALLER),
                String::class.java,
                String::class.java,
                Int::class.javaPrimitiveType,
            ).newInstance(
                remotePackageInstaller,
                installerPackageName,
                context.attributionTag,
                shellOrCurrentUserId(),
            )
        } else {
            PackageInstaller::class.java.getConstructor(
                Class.forName(IPACKAGE_INSTALLER),
                String::class.java,
                Int::class.javaPrimitiveType,
            ).newInstance(
                remotePackageInstaller,
                installerPackageName,
                shellOrCurrentUserId(),
            )
        }
    }

    private fun shellOrCurrentUserId(): Int = if (Shizuku.getUid() == Process.ROOT_UID) {
        Process.myUserHandle().hashCode()
    } else {
        0
    }

    private fun enableHiddenPackageApiAccess() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            HiddenApiBypass.addHiddenApiExemptions("Landroid/content/pm")
        }
    }

    private fun asBinder(value: Any): IBinder = when (value) {
        is IBinder -> value
        is IInterface -> value.asBinder()
        else -> error("${value.javaClass.name} is not a Binder interface")
    }

    private fun asInterface(interfaceName: String, binder: IBinder): Any {
        val stub = Class.forName("$interfaceName\$Stub")
        return stub.getMethod("asInterface", IBinder::class.java)
            .invoke(null, binder)
            ?: error("$interfaceName.Stub.asInterface returned null")
    }

    private fun call(
        owner: String,
        target: Any,
        methodName: String,
        parameterTypes: Array<Class<*>> = emptyArray(),
        arguments: Array<Any?> = emptyArray(),
    ): Any? {
        val method = Class.forName(owner).getMethod(methodName, *parameterTypes)
        return method.invoke(target, *arguments)
    }

    private companion object {
        const val IPACKAGE_MANAGER = "android.content.pm.IPackageManager"
        const val IPACKAGE_INSTALLER = "android.content.pm.IPackageInstaller"
        const val IPACKAGE_INSTALLER_SESSION = "android.content.pm.IPackageInstallerSession"
        const val DEFAULT_PERMISSION_REQUEST_CODE = 0x4c41
    }
}
