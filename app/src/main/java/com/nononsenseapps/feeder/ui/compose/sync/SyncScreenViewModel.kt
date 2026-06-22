package com.nononsenseapps.feeder.ui.compose.sync

import android.app.Application
import android.util.Log
import android.widget.Toast
import androidx.compose.runtime.Immutable
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.nononsenseapps.feeder.ApplicationCoroutineScope
import com.nononsenseapps.feeder.R
import com.nononsenseapps.feeder.archmodel.Repository
import com.nononsenseapps.feeder.background.runOnceRssSync
import com.nononsenseapps.feeder.base.DIAwareViewModel
import com.nononsenseapps.feeder.db.room.DEFAULT_SERVER_ADDRESS
import com.nononsenseapps.feeder.db.room.SyncDevice
import com.nononsenseapps.feeder.db.room.SyncRemote
import com.nononsenseapps.feeder.util.DEEP_LINK_BASE_URI
import com.nononsenseapps.feeder.util.logDebug
import com.nononsenseapps.feeder.util.urlEncode
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.kodein.di.DI
import org.kodein.di.instance
import java.net.URL

class SyncScreenViewModel(
    di: DI,
    private val state: SavedStateHandle,
) : DIAwareViewModel(di) {
    private val context: Application by instance()
    private val repository: Repository by instance()

    private val applicationCoroutineScope: ApplicationCoroutineScope by instance()

    private val syncCode: MutableStateFlow<String> =
        MutableStateFlow(
            state["syncCode"] ?: "",
        )

    private val secretKey: MutableStateFlow<String> =
        MutableStateFlow(
            state["secretKey"] ?: "",
        )

    // The sync server address. Seeded from the persisted SyncRemote in init below.
    private val serverUrl: MutableStateFlow<String> = MutableStateFlow("")

    private val screenToShow: MutableStateFlow<SyncScreenToShow> =
        MutableStateFlow(
            state["syncScreen"] ?: SyncScreenToShow.SETUP,
        )

    init {
        if (syncCode.value.isNotBlank() || secretKey.value.isNotBlank()) {
            if (!state.contains("syncScreen")) {
                setScreen(SyncScreenToShow.JOIN)
            }
        }
    }

    fun setSyncCode(value: String) {
        val possibleUrlCode = value.syncCodeQueryParam

        val code =
            if (possibleUrlCode.length == 64) {
                possibleUrlCode
            } else {
                value
            }

        state["syncCode"] = code
        syncCode.update { code }

        // If a full join link was pasted, pick up the self-hosted server address too.
        val server = value.serverQueryParam
        if (server.isNotBlank()) {
            setServerUrl(server)
        }
    }

    fun setSecretKey(value: String) {
        val key = value.secretKeyQueryParam

        state["secretKey"] = key
        secretKey.update { key }
    }

    fun setScreen(value: SyncScreenToShow) {
        state["syncScreen"] = value
        screenToShow.update { value }
    }

    /**
     * Updates the sync server address. The raw text is always reflected in the UI; only valid
     * URLs are persisted to the [SyncRemote] (used by create/join). Set this before creating or
     * joining a chain so the right server is contacted.
     */
    fun setServerUrl(value: String) {
        serverUrl.update { value }
        val parsed = runCatching { URL(value.trim()) }.getOrNull() ?: return
        applicationCoroutineScope.launch {
            val current = repository.getSyncRemote()
            if (current.url.toString() != parsed.toString()) {
                repository.updateSyncRemote(current.copy(url = parsed))
            }
        }
    }

    fun updateDeviceList() {
        applicationCoroutineScope.launch {
            logDebug(tag = LOG_TAG, "Update Devices")
            repository
                .updateDeviceList()
                .onLeft {
                    Log.e(LOG_TAG, "updateDeviceList: ${it.code}: ${it.body}", it.throwable)
                }
        }
    }

    fun joinSyncChain(
        syncCode: String,
        secretKey: String,
    ) {
        logDebug(tag = LOG_TAG, "Joining sync chain")
        viewModelScope.launch {
            try {
                applicationCoroutineScope
                    .async {
                        repository.joinSyncChain(syncCode = syncCode, secretKey = secretKey)
                    }.await()
                    .onRight {
                        runOnceRssSync(
                            di = di,
                            triggeredByUser = false,
                        )
                        joinedWithSyncCode(syncCode = syncCode, secretKey = secretKey)
                    }.onLeft {
                        Log.e(LOG_TAG, "joinSyncChain: ${it.code}, ${it.body}", it.throwable)
                    }
            } catch (e: Exception) {
                Log.e(LOG_TAG, "Error when joining sync chain", e)
            }
        }
    }

    fun leaveSyncChain() {
        applicationCoroutineScope.launch {
            repository.leaveSyncChain()
            setSyncCode("")
            setSecretKey("")
            setScreen(SyncScreenToShow.SETUP)
        }
    }

    fun removeDevice(deviceId: Long) {
        applicationCoroutineScope.launch {
            try {
                repository.removeDevice(deviceId = deviceId)
            } catch (e: Exception) {
                Log.e(LOG_TAG, "Error when removing device", e)
            }
        }
    }

    private fun joinedWithSyncCode(
        syncCode: String,
        secretKey: String,
    ) {
        setSyncCode(syncCode)
        setSecretKey(secretKey)
        setScreen(SyncScreenToShow.ADD_DEVICE)
    }

    fun startNewSyncChain() {
        applicationCoroutineScope.launch {
            try {
                repository
                    .startNewSyncChain()
                    .onRight { (syncCode, secretKey) ->
                        joinedWithSyncCode(syncCode = syncCode, secretKey = secretKey)
                    }.onLeft {
                        Log.e(LOG_TAG, "startNewChain: ${it.body}", it.throwable)
                    }
            } catch (e: Exception) {
                Log.e(LOG_TAG, "Error when starting new sync chain", e)
            }
        }
    }

    fun onMissingBarCodeScanner() {
        Toast.makeText(context, R.string.no_barcode_scanner_installed, Toast.LENGTH_SHORT).show()
    }

    private val _viewState = MutableStateFlow(SyncScreenViewState())
    val viewState: StateFlow<SyncScreenViewState>
        get() = _viewState.asStateFlow()

    init {
        viewModelScope.launch {
            repository.getSyncRemote().let { syncRemote ->
                if (!state.contains("syncCode")) {
                    setSyncCode(syncRemote.syncChainId)
                }
                serverUrl.update { syncRemote.url.toString() }
            }

            combine(
                syncCode,
                repository.getSyncRemoteFlow(),
                screenToShow,
                repository.getDevices(),
                secretKey,
                serverUrl,
            ) { params ->
                val syncCode = params[0] as String
                val syncRemote = params[1] as SyncRemote?
                val screen = params[2] as SyncScreenToShow
                val secretKey = params[4] as String
                val serverUrl = params[5] as String

                @Suppress("UNCHECKED_CAST")
                val deviceList = params[3] as List<SyncDevice>

                val actualScreen =
                    if (syncRemote?.syncChainId?.length == 64) {
                        when (screen) {
                            // Setup and join only possible if nothing setup already
                            SyncScreenToShow.SETUP,
                            SyncScreenToShow.JOIN,
                            -> SyncScreenToShow.DEVICELIST

                            SyncScreenToShow.DEVICELIST,
                            SyncScreenToShow.ADD_DEVICE,
                            -> screen
                        }
                    } else {
                        when (screen) {
                            SyncScreenToShow.SETUP,
                            SyncScreenToShow.JOIN,
                            -> screen

                            SyncScreenToShow.DEVICELIST,
                            SyncScreenToShow.ADD_DEVICE,
                            -> SyncScreenToShow.SETUP
                        }
                    }

                Log.v(
                    LOG_TAG,
                    "Showing $actualScreen, remoteCode: ${syncRemote?.syncChainId?.take(10)}",
                )

                val remoteKeyEncoded = (syncRemote?.secretKey ?: "").urlEncode()
                val serverAddress = syncRemote?.url?.toString() ?: DEFAULT_SERVER_ADDRESS
                // For the official server use the app's deep-link host so the link opens the app
                // when tapped; for a self-hosted server use its own address so the QR reflects it.
                // Always carry the server as a query param so the joining device auto-configures it.
                val joinLinkBase =
                    if (serverAddress.contains("nononsenseapps.com", ignoreCase = true)) {
                        DEEP_LINK_BASE_URI
                    } else {
                        serverAddress.trimEnd('/')
                    }

                SyncScreenViewState(
                    syncCode = syncCode,
                    addNewDeviceUrl =
                        URL(
                            "$joinLinkBase/sync/join?sync_code=${syncRemote?.syncChainId ?: ""}" +
                                "&key=$remoteKeyEncoded&server=${serverAddress.urlEncode()}",
                        ),
                    singleScreenToShow = actualScreen,
                    deviceId = syncRemote?.deviceId ?: 0,
                    deviceList = deviceList,
                    secretKey = secretKey,
                    serverUrl = serverUrl,
                )
            }.collect {
                _viewState.value = it
            }
        }
    }

    companion object {
        private const val LOG_TAG = "FEEDER_SYNCVMODEL"
    }
}

@Immutable
data class SyncScreenViewState(
    val syncCode: String = "",
    val secretKey: String = "",
    val addNewDeviceUrl: URL = URL("https://"),
    val singleScreenToShow: SyncScreenToShow = SyncScreenToShow.SETUP,
    val deviceId: Long = 0,
    val deviceList: List<SyncDevice> = emptyList(),
    val serverUrl: String = "",
) {
    val leftScreenToShow: LeftScreenToShow
        get() =
            when (singleScreenToShow) {
                SyncScreenToShow.SETUP, SyncScreenToShow.JOIN -> LeftScreenToShow.SETUP
                SyncScreenToShow.DEVICELIST, SyncScreenToShow.ADD_DEVICE -> LeftScreenToShow.DEVICELIST
            }

    val rightScreenToShow: RightScreenToShow
        get() =
            when (singleScreenToShow) {
                SyncScreenToShow.SETUP, SyncScreenToShow.JOIN -> RightScreenToShow.JOIN
                SyncScreenToShow.DEVICELIST, SyncScreenToShow.ADD_DEVICE -> RightScreenToShow.ADD_DEVICE
            }
}

enum class SyncScreenToShow {
    SETUP,
    DEVICELIST,
    ADD_DEVICE,
    JOIN,
}

enum class LeftScreenToShow {
    SETUP,
    DEVICELIST,
}

enum class RightScreenToShow {
    ADD_DEVICE,
    JOIN,
}
