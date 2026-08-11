package com.sysadmin.lasstore.data

import android.annotation.SuppressLint
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.sysadmin.lasstore.install.BackgroundUpdateScheduler
import com.sysadmin.lasstore.install.BatchUninstallStore
import com.sysadmin.lasstore.install.DownloadQueueStore
import com.sysadmin.lasstore.install.ForegroundInstallStore
import com.sysadmin.lasstore.install.ArchiveRestoreStore
import com.sysadmin.lasstore.install.PackageInstallerService
import com.sysadmin.lasstore.install.QueuedUpdateStatusStore
import com.sysadmin.lasstore.wear.WearUpdateMessenger

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
    lateinit var fdroidIndex: FdroidIndexClient
        private set
    lateinit var sourceBranding: SourceBrandingClient
        private set
    lateinit var sourceDirectory: SourceDirectoryClient
        private set
    lateinit var installer: PackageInstallerService
        private set
    lateinit var audit: InstallAuditLog
        private set
    lateinit var appIdCache: AppIdCache
        private set
    lateinit var apkLockfile: ApkLockfileStore
        private set
    lateinit var preferredSources: PreferredSourceStore
        private set
    lateinit var ignoreList: IgnoreListStore
        private set
    lateinit var updateCadences: UpdateCadenceStore
        private set
    lateinit var channelPreferences: ChannelPreferenceStore
        private set
    lateinit var backgroundUpdates: BackgroundUpdateScheduler
        private set
    lateinit var downloadQueue: DownloadQueueStore
        private set
    lateinit var batchUninstalls: BatchUninstallStore
        private set
    lateinit var queuedUpdateStatus: QueuedUpdateStatusStore
        private set
    lateinit var catalogSnapshots: CatalogSnapshotStore
        private set
    lateinit var foregroundInstalls: ForegroundInstallStore
        private set
    lateinit var archiveRestores: ArchiveRestoreStore
        private set
    lateinit var library: LibraryStore
        private set
    lateinit var libraryRestore: LibraryRestoreStore
        private set
    lateinit var libraryExport: LibraryExportStore
        private set
    lateinit var wearUpdates: WearUpdateMessenger
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
            fdroidIndex = FdroidIndexClient(
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
            sourceBranding = SourceBrandingClient()
            sourceDirectory = SourceDirectoryClient()
            installer = PackageInstallerService(appContext, logger)
            audit = InstallAuditLog(appContext)
            appIdCache = AppIdCache(appContext)
            apkLockfile = ApkLockfileStore(appContext)
            preferredSources = PreferredSourceStore(appContext)
            ignoreList = IgnoreListStore(appContext)
            updateCadences = UpdateCadenceStore(appContext)
            channelPreferences = ChannelPreferenceStore(appContext)
            queuedUpdateStatus = QueuedUpdateStatusStore(appContext)
            backgroundUpdates = BackgroundUpdateScheduler(appContext, logger)
            downloadQueue = DownloadQueueStore(appContext)
            batchUninstalls = BatchUninstallStore(appContext)
            foregroundInstalls = ForegroundInstallStore(appContext)
            archiveRestores = ArchiveRestoreStore(appContext)
            library = LibraryStore(appContext)
            libraryRestore = LibraryRestoreStore(appContext)
            libraryExport = LibraryExportStore(
                context = appContext,
                library = library,
                apkLockfile = apkLockfile,
            )
            wearUpdates = WearUpdateMessenger(appContext) { throwable ->
                logger.warn("Wear", "Could not deliver update count: ${throwable.message}")
            }
            wearUpdates.registerPhoneCapability()
            initialized = true
        }
    }
}
