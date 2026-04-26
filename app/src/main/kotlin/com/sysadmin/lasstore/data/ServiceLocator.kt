package com.sysadmin.lasstore.data

import android.annotation.SuppressLint
import android.content.Context
import com.sysadmin.lasstore.install.PackageInstallerService

@SuppressLint("StaticFieldLeak")
object ServiceLocator {
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

    fun init(context: Context) {
        appContext = context.applicationContext
        logger = Logger(appContext).also { it.installCrashHandler() }
        secrets = SecretStore(appContext)
        settings = SettingsStore(appContext, secrets)
        apkInspector = ApkInspector(appContext)
        developerVerification = DeveloperVerificationPreflight(appContext)
        installState = InstallStateRepo(appContext)
        github = GitHubClient(patProvider = { secrets.getPat() }, logger = logger)
        installer = PackageInstallerService(appContext, logger)
        audit = InstallAuditLog(appContext)
    }
}
