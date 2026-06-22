package com.nononsenseapps.feeder.ui.compose.sync

import androidx.compose.runtime.Immutable
import androidx.lifecycle.viewModelScope
import com.nononsenseapps.feeder.archmodel.Repository
import com.nononsenseapps.feeder.archmodel.UserSettings
import com.nononsenseapps.feeder.base.DIAwareViewModel
import com.nononsenseapps.feeder.db.room.Feed
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import org.kodein.di.DI
import org.kodein.di.instance

/**
 * Backs the fine-grained sync selection tree (Settings/Feeds -> categories/tags -> individual
 * keys/feeds). All selection is a per-device local preference - it is never synced.
 */
class SyncSelectionViewModel(
    di: DI,
) : DIAwareViewModel(di) {
    private val repository: Repository by instance()

    private val feeds = MutableStateFlow<List<Feed>>(emptyList())

    private val _viewState = MutableStateFlow(SyncSelectionViewState())
    val viewState: StateFlow<SyncSelectionViewState>
        get() = _viewState.asStateFlow()

    init {
        viewModelScope.launch {
            feeds.value = repository.getFeedsOrderedByUrl()
        }
        viewModelScope.launch {
            combine(
                repository.syncSettingsEnabled,
                repository.syncFeedsEnabled,
                repository.excludedSyncSettingKeys,
                repository.excludedSyncFeedUrls,
                feeds,
            ) { syncSettings, syncFeeds, excludedKeys, excludedUrls, feedList ->
                buildViewState(syncSettings, syncFeeds, excludedKeys, excludedUrls, feedList)
            }.collect { _viewState.value = it }
        }
    }

    fun setSyncSettingsEnabled(value: Boolean) = repository.setSyncSettingsEnabled(value)

    fun setSyncFeedsEnabled(value: Boolean) = repository.setSyncFeedsEnabled(value)

    fun setSettingSynced(
        key: String,
        synced: Boolean,
    ) = repository.setSettingKeysSynced(listOf(key), synced)

    fun setCategorySynced(
        category: SyncSettingCategory,
        synced: Boolean,
    ) = repository.setSettingKeysSynced(
        UserSettings.entries.filter { it.syncCategory() == category }.map { it.key },
        synced,
    )

    fun setFeedSynced(
        url: String,
        synced: Boolean,
    ) = repository.setFeedUrlsSynced(listOf(url), synced)

    fun setTagSynced(
        tag: String,
        synced: Boolean,
    ) = repository.setFeedUrlsSynced(
        feeds.value.filter { it.tag == tag }.map { it.url.toString() },
        synced,
    )

    private fun buildViewState(
        syncSettings: Boolean,
        syncFeeds: Boolean,
        excludedKeys: Set<String>,
        excludedUrls: Set<String>,
        feedList: List<Feed>,
    ): SyncSelectionViewState {
        val grouped = UserSettings.entries.groupBy { it.syncCategory() }
        val categories =
            SyncSettingCategory.entries.mapNotNull { category ->
                grouped[category]?.let { entries ->
                    SyncSettingCategoryNode(
                        category = category,
                        settings =
                            entries
                                .map { SyncSettingLeaf(it.key, it.syncLabel(), it.key !in excludedKeys) }
                                .sortedBy { it.label.lowercase() },
                    )
                }
            }

        val tags =
            feedList
                .groupBy { it.tag }
                .toList()
                .sortedWith(compareBy({ it.first.isBlank() }, { it.first.lowercase() }))
                .map { (tag, tagFeeds) ->
                    SyncFeedTagNode(
                        tag = tag,
                        feeds =
                            tagFeeds
                                .sortedBy { it.displayTitle.lowercase() }
                                .map { SyncFeedLeaf(it.url.toString(), it.displayTitle, it.url.toString() !in excludedUrls) },
                    )
                }

        return SyncSelectionViewState(
            syncSettingsEnabled = syncSettings,
            syncFeedsEnabled = syncFeeds,
            settingCategories = categories,
            feedTags = tags,
        )
    }
}

enum class TriState { ALL, SOME, NONE }

private fun triStateOf(
    synced: Int,
    total: Int,
): TriState =
    when {
        total == 0 || synced == 0 -> TriState.NONE
        synced == total -> TriState.ALL
        else -> TriState.SOME
    }

@Immutable
data class SyncSelectionViewState(
    val syncSettingsEnabled: Boolean = true,
    val syncFeedsEnabled: Boolean = true,
    val settingCategories: List<SyncSettingCategoryNode> = emptyList(),
    val feedTags: List<SyncFeedTagNode> = emptyList(),
)

@Immutable
data class SyncSettingCategoryNode(
    val category: SyncSettingCategory,
    val settings: List<SyncSettingLeaf>,
) {
    val triState: TriState get() = triStateOf(settings.count { it.synced }, settings.size)
}

@Immutable
data class SyncSettingLeaf(
    val key: String,
    val label: String,
    val synced: Boolean,
)

@Immutable
data class SyncFeedTagNode(
    val tag: String,
    val feeds: List<SyncFeedLeaf>,
) {
    val triState: TriState get() = triStateOf(feeds.count { it.synced }, feeds.size)
}

@Immutable
data class SyncFeedLeaf(
    val url: String,
    val title: String,
    val synced: Boolean,
)
