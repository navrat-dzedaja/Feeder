package com.nononsenseapps.feeder.sync

import android.util.Log
import androidx.annotation.VisibleForTesting
import com.nononsenseapps.feeder.archmodel.Repository
import com.nononsenseapps.feeder.crypto.AesCbcWithIntegrity
import com.nononsenseapps.feeder.crypto.SecretKeys
import com.nononsenseapps.feeder.db.room.DEFAULT_SERVER_ADDRESS
import com.nononsenseapps.feeder.db.room.DEPRECATED_SYNC_HOSTS
import com.nononsenseapps.feeder.db.room.Feed
import com.nononsenseapps.feeder.db.room.FeedItemForReadMark
import com.nononsenseapps.feeder.db.room.RemoteFeed
import com.nononsenseapps.feeder.db.room.SyncDevice
import com.nononsenseapps.feeder.db.room.SyncRemote
import com.nononsenseapps.feeder.db.room.generateDeviceName
import com.nononsenseapps.feeder.model.OPMLParserHandler
import com.nononsenseapps.feeder.util.Either
import com.nononsenseapps.feeder.util.logDebug
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.kodein.di.DI
import org.kodein.di.DIAware
import org.kodein.di.instance
import retrofit2.Response
import java.net.URL
import java.time.Instant

class SyncRestClient(
    override val di: DI,
) : DIAware {
    private val repository: Repository by instance()
    private val okHttpClient: OkHttpClient by instance()
    private val opmlParserHandler: OPMLParserHandler by instance()
    private var feederSync: FeederSync? = null
    private var secretKey: SecretKeys? = null
    private val moshi = getMoshi()
    private val readMarkAdapter = moshi.adapter<ReadMarkContent>()
    private val feedsAdapter = moshi.adapter<EncryptedFeeds>()
    private val settingsAdapter = moshi.adapter<EncryptedSettings>()

    init {
        runBlocking {
            initialize()
        }
    }

    val isConfigured: Boolean
        get() = isInitialized

    private val isInitialized: Boolean
        get() = feederSync != null && secretKey != null
    private val isNotInitialized
        get() = !isInitialized

    internal suspend fun initialize() {
        if (isNotInitialized) {
            try {
                var syncRemote = repository.getSyncRemote()
                if (DEPRECATED_SYNC_HOSTS.any { host -> host in "${syncRemote.url}" }) {
                    logDebug(
                        LOG_TAG,
                        "Updating to latest sync host: $DEFAULT_SERVER_ADDRESS",
                    )
                    syncRemote =
                        syncRemote.copy(
                            url = URL(DEFAULT_SERVER_ADDRESS),
                        )
                    repository.updateSyncRemote(syncRemote)
                }
                if (syncRemote.hasSyncChain()) {
                    secretKey = AesCbcWithIntegrity.decodeKey(syncRemote.secretKey)
                    feederSync =
                        getFeederSyncClient(
                            syncRemote = syncRemote,
                            okHttpClient = okHttpClient,
                        )
                }
            } catch (e: Exception) {
                Log.e(LOG_TAG, "Failed to initialize", e)
            }
        }
    }

    private suspend fun <A> safeBlock(block: suspend (SyncRemote, FeederSync, SecretKeys) -> Either<ErrorResponse, A>): Either<ErrorResponse, A> {
        repository.getSyncRemote().let { syncRemote ->
            if (syncRemote.hasSyncChain()) {
                feederSync?.let { feederSync ->
                    secretKey?.let { secretKey ->
                        return block(syncRemote, feederSync, secretKey)
                    }
                }
            }
        }
        return Either.Left(
            ErrorResponse(
                code = 1001,
                body = null,
            ),
        )
    }

    suspend fun create(): Either<ErrorResponse, String> {
        logDebug(LOG_TAG, "create")
        // To ensure always uses correct client, manually set remote here ALWAYS
        val syncRemote = repository.getSyncRemote()

        val secretKey = AesCbcWithIntegrity.decodeKey(syncRemote.secretKey)
        this.secretKey = secretKey

        val feederSync =
            getFeederSyncClient(
                syncRemote = syncRemote,
                okHttpClient = okHttpClient,
            )
        this.feederSync = feederSync

        val deviceName = generateDeviceName()

        return feederSync
            .create(
                CreateRequest(
                    deviceName = AesCbcWithIntegrity.encryptString(deviceName, secretKey),
                ),
            ).toEither()
            .onRight { response ->
                repository.updateSyncRemote(
                    syncRemote.copy(
                        syncChainId = response.syncCode,
                        deviceId = response.deviceId,
                        deviceName = deviceName,
                        latestMessageTimestamp = Instant.EPOCH,
                    ),
                )
            }.map { response ->
                response.syncCode
            }
    }

    suspend fun join(
        syncCode: String,
        remoteSecretKey: String,
    ): Either<ErrorResponse, String> {
        logDebug(LOG_TAG, "join")
        try {
            logDebug(LOG_TAG, "Really joining")
            // To ensure always uses correct client, manually set remote here ALWAYS
            val syncRemote = repository.getSyncRemote()
            syncRemote.secretKey = remoteSecretKey
            syncRemote.deviceName = generateDeviceName()
            repository.updateSyncRemote(syncRemote)

            val secretKey = AesCbcWithIntegrity.decodeKey(syncRemote.secretKey)
            this.secretKey = secretKey

            val feederSync =
                getFeederSyncClient(
                    syncRemote = syncRemote,
                    okHttpClient = okHttpClient,
                )
            this.feederSync = feederSync

            logDebug(LOG_TAG, "Updated objects")

            return feederSync
                .join(
                    syncChainId = syncCode,
                    request =
                        JoinRequest(
                            deviceName =
                                AesCbcWithIntegrity.encryptString(
                                    syncRemote.deviceName,
                                    secretKey,
                                ),
                        ),
                ).toEither()
                .onRight { response ->
                    logDebug(LOG_TAG, "Join response: $response")

                    repository.updateSyncRemote(
                        syncRemote.copy(
                            syncChainId = response.syncCode,
                            deviceId = response.deviceId,
                            latestMessageTimestamp = Instant.EPOCH,
                        ),
                    )

                    logDebug(LOG_TAG, "Updated sync remote")
                }.map { response ->
                    response.syncCode
                }
        } catch (e: Exception) {
            if (e is retrofit2.HttpException) {
                Log.e(
                    LOG_TAG,
                    "Error during leave: msg: code: ${e.code()}, error: ${
                        e.response()?.errorBody()?.string()
                    }",
                    e,
                )
            } else {
                Log.e(LOG_TAG, "Error during leave", e)
            }
            return Either.Left(ErrorResponse(999, e.message))
        }
    }

    suspend fun leave(): Either<ErrorResponse, Unit> {
        logDebug(LOG_TAG, "leave")
        return try {
            safeBlock { syncRemote, feederSync, _ ->
                logDebug(LOG_TAG, "Really leaving")
                feederSync
                    .removeDevice(
                        syncChainId = syncRemote.syncChainId,
                        currentDeviceId = syncRemote.deviceId,
                        deviceId = syncRemote.deviceId,
                    ).toEither()
                    .onLeft {
                        Log.e(LOG_TAG, "Error during leave: ${it.code}, ${it.body}", it.throwable)
                    }.map {
                    }.also {
                        this.feederSync = null
                        this.secretKey = null
                    }
            }
        } catch (e: Exception) {
            Log.e(LOG_TAG, "Error during leave", e)
            Either.Left(
                ErrorResponse(1000, e.message, e),
            )
        } finally {
            repository.replaceWithDefaultSyncRemote()
        }
    }

    suspend fun removeDevice(deviceId: Long): Either<ErrorResponse, DeviceListResponse> =
        try {
            safeBlock { syncRemote, feederSync, secretKey ->
                logDebug(LOG_TAG, "removeDevice")
                feederSync
                    .removeDevice(
                        syncChainId = syncRemote.syncChainId,
                        currentDeviceId = syncRemote.deviceId,
                        deviceId = deviceId,
                    ).toEither()
                    .onRight { deviceListResponse ->
                        logDebug(LOG_TAG, "Updating device list: $deviceListResponse")

                        repository.replaceDevices(
                            deviceListResponse.devices.map {
                                SyncDevice(
                                    deviceId = it.deviceId,
                                    deviceName =
                                        AesCbcWithIntegrity.decryptString(
                                            it.deviceName,
                                            secretKey,
                                        ),
                                    syncRemote = syncRemote.id,
                                )
                            },
                        )
                    }.onLeft {
                        it.leaveChainIfKickedOutElseLog()
                    }
            }
        } catch (e: Exception) {
            Log.e(LOG_TAG, "Error in removeDevice", e)
            Either.Left(
                ErrorResponse(1000, e.message, e),
            )
        }

    private suspend fun sendReadMarksBatch(feedItems: List<FeedItemForReadMark>): Either<ErrorResponse, SendReadMarkResponse> =
        try {
            safeBlock { syncRemote, feederSync, secretKey ->
                logDebug(LOG_TAG, "markAsRead: ${feedItems.size} items")
                feederSync
                    .sendEncryptedReadMarks(
                        currentDeviceId = syncRemote.deviceId,
                        syncChainId = syncRemote.syncChainId,
                        request =
                            SendEncryptedReadMarkBulkRequest(
                                items =
                                    feedItems.map { feedItem ->
                                        SendEncryptedReadMarkRequest(
                                            encrypted =
                                                AesCbcWithIntegrity.encryptString(
                                                    secretKeys = secretKey,
                                                    plaintext =
                                                        readMarkAdapter.toJson(
                                                            ReadMarkContent(
                                                                feedUrl = feedItem.feedUrl,
                                                                articleGuid = feedItem.guid,
                                                            ),
                                                        ),
                                                ),
                                        )
                                    },
                            ),
                    ).toEither()
                    .onRight {
                        for (feedItem in feedItems) {
                            repository.setSynced(feedItemId = feedItem.id)
                        }
                    }.onLeft {
                        it.leaveChainIfKickedOutElseLog()
                    }

                // Should not set latest timestamp here because we cant be sure to retrieved them
            }
        } catch (e: Exception) {
            Log.e(LOG_TAG, "Error in markAsRead", e)
            Either.Left(
                ErrorResponse(1000, e.message, e),
            )
        }

    suspend fun markAsRead(): Either<ErrorResponse, Unit> =
        try {
            safeBlock { _, _, _ ->
                val readItems = repository.getFeedItemsWithoutSyncedReadMark()

                if (readItems.isNotEmpty()) {
                    logDebug(LOG_TAG, "markAsReadBatch: ${readItems.size} items")

                    readItems
                        .asSequence()
                        .chunked(100)
                        .forEach { feedItems ->
                            sendReadMarksBatch(feedItems)
                        }
                }
                Either.Right(Unit)
            }
        } catch (e: Exception) {
            Log.e(LOG_TAG, "Error in markAsRead", e)
            Either.Left(
                ErrorResponse(1000, e.message, e),
            )
        }

    internal suspend fun getDevices(): Either<ErrorResponse, DeviceListResponse> =
        try {
            logDebug(LOG_TAG, "getDevices")
            safeBlock { syncRemote, feederSync, secretKey ->
                logDebug(LOG_TAG, "getDevices Inside block")
                feederSync
                    .getDevices(
                        syncChainId = syncRemote.syncChainId,
                        currentDeviceId = syncRemote.deviceId,
                    ).toEither()
                    .onRight { response ->
                        logDebug(LOG_TAG, "getDevices: $response")

                        repository.replaceDevices(
                            response.devices.map {
                                logDebug(LOG_TAG, "device: $it")
                                SyncDevice(
                                    deviceId = it.deviceId,
                                    deviceName =
                                        AesCbcWithIntegrity.decryptString(
                                            it.deviceName,
                                            secretKey,
                                        ),
                                    syncRemote = syncRemote.id,
                                )
                            },
                        )
                    }.onLeft {
                        it.leaveChainIfKickedOutElseLog()
                    }
            }
        } catch (e: Exception) {
            Log.e(LOG_TAG, "Error in getDevices", e)
            Either.Left(
                ErrorResponse(1000, e.message, e),
            )
        }

    internal suspend fun getRead() {
        try {
            safeBlock { syncRemote, feederSync, secretKey ->
                logDebug(LOG_TAG, "getRead")
                feederSync
                    .getEncryptedReadMarks(
                        currentDeviceId = syncRemote.deviceId,
                        syncChainId = syncRemote.syncChainId,
                        // Add one ms so we don't get inclusive of last message we got
                        sinceMillis = syncRemote.latestMessageTimestamp.plusMillis(1).toEpochMilli(),
                    ).toEither()
                    .onRight { response ->
                        logDebug(LOG_TAG, "getRead: ${response.readMarks.size} read marks")
                        for (readMark in response.readMarks) {
                            val readMarkContent =
                                readMarkAdapter.fromJson(
                                    AesCbcWithIntegrity.decryptString(readMark.encrypted, secretKey),
                                )

                            if (readMarkContent == null) {
                                Log.e(LOG_TAG, "Failed to decrypt readMark content")
                                // Advance the timestamp so the same corrupt mark is not retried indefinitely.
                                repository.updateSyncRemoteMessageTimestamp(readMark.timestamp)
                                continue
                            }

                            repository.remoteMarkAsRead(
                                feedUrl = readMarkContent.feedUrl,
                                articleGuid = readMarkContent.articleGuid,
                            )
                            repository.updateSyncRemoteMessageTimestamp(readMark.timestamp)
                        }
                    }.onLeft {
                        it.leaveChainIfKickedOutElseLog()
                    }
            }
        } catch (e: Exception) {
            Log.e(LOG_TAG, "Error in getRead", e)
        }
    }

    internal suspend fun getFeeds() {
        if (!repository.syncFeedsEnabled.value) {
            return
        }
        try {
            safeBlock { syncRemote, feederSync, secretKey ->
                logDebug(LOG_TAG, "getFeeds")
                feederSync
                    .getFeeds(
                        syncChainId = syncRemote.syncChainId,
                        currentDeviceId = syncRemote.deviceId,
                    ).toEither()
                    .onRight { response ->
                        logDebug(LOG_TAG, "GetFeeds response hash: ${response.hash}")

                        if (response.hash == syncRemote.lastFeedsRemoteHash) {
                            // Nothing to do
                            logDebug(LOG_TAG, "GetFeeds got nothing new, returning.")
                            return@onRight
                        }

                        val encryptedFeeds =
                            feedsAdapter.fromJson(
                                AesCbcWithIntegrity.decryptString(
                                    response.encrypted,
                                    secretKeys = secretKey,
                                ),
                            )

                        if (encryptedFeeds == null) {
                            Log.e(LOG_TAG, "Failed to decrypt encrypted feeds")
                            return@onRight
                        }

                        feedDiffing(encryptedFeeds.feeds)

                        syncRemote.lastFeedsRemoteHash = response.hash
                        repository.updateSyncRemote(syncRemote)
                    }.onLeft {
                        it.leaveChainIfKickedOutElseLog()
                    }
            }
        } catch (e: Exception) {
            Log.e(LOG_TAG, "Error in getFeeds", e)
        }
    }

    internal suspend fun getSettings() {
        if (!repository.syncSettingsEnabled.value) {
            return
        }
        try {
            safeBlock { syncRemote, feederSync, secretKey ->
                logDebug(LOG_TAG, "getSettings")
                feederSync
                    .getSettings(
                        syncChainId = syncRemote.syncChainId,
                        currentDeviceId = syncRemote.deviceId,
                    ).toEither()
                    .onRight { response ->
                        logDebug(LOG_TAG, "GetSettings response hash: ${response.hash}")

                        if (response.hash == syncRemote.lastSettingsRemoteHash) {
                            // Nothing to do
                            return@onRight
                        }

                        val encryptedSettings =
                            settingsAdapter.fromJson(
                                AesCbcWithIntegrity.decryptString(
                                    response.encrypted,
                                    secretKeys = secretKey,
                                ),
                            )

                        if (encryptedSettings == null) {
                            Log.e(LOG_TAG, "Failed to decrypt encrypted settings")
                            return@onRight
                        }

                        applySettings(encryptedSettings.settings)

                        syncRemote.lastSettingsRemoteHash = response.hash
                        repository.updateSyncRemote(syncRemote)
                    }.onLeft {
                        it.ignoreIfSettingsUnsupportedElseHandle()
                    }
            }
        } catch (e: Exception) {
            Log.e(LOG_TAG, "Error in getSettings", e)
        }
    }

    private suspend fun feedDiffing(remoteFeeds: List<EncryptedFeed>) {
        try {
            logDebug(LOG_TAG, "feedDiffing: ${remoteFeeds.size}")
            val excludedUrls = repository.excludedSyncFeedUrls.value
            val remotelySeenFeedUrls = repository.getRemotelySeenFeeds()

            val feedUrlsWhichWereDeletedOnRemote =
                remotelySeenFeedUrls
                    .filterNot { url -> remoteFeeds.asSequence().map { it.url }.contains(url) }
                    // Never delete feeds the user excluded from sync on this device.
                    .filterNot { url -> url.toString() in excludedUrls }

            logDebug(LOG_TAG, "RemotelyDeleted: ${feedUrlsWhichWereDeletedOnRemote.size}")

            for (url in feedUrlsWhichWereDeletedOnRemote) {
                logDebug(LOG_TAG, "Deleting remotely deleted feed: $url")
                repository.deleteFeed(url)
            }

            for (remoteFeed in remoteFeeds) {
                // Excluded feeds are invisible to sync on this device - don't apply remote changes.
                if (remoteFeed.url.toString() in excludedUrls) {
                    continue
                }
                val seenRemotelyBefore = remoteFeed.url in remotelySeenFeedUrls
                val dbFeed = repository.getFeed(remoteFeed.url)

                when {
                    dbFeed == null && !seenRemotelyBefore -> {
                        // Entirely new remote feed
                        logDebug(LOG_TAG, "Saving new feed: ${remoteFeed.url}")
                        repository.saveFeed(
                            remoteFeed.updateFeedCopy(Feed()),
                        )
                    }

                    dbFeed == null && seenRemotelyBefore -> {
                        // Has been locally deleted, it will be deleted on next call of updateFeeds
                        logDebug(
                            LOG_TAG,
                            "Received update for locally deleted feed: ${remoteFeed.url}",
                        )
                    }

                    dbFeed != null -> {
                        // Update of feed
                        // Compare modification date - only save if remote is newer
                        if (remoteFeed.whenModified > dbFeed.whenModified) {
                            logDebug(LOG_TAG, "Saving updated feed: ${remoteFeed.url}")
                            repository.saveFeed(
                                remoteFeed.updateFeedCopy(dbFeed),
                            )
                        } else {
                            logDebug(
                                LOG_TAG,
                                "Not saving feed because local date trumps it: ${remoteFeed.url}",
                            )
                        }
                    }
                }
            }

            repository.replaceRemoteFeedsWith(
                remoteFeeds.map {
                    RemoteFeed(
                        syncRemote = 1L,
                        url = it.url,
                    )
                },
            )
        } catch (e: Exception) {
            Log.e(LOG_TAG, "Error in feedDiffing", e)
        }
    }

    suspend fun sendUpdatedFeeds(): Either<ErrorResponse, Boolean> {
        if (!repository.syncFeedsEnabled.value) {
            return Either.Right(false)
        }
        return try {
            safeBlock { syncRemote, feederSync, secretKey ->
                logDebug(LOG_TAG, "sendUpdatedFeeds")
                val lastRemoteHash = syncRemote.lastFeedsRemoteHash

                // Only send if hash does not match
                // Important to keep iteration order stable - across devices. So sort on URL, not ID or date
                val excludedUrls = repository.excludedSyncFeedUrls.value
                val feeds =
                    if (excludedUrls.isEmpty()) {
                        repository
                            .getFeedsOrderedByUrl()
                            .map { it.toEncryptedFeed() }
                    } else {
                        // Send local feeds the user opted to sync, but preserve excluded feeds from
                        // the remote blob so excluding one here doesn't delete it on other devices.
                        val localFeeds =
                            repository
                                .getFeedsOrderedByUrl()
                                .map { it.toEncryptedFeed() }
                                .filterNot { it.url.toString() in excludedUrls }
                        val preservedRemoteFeeds =
                            fetchRemoteFeeds(syncRemote, feederSync, secretKey)
                                .filter { it.url.toString() in excludedUrls }
                        (localFeeds + preservedRemoteFeeds).sortedBy { it.url.toString() }
                    }

                // Yes, List hashCodes are based on elements. Just remember to hash what you send
                // - and not raw database objects
                val currentContentHash = feeds.hashCode()

                if (lastRemoteHash == currentContentHash) {
                    // Nothing to do
                    logDebug(LOG_TAG, "Feeds haven't changed - so not sending")
                    return@safeBlock Either.Right(false)
                }

                val encrypted =
                    AesCbcWithIntegrity.encryptString(
                        feedsAdapter.toJson(
                            EncryptedFeeds(
                                feeds = feeds,
                            ),
                        ),
                        secretKeys = secretKey,
                    )

                logDebug(
                    LOG_TAG,
                    "Sending updated feeds with locally computed hash: $currentContentHash",
                )
                // Might fail with 412 in case already updated remotely - need to call get
                feederSync
                    .updateFeeds(
                        syncChainId = syncRemote.syncChainId,
                        currentDeviceId = syncRemote.deviceId,
                        etagValue = syncRemote.lastFeedsRemoteHash.asWeakETagValue(),
                        request =
                            UpdateFeedsRequest(
                                contentHash = currentContentHash,
                                encrypted = encrypted,
                            ),
                    ).toEither()
                    .onLeft {
                        if (it.code == 412) {
                            // Need to call get first because updates have happened
                            getFeeds()
                            // Now try again
                            sendUpdatedFeeds()
                        } else {
                            it.leaveChainIfKickedOutElseLog()
                        }
                    }.onRight { response ->
                        // Store hash for future
                        syncRemote.lastFeedsRemoteHash = response.hash
                        repository.updateSyncRemote(syncRemote)

                        logDebug(LOG_TAG, "Received updated feeds hash: ${response.hash}")
                    }.map {
                        true
                    }
            }
        } catch (e: Exception) {
            Log.e(LOG_TAG, "Error in sendUpdatedFeeds", e)
            Either.Left(
                ErrorResponse(1000, e.message, e),
            )
        }
    }

    suspend fun sendUpdatedSettings(): Either<ErrorResponse, Boolean> {
        if (!repository.syncSettingsEnabled.value) {
            return Either.Right(false)
        }
        return try {
            safeBlock { syncRemote, feederSync, secretKey ->
                logDebug(LOG_TAG, "sendUpdatedSettings")
                val lastRemoteHash = syncRemote.lastSettingsRemoteHash

                val excluded = repository.excludedSyncSettingKeys.value
                val localEnabled = repository.getAllSettings().filterKeys { it !in excluded }

                // Merge with the current remote so keys this device excludes (and thus does not
                // manage) are preserved for other devices instead of being wiped from the blob.
                val remoteSettings =
                    fetchRemoteSettingsMap(syncRemote, feederSync, secretKey)
                        ?: return@safeBlock Either.Right(false) // server doesn't support settings
                val settings = remoteSettings + localEnabled

                // List/Map hashCodes are based on elements. Just remember to hash what you send.
                val currentContentHash = settings.hashCode()

                if (lastRemoteHash == currentContentHash) {
                    // Nothing to do
                    logDebug(LOG_TAG, "Settings haven't changed - so not sending")
                    return@safeBlock Either.Right(false)
                }

                val encrypted =
                    AesCbcWithIntegrity.encryptString(
                        settingsAdapter.toJson(
                            EncryptedSettings(
                                settings = settings,
                            ),
                        ),
                        secretKeys = secretKey,
                    )

                // Might fail with 412 in case already updated remotely - need to call get
                feederSync
                    .updateSettings(
                        syncChainId = syncRemote.syncChainId,
                        currentDeviceId = syncRemote.deviceId,
                        etagValue = syncRemote.lastSettingsRemoteHash.asWeakETagValue(),
                        request =
                            UpdateSettingsRequest(
                                contentHash = currentContentHash,
                                encrypted = encrypted,
                            ),
                    ).toEither()
                    .onLeft {
                        if (it.code == 412) {
                            // Need to call get first because updates have happened
                            getSettings()
                            // Now try again
                            sendUpdatedSettings()
                        } else {
                            it.ignoreIfSettingsUnsupportedElseHandle()
                        }
                    }.onRight { response ->
                        syncRemote.lastSettingsRemoteHash = response.hash
                        repository.updateSyncRemote(syncRemote)

                        logDebug(LOG_TAG, "Received updated settings hash: ${response.hash}")
                    }.map {
                        true
                    }
            }
        } catch (e: Exception) {
            Log.e(LOG_TAG, "Error in sendUpdatedSettings", e)
            Either.Left(
                ErrorResponse(1000, e.message, e),
            )
        }
    }

    private suspend fun applySettings(settings: Map<String, String>) {
        val excluded = repository.excludedSyncSettingKeys.value
        for ((key, value) in settings) {
            if (key in excluded) {
                continue
            }
            try {
                opmlParserHandler.saveSetting(key, value)
            } catch (e: Exception) {
                Log.e(LOG_TAG, "Failed to apply synced setting: $key", e)
            }
        }
    }

    /**
     * Fetches and decrypts the current remote settings blob, used to merge on send.
     * Returns null only when the server does not support settings sync (404); an empty map
     * means there is simply no remote blob yet.
     */
    private suspend fun fetchRemoteSettingsMap(
        syncRemote: SyncRemote,
        feederSync: FeederSync,
        secretKey: SecretKeys,
    ): Map<String, String>? =
        feederSync
            .getSettings(
                syncChainId = syncRemote.syncChainId,
                currentDeviceId = syncRemote.deviceId,
            ).toEither()
            .fold(
                { error -> if (error.code == 404) null else emptyMap() },
                { response ->
                    runCatching {
                        settingsAdapter.fromJson(
                            AesCbcWithIntegrity.decryptString(response.encrypted, secretKeys = secretKey),
                        )?.settings
                    }.getOrNull() ?: emptyMap()
                },
            )

    /** Fetches and decrypts the current remote feeds, used to preserve excluded feeds when merging on send. */
    private suspend fun fetchRemoteFeeds(
        syncRemote: SyncRemote,
        feederSync: FeederSync,
        secretKey: SecretKeys,
    ): List<EncryptedFeed> =
        feederSync
            .getFeeds(
                syncChainId = syncRemote.syncChainId,
                currentDeviceId = syncRemote.deviceId,
            ).toEither()
            .fold(
                { emptyList() },
                { response ->
                    runCatching {
                        feedsAdapter.fromJson(
                            AesCbcWithIntegrity.decryptString(response.encrypted, secretKeys = secretKey),
                        )?.feeds
                    }.getOrNull() ?: emptyList()
                },
            )

    private suspend fun ErrorResponse.leaveChainIfKickedOutElseLog() {
        Log.e(LOG_TAG, "leaveChainIfKickedOutElseLog: $code, $body", throwable)
        if (body?.contains(DEVICE_NOT_REGISTERED, ignoreCase = true) == true) {
            // this device has been removed from the chain from another device
            leave()
        }
    }

    /**
     * Settings sync is optional and only available on servers that implement the /settings endpoint.
     * A 404 means the server (e.g. the stock public server) doesn't support it, so just skip silently
     * instead of treating it as a fatal sync error.
     */
    private suspend fun ErrorResponse.ignoreIfSettingsUnsupportedElseHandle() {
        if (code == 404) {
            logDebug(LOG_TAG, "Settings sync not supported by this server (404), skipping")
        } else {
            leaveChainIfKickedOutElseLog()
        }
    }

    /**
     * Test-only helper that bypasses the normal [initialize] flow and directly sets the
     * underlying [FeederSync] client and encryption key.
     */
    @VisibleForTesting
    internal fun initForTesting(
        feederSync: FeederSync,
        secretKey: SecretKeys,
    ) {
        this.feederSync = feederSync
        this.secretKey = secretKey
    }

    companion object {
        private const val LOG_TAG = "FEEDER_REST_CLIENT"
        private const val DEVICE_NOT_REGISTERED = "Device not registered"
    }
}

fun Any.asWeakETagValue() = "W/\"$this\""

fun <T> Response<T>.toEither(): Either<ErrorResponse, T> =
    try {
        if (isSuccessful) {
            body()?.let { Either.Right(it) }
                ?: Either.Left(
                    ErrorResponse(
                        code = 998,
                        body = "No body but success",
                    ),
                )
        } else {
            Either.Left(
                ErrorResponse(
                    code = code(),
                    body = errorBody()?.string(),
                ),
            )
        }
    } catch (e: Exception) {
        Either.Left(
            ErrorResponse(
                code = 999,
                body = e.message,
                throwable = e,
            ),
        )
    }

data class ErrorResponse(
    val code: Int,
    val body: String? = null,
    val throwable: Throwable? = null,
)
