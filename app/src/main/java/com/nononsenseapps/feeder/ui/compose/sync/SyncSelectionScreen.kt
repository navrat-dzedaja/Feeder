package com.nononsenseapps.feeder.ui.compose.sync

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TriStateCheckbox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nononsenseapps.feeder.R
import com.nononsenseapps.feeder.ui.compose.theme.LocalDimens
import com.nononsenseapps.feeder.ui.compose.theme.PreviewTheme
import com.nononsenseapps.feeder.ui.compose.theme.SensibleTopAppBar

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SyncSelectionScreen(
    onNavigateUp: () -> Unit,
    viewModel: SyncSelectionViewModel,
    modifier: Modifier = Modifier,
) {
    val viewState by viewModel.viewState.collectAsStateWithLifecycle()
    val scrollBehavior = TopAppBarDefaults.enterAlwaysScrollBehavior()

    Scaffold(
        modifier =
            modifier
                .nestedScroll(scrollBehavior.nestedScrollConnection)
                .windowInsetsPadding(WindowInsets.navigationBars.only(WindowInsetsSides.Horizontal)),
        contentWindowInsets = WindowInsets.statusBars,
        topBar = {
            SensibleTopAppBar(
                scrollBehavior = scrollBehavior,
                title = stringResource(R.string.sync_selection_title),
                navigationIcon = {
                    IconButton(onClick = onNavigateUp) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.go_back),
                        )
                    }
                },
            )
        },
    ) { padding ->
        SyncSelectionContent(
            viewState = viewState,
            onSyncSettingsChange = viewModel::setSyncSettingsEnabled,
            onSyncFeedsChange = viewModel::setSyncFeedsEnabled,
            onCategoryChange = viewModel::setCategorySynced,
            onSettingChange = viewModel::setSettingSynced,
            onTagChange = viewModel::setTagSynced,
            onFeedChange = viewModel::setFeedSynced,
            modifier =
                Modifier
                    .padding(padding)
                    .navigationBarsPadding(),
        )
    }
}

@Composable
fun SyncSelectionContent(
    viewState: SyncSelectionViewState,
    onSyncSettingsChange: (Boolean) -> Unit,
    onSyncFeedsChange: (Boolean) -> Unit,
    onCategoryChange: (SyncSettingCategory, Boolean) -> Unit,
    onSettingChange: (String, Boolean) -> Unit,
    onTagChange: (String, Boolean) -> Unit,
    onFeedChange: (String, Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val dimens = LocalDimens.current
    val scrollState = rememberScrollState()
    // Collapsed by default - the fine-grained tree is hidden behind expand/collapse.
    val expanded = remember { mutableStateMapOf<String, Boolean>() }

    fun toggleExpanded(id: String) {
        expanded[id] = !(expanded[id] ?: false)
    }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier =
            modifier
                .fillMaxWidth()
                .verticalScroll(scrollState),
    ) {
        Column(
            modifier =
                Modifier
                    .width(dimens.maxContentWidth)
                    .padding(horizontal = dimens.margin, vertical = 8.dp),
        ) {
            Text(
                text = stringResource(R.string.sync_selection_description),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
                modifier = Modifier.padding(bottom = 8.dp),
            )

            // ----- Settings root -----
            MasterRow(
                title = stringResource(R.string.sync_selection_settings),
                enabled = viewState.syncSettingsEnabled,
                onEnabledChange = onSyncSettingsChange,
                expanded = expanded["settings"] ?: false,
                onExpandToggle = { toggleExpanded("settings") },
            )
            if (expanded["settings"] == true) {
                for (category in viewState.settingCategories) {
                    val catId = "cat:${category.category.name}"
                    GroupRow(
                        title = stringResource(category.category.titleRes),
                        triState = category.triState,
                        enabled = viewState.syncSettingsEnabled,
                        onToggle = { onCategoryChange(category.category, it) },
                        expanded = expanded[catId] ?: false,
                        onExpandToggle = { toggleExpanded(catId) },
                    )
                    if (expanded[catId] == true) {
                        for (leaf in category.settings) {
                            LeafRow(
                                title = leaf.label,
                                checked = leaf.synced,
                                enabled = viewState.syncSettingsEnabled,
                                onCheckedChange = { onSettingChange(leaf.key, it) },
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.padding(top = 8.dp))

            // ----- Feeds root -----
            MasterRow(
                title = stringResource(R.string.sync_selection_feeds),
                enabled = viewState.syncFeedsEnabled,
                onEnabledChange = onSyncFeedsChange,
                expanded = expanded["feeds"] ?: false,
                onExpandToggle = { toggleExpanded("feeds") },
            )
            if (expanded["feeds"] == true) {
                if (viewState.feedTags.isEmpty()) {
                    Text(
                        text = stringResource(R.string.sync_selection_no_feeds),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline,
                        modifier = Modifier.padding(start = 24.dp, top = 8.dp, bottom = 8.dp),
                    )
                }
                for (tagNode in viewState.feedTags) {
                    val tagLabel = tagNode.tag.ifBlank { stringResource(R.string.sync_selection_untagged) }
                    val tagId = "tag:${tagNode.tag}"
                    GroupRow(
                        title = tagLabel,
                        triState = tagNode.triState,
                        enabled = viewState.syncFeedsEnabled,
                        onToggle = { onTagChange(tagNode.tag, it) },
                        expanded = expanded[tagId] ?: false,
                        onExpandToggle = { toggleExpanded(tagId) },
                    )
                    if (expanded[tagId] == true) {
                        for (feed in tagNode.feeds) {
                            LeafRow(
                                title = feed.title,
                                checked = feed.synced,
                                enabled = viewState.syncFeedsEnabled,
                                onCheckedChange = { onFeedChange(feed.url, it) },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MasterRow(
    title: String,
    enabled: Boolean,
    onEnabledChange: (Boolean) -> Unit,
    expanded: Boolean,
    onExpandToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier =
            modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp)
                .clickable(onClick = onExpandToggle),
    ) {
        ExpandIcon(expanded = expanded)
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.weight(1f),
        )
        Switch(checked = enabled, onCheckedChange = onEnabledChange)
    }
}

@Composable
private fun GroupRow(
    title: String,
    triState: TriState,
    enabled: Boolean,
    onToggle: (Boolean) -> Unit,
    expanded: Boolean,
    onExpandToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier =
            modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .padding(start = 16.dp)
                .clickable(onClick = onExpandToggle),
    ) {
        TriStateCheckbox(
            state =
                when (triState) {
                    TriState.ALL -> ToggleableState.On
                    TriState.NONE -> ToggleableState.Off
                    TriState.SOME -> ToggleableState.Indeterminate
                },
            enabled = enabled,
            // Clicking the tri-state selects all when not already all-on, else clears.
            onClick = { onToggle(triState != TriState.ALL) },
        )
        Text(
            text = title,
            style = MaterialTheme.typography.bodyLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        ExpandIcon(expanded = expanded)
    }
}

@Composable
private fun LeafRow(
    title: String,
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Start,
        modifier =
            modifier
                .fillMaxWidth()
                .heightIn(min = 44.dp)
                .padding(start = 40.dp)
                .clickable(enabled = enabled) { onCheckedChange(!checked) },
    ) {
        Checkbox(checked = checked, enabled = enabled, onCheckedChange = onCheckedChange)
        Text(
            text = title,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun ExpandIcon(expanded: Boolean) {
    Icon(
        imageVector = if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
        contentDescription = null,
    )
}

@androidx.compose.ui.tooling.preview.Preview
@Composable
private fun SyncSelectionPreview() {
    PreviewTheme {
        SyncSelectionContent(
            viewState =
                SyncSelectionViewState(
                    syncSettingsEnabled = true,
                    syncFeedsEnabled = true,
                    settingCategories =
                        listOf(
                            SyncSettingCategoryNode(
                                category = SyncSettingCategory.AI,
                                settings =
                                    listOf(
                                        SyncSettingLeaf("pref_openai_key", "Openai key", true),
                                        SyncSettingLeaf("pref_summary_prompt", "Summary prompt", false),
                                    ),
                            ),
                        ),
                    feedTags =
                        listOf(
                            SyncFeedTagNode(
                                tag = "News",
                                feeds = listOf(SyncFeedLeaf("https://nyt.com", "New York Times", true)),
                            ),
                        ),
                ),
            onSyncSettingsChange = {},
            onSyncFeedsChange = {},
            onCategoryChange = { _, _ -> },
            onSettingChange = { _, _ -> },
            onTagChange = { _, _ -> },
            onFeedChange = { _, _ -> },
        )
    }
}
