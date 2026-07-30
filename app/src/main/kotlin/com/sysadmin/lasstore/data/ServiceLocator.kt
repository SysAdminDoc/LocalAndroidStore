package com.sysadmin.lasstore.data

import android.annotation.SuppressLint
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.sysadmin.lasstore.install.BackgroundUpdateScheduler
import com.sysadmin.lasstore.install.ForegroundInstallStore
import com.sysadmin.lasstore.install.PackageInstallerService
import com.sysadmin.lasstore.install.QueuedUpdateStatusStore

@SuppressLint("StaticFieldLeak")
object ServiceLocator {
    @Volatile private var initialized: Boolean = false

    lateinit var appContext: Context
        private set
    lateinit var logger: Logger
        private set
    lateinit var secrets: SecretStore
        private set
    lateinit var settings: SettingsStore
        private set
    lateinit var apkInspector: ApkInspector
        private set
    lateinit var developerVerification: DeveloperVerificationPreflight
        private set
    lateinit var installState: InstallStateRepo
        private set
    lateinit var github: GitHubClient
        private set
    lateinit var installer: PackageInstallerService
        private set
    lateinit var audit: InstallAuditLog
        private set
    lateinit var appIdCache: AppIdCache
        private set
    lateinit var ignoreList: IgnoreListStore
        private set
    lateinit var backgroundUpdates: BackgroundUpdateScheduler
        private set
    lateinit var queuedUpdateStatus: QueuedUpdateStatusStore
        private set
    lateinit var catalogSnapshots: CatalogSnapshotStore
        private set
    lateinit var foregroundInstalls: ForegroundInstallStore
        private set

    fun init(context: Context) {
        if (initialized) return
        synchronized(this) {
            if (initialized) return
            appContext = context.applicationContext
            logger = Logger(appContext).also { it.installCrashHandler() }
            secrets = SecretStore(appContext)
            settings = SettingsStore(appContext, secrets)
            apkInspector = ApkInspector(appContext)
            developerVerification = DeveloperVerificationPreflight(appContext)
            installState = InstallStateRepo(appContext)
            catalogSnapshots = CatalogSnapshotStore(appContext)
            github = GitHubClient(
                patProvider = { secrets.getPat() },
                logger = logger,
                responseCache = FileGitHubResponseCache(appContext),
                networkAvailable = {
                    val connectivity =
                        appContext.getSystemService(ConnectivityManager::class.java)
                    val capabilities = connectivity.activeNetwork
                        ?.let(connectivity::getNetworkCapabilities)
                    capabilities?.hasCapability(
                        NetworkCapabilities.NET_CAPABILITY_VALIDATED
                    ) == true
                },
            )
            installer = PackageInstallerService(appContext, logger)
            audit = InstallAuditLog(appContext)
            appIdCache = AppIdCache(appContext)
            ignoreList = IgnoreListStore(appContext)
            queuedUpdateStatus = QueuedUpdateStatusStore(appContext)
            backgroundUpdates = BackgroundUpdateScheduler(appContext, logger)
            foregroundInstalls = ForegroundInstallStore(appContext)
            initialized = true
        }
    }
}
