package com.sysadmin.lasstore.ui.catalog

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sysadmin.lasstore.data.AppSettings
import com.sysadmin.lasstore.data.ServiceLocator
import com.sysadmin.lasstore.domain.AppInfo
import com.sysadmin.lasstore.domain.CardStatus
import com.sysadmin.lasstore.domain.DiscoveryUseCase
import com.sysadmin.lasstore.install.InstallResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File

data class CardState(
    val info: AppInfo,
    val status: CardStatus,
    val installedVersion: String? = null,
    val installedVersionCode: Long? = null,
    val progress: Float = 0f,
    val message: String? = null,
)

data class CatalogUiState(
    val refreshing: Boolean = false,
    val cards: List<CardState> = emptyList(),
    val canRequestInstalls: Boolean = true,
    val errorMessage: String? = null,
    val warning: String? = null,
)

class CatalogViewModel : ViewModel() {
    private val sl = ServiceLocator
    private val discovery = DiscoveryUseCase(sl.github, sl.logger)

    private val _state = MutableStateFlow(CatalogUiState())
    val state: StateFlow<CatalogUiState> = _state.asStateFlow()

    init {
        refreshInstallPermission()
        refresh()
    }

    fun refreshInstallPermission() {
        _state.update { it.copy(canRequestInstalls = sl.installer.canRequestInstalls()) }
    }

    fun openInstallPermissionSettings() = sl.installer.openInstallPermissionSettings()

    fun refresh() {
        viewModelScope.launch(Dispatchers.IO) {
            _state.update { it.copy(refreshing = true, errorMessage = null) }
            val settings: AppSettings = sl.settings.flow.first()
            val infos = runCatching { discovery.discover(settings) }
                .onFailure { sl.logger.error("Catalog", "discover failed", it) }
                .getOrElse { emptyList() }
            sl.logger.info("Catalog", "Discovered ${infos.size} APK-bearing repos for ${settings.githubUser}")
            val cards = infos.map { info -> buildCardState(info) }
            _state.update { it.copy(refreshing = false, cards = cards) }
        }
    }

    private fun buildCardState(info: AppInfo): CardState {
        val installed = info.applicationId?.let { sl.installState.info(it) }
        return when {
            installed == null -> {
                // We don't know the applicationId until we've inspected the APK.
                // Until then, present as not-installed; install flow will resolve it.
                CardState(info = info, status = CardStatus.NotInstalled)
            }
            else -> {
                val updateAvailable = info.versionCode != null && info.versionCode > installed.versionCode
                val status = if (updateAvailable) CardStatus.UpdateAvailable else CardStatus.Installed
                CardState(
                    info = info,
                    status = status,
                    installedVersion = installed.versionName,
                    installedVersionCode = installed.versionCode,
                )
            }
        }
    }

    fun install(card: CardState) {
        if (!sl.installer.canRequestInstalls()) {
            _state.update { it.copy(warning = "Grant 'Install unknown apps' first.") }
            sl.installer.openInstallPermissionSettings()
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            updateCard(card.info) { it.copy(status = CardStatus.Working, progress = 0.01f, message = "Downloading…") }
            try {
                val cacheDir = File(sl.appContext.cacheDir, "apks").apply { mkdirs() }
                val target = File(cacheDir, "${card.info.owner}_${card.info.repo}_${card.info.tagName}.apk")
                sl.github.download(card.info.asset.browserDownloadUrl, target) { d, t ->
                    val frac = if (t > 0) (d.toFloat() / t.toFloat()).coerceIn(0f, 1f) else 0f
                    updateCard(card.info) { it.copy(progress = frac, message = "Downloading… ${(frac * 100).toInt()}%") }
                }

                val meta = sl.apkInspector.inspect(target)
                if (meta == null) {
                    sl.logger.error("Install", "ApkInspector returned null for ${target.absolutePath}")
                    updateCard(card.info) { it.copy(status = CardStatus.Error, message = "APK metadata read failed") }
                    return@launch
                }

                // Signature pinning — block silent publisher swap.
                val pinned = sl.secrets.getPin(meta.applicationId)
                val installedAlready = sl.installState.info(meta.applicationId) != null
                if (pinned != null && pinned.isNotEmpty() && pinned != meta.signingSha256) {
                    sl.logger.error(
                        "Install",
                        "Signature pin mismatch for ${meta.applicationId}: pinned=$pinned actual=${meta.signingSha256}"
                    )
                    updateCard(card.info) {
                        it.copy(
                            status = CardStatus.SignatureMismatch,
                            message = "Publisher key changed — install blocked. " +
                                "Possible MITM or repo takeover. Install manually if you trust this update.",
                        )
                    }
                    return@launch
                }

                updateCard(card.info) { it.copy(message = "Installing…") }
                val result = sl.installer.installApk(target)
                when (result) {
                    is InstallResult.Success -> {
                        // First successful install pins the signing cert.
                        if (pinned == null) sl.secrets.setPin(meta.applicationId, meta.signingSha256)
                        sl.logger.info("Install", "Installed ${meta.applicationId} ${meta.versionName}")
                        // Refresh installed-state row.
                        val installedInfo = sl.installState.info(meta.applicationId)
                        updateCard(card.info) { state ->
                            state.copy(
                                info = state.info.copy(
                                    applicationId = meta.applicationId,
                                    versionCode = meta.versionCode,
                                    versionName = meta.versionName ?: state.info.versionName,
                                ),
                                status = CardStatus.Installed,
                                installedVersion = installedInfo?.versionName ?: meta.versionName,
                                installedVersionCode = installedInfo?.versionCode ?: meta.versionCode,
                                progress = 1f,
                                message = null,
                            )
                        }
                    }
                    is InstallResult.Failure -> {
                        sl.logger.warn("Install", "Install failed for ${meta.applicationId}: ${result.message}")
                        updateCard(card.info) { it.copy(status = CardStatus.Error, message = result.message) }
                    }
                }
            } catch (t: Throwable) {
                sl.logger.error("Install", "Install pipeline crashed", t)
                updateCard(card.info) { it.copy(status = CardStatus.Error, message = t.message ?: "install failed") }
            }
        }
    }

    fun uninstall(card: CardState) {
        val applicationId = card.info.applicationId ?: return
        sl.installer.openAppInfo(applicationId)
        sl.logger.info("Uninstall", "Opened delete intent for $applicationId")
        // We let the user finish; on next refresh InstallStateRepo will catch up.
    }

    fun open(card: CardState) {
        val applicationId = card.info.applicationId ?: return
        if (!sl.installer.launch(applicationId)) {
            _state.update { it.copy(warning = "Couldn't launch $applicationId — no exported launcher activity?") }
        }
    }

    fun openRepo(card: CardState) {
        val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(card.info.htmlUrl))
            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        sl.appContext.startActivity(intent)
    }

    fun dismissWarning() = _state.update { it.copy(warning = null) }

    private fun updateCard(info: AppInfo, transform: (CardState) -> CardState) {
        _state.update { ui ->
            ui.copy(cards = ui.cards.map {
                if (it.info.owner == info.owner && it.info.repo == info.repo) transform(it) else it
            })
        }
    }
}
