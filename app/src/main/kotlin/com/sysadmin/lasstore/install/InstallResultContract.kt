package com.sysadmin.lasstore.install

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import com.sysadmin.lasstore.data.Logger
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap

internal enum class InstallResultRoute {
    Foreground,
    Preapproval,
    Queued,
}

internal data class InstallResultRegistration(
    val capability: String,
    val sessionId: Int,
    val applicationId: String,
    val route: InstallResultRoute,
)

internal data class InstallResultEnvelope(
    val capability: String?,
    val declaredSessionId: Int?,
    val declaredApplicationId: String?,
    val platformSessionId: Int?,
    val platformApplicationId: String?,
    val status: Int,
) {
    val isTerminal: Boolean
        get() = status != PackageInstaller.STATUS_PENDING_USER_ACTION

    companion object {
        fun from(intent: Intent): InstallResultEnvelope = InstallResultEnvelope(
            capability = intent.getStringExtra(EXTRA_CAPABILITY),
            declaredSessionId = intent.intExtraOrNull(EXTRA_DECLARED_SESSION_ID),
            declaredApplicationId = intent.getStringExtra(EXTRA_DECLARED_APPLICATION_ID),
            platformSessionId = intent.intExtraOrNull(PackageInstaller.EXTRA_SESSION_ID),
            platformApplicationId = intent.getStringExtra(PackageInstaller.EXTRA_PACKAGE_NAME),
            status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, STATUS_MISSING),
        )
    }
}

internal sealed interface InstallResultValidation {
    data class Accepted(
        val registration: InstallResultRegistration,
        val terminal: Boolean,
    ) : InstallResultValidation

    data class Rejected(val reason: String) : InstallResultValidation
}

internal object InstallResultValidator {
    fun validate(
        registration: InstallResultRegistration?,
        envelope: InstallResultEnvelope,
    ): InstallResultValidation {
        if (registration == null) {
            return InstallResultValidation.Rejected("unknown or already-consumed capability")
        }
        if (envelope.capability != registration.capability) {
            return InstallResultValidation.Rejected("capability mismatch")
        }
        if (envelope.declaredSessionId != registration.sessionId) {
            return InstallResultValidation.Rejected("declared session mismatch")
        }
        if (envelope.platformSessionId != null &&
            envelope.platformSessionId != registration.sessionId
        ) {
            return InstallResultValidation.Rejected("platform session mismatch")
        }
        if (envelope.declaredApplicationId != registration.applicationId) {
            return InstallResultValidation.Rejected("declared package mismatch")
        }
        if (envelope.platformApplicationId != null &&
            envelope.platformApplicationId != registration.applicationId
        ) {
            return InstallResultValidation.Rejected("platform package mismatch")
        }
        if (envelope.status !in VALID_STATUSES) {
            return InstallResultValidation.Rejected("unknown status ${envelope.status}")
        }
        return InstallResultValidation.Accepted(
            registration = registration,
            terminal = envelope.isTerminal,
        )
    }

    private val VALID_STATUSES = setOf(
        PackageInstaller.STATUS_PENDING_USER_ACTION,
        PackageInstaller.STATUS_SUCCESS,
        PackageInstaller.STATUS_FAILURE,
        PackageInstaller.STATUS_FAILURE_ABORTED,
        PackageInstaller.STATUS_FAILURE_BLOCKED,
        PackageInstaller.STATUS_FAILURE_CONFLICT,
        PackageInstaller.STATUS_FAILURE_INCOMPATIBLE,
        PackageInstaller.STATUS_FAILURE_INVALID,
        PackageInstaller.STATUS_FAILURE_STORAGE,
        PackageInstaller.STATUS_FAILURE_TIMEOUT,
    )
}

internal class InstallResultRegistry(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun register(
        sessionId: Int,
        applicationId: String,
        route: InstallResultRoute,
    ): InstallResultRegistration {
        val registration = InstallResultRegistration(
            capability = newCapability(),
            sessionId = sessionId,
            applicationId = applicationId,
            route = route,
        )
        val persisted = prefs.edit()
            .putInt(key(registration.capability, FIELD_SESSION), sessionId)
            .putString(key(registration.capability, FIELD_PACKAGE), applicationId)
            .putString(key(registration.capability, FIELD_ROUTE), route.name)
            .commit()
        check(persisted) { "Could not persist install-result capability" }
        return registration
    }

    fun find(capability: String?): InstallResultRegistration? {
        if (capability == null || !CAPABILITY_PATTERN.matches(capability)) return null
        val sessionKey = key(capability, FIELD_SESSION)
        if (!prefs.contains(sessionKey)) return null
        val applicationId = prefs.getString(key(capability, FIELD_PACKAGE), null) ?: return null
        val routeName = prefs.getString(key(capability, FIELD_ROUTE), null) ?: return null
        val route = runCatching { InstallResultRoute.valueOf(routeName) }.getOrNull() ?: return null
        return InstallResultRegistration(
            capability = capability,
            sessionId = prefs.getInt(sessionKey, INVALID_SESSION_ID),
            applicationId = applicationId,
            route = route,
        )
    }

    fun consume(registration: InstallResultRegistration): Boolean = synchronized(REGISTRY_LOCK) {
        if (find(registration.capability) != registration) return@synchronized false
        remove(registration.capability)
    }

    fun cancel(registration: InstallResultRegistration) {
        synchronized(REGISTRY_LOCK) {
            if (find(registration.capability) == registration) {
                remove(registration.capability)
            }
        }
    }

    fun cancelSession(sessionId: Int) {
        synchronized(REGISTRY_LOCK) {
            prefs.all
                .filter { (key, value) ->
                    key.endsWith(".$FIELD_SESSION") && value == sessionId
                }
                .keys
                .map { it.removeSuffix(".$FIELD_SESSION") }
                .forEach(::remove)
        }
    }

    private fun remove(capability: String): Boolean = prefs.edit()
        .remove(key(capability, FIELD_SESSION))
        .remove(key(capability, FIELD_PACKAGE))
        .remove(key(capability, FIELD_ROUTE))
        .commit()

    private fun key(capability: String, field: String): String = "$capability.$field"

    private fun newCapability(): String {
        val bytes = ByteArray(CAPABILITY_BYTES)
        SECURE_RANDOM.nextBytes(bytes)
        return bytes.joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }
    }

    private companion object {
        const val PREFS_NAME = "install_result_capabilities"
        const val FIELD_SESSION = "session"
        const val FIELD_PACKAGE = "package"
        const val FIELD_ROUTE = "route"
        const val INVALID_SESSION_ID = -1
        const val CAPABILITY_BYTES = 32
        val CAPABILITY_PATTERN = Regex("[0-9a-f]{64}")
        val SECURE_RANDOM = SecureRandom()
        val REGISTRY_LOCK = Any()
    }
}

internal object ForegroundInstallResultRouter {
    private val handlers = ConcurrentHashMap<String, (Context, Intent) -> Unit>()

    fun attach(capability: String, handler: (Context, Intent) -> Unit) {
        handlers[capability] = handler
    }

    fun detach(capability: String) {
        handlers.remove(capability)
    }

    fun dispatch(context: Context, intent: Intent, capability: String): Boolean =
        handlers[capability]?.let { handler ->
            handler(context, intent)
            true
        } ?: false
}

internal class InstallResultReceiver : android.content.BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val appContext = context.applicationContext
        val logger = runCatching {
            com.sysadmin.lasstore.data.ServiceLocator.init(appContext)
            com.sysadmin.lasstore.data.ServiceLocator.logger
        }.getOrElse { Logger(appContext) }
        val registry = InstallResultRegistry(appContext)
        val envelope = InstallResultEnvelope.from(intent)
        val registration = registry.find(envelope.capability)
        val validation = InstallResultValidator.validate(registration, envelope)
        if (validation is InstallResultValidation.Rejected) {
            logger.warn("Installer", "Rejected install result: ${validation.reason}")
            return
        }

        validation as InstallResultValidation.Accepted
        if (validation.terminal && !registry.consume(validation.registration)) {
            logger.warn("Installer", "Rejected replayed install result for session ${validation.registration.sessionId}")
            return
        }

        when (validation.registration.route) {
            InstallResultRoute.Foreground,
            InstallResultRoute.Preapproval -> {
                val delivered = ForegroundInstallResultRouter.dispatch(
                    appContext,
                    intent,
                    validation.registration.capability,
                )
                if (!delivered) {
                    logger.warn(
                        "Installer",
                        "No active callback for session ${validation.registration.sessionId}",
                    )
                }
            }
            InstallResultRoute.Queued ->
                QueuedInstallResultHandler.handle(appContext, intent, logger)
        }
    }
}

internal fun installResultIntent(
    context: Context,
    registration: InstallResultRegistration,
    resultData: Intent? = null,
): Intent = Intent(context, InstallResultReceiver::class.java).apply {
    action = ACTION_INSTALL_RESULT
    setPackage(context.packageName)
    resultData?.extras?.let(::putExtras)
    putExtra(EXTRA_CAPABILITY, registration.capability)
    putExtra(EXTRA_DECLARED_SESSION_ID, registration.sessionId)
    putExtra(EXTRA_DECLARED_APPLICATION_ID, registration.applicationId)
}

internal fun Intent.pendingUserActionIntent(): Intent? =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
    } else {
        @Suppress("DEPRECATION")
        getParcelableExtra(Intent.EXTRA_INTENT)
    }

private fun Intent.intExtraOrNull(name: String): Int? =
    if (hasExtra(name)) getIntExtra(name, STATUS_MISSING) else null

private const val ACTION_INSTALL_RESULT = "com.sysadmin.lasstore.action.INSTALL_RESULT"
internal const val EXTRA_CAPABILITY = "com.sysadmin.lasstore.extra.INSTALL_CAPABILITY"
internal const val EXTRA_DECLARED_SESSION_ID = "com.sysadmin.lasstore.extra.INSTALL_SESSION"
internal const val EXTRA_DECLARED_APPLICATION_ID = "com.sysadmin.lasstore.extra.INSTALL_PACKAGE"
private const val STATUS_MISSING = Int.MIN_VALUE
