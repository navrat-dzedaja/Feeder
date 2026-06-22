package com.nononsenseapps.feeder.ui.compose.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nononsenseapps.feeder.R
import com.nononsenseapps.feeder.ui.compose.theme.LocalDimens
import com.nononsenseapps.feeder.ui.compose.theme.PreviewTheme
import com.nononsenseapps.feeder.ui.compose.theme.SensibleTopAppBar

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SummaryPromptsScreen(
    onNavigateUp: () -> Unit,
    viewModel: SummaryPromptsViewModel,
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
                title = stringResource(R.string.summary_prompts_title),
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
        SummaryPromptsContent(
            viewState = viewState,
            onAppPromptChange = viewModel::setAppPrompt,
            onTagPromptChange = viewModel::setTagPrompt,
            modifier =
                Modifier
                    .padding(padding)
                    .navigationBarsPadding(),
        )
    }
}

@Composable
fun SummaryPromptsContent(
    viewState: SummaryPromptsViewState,
    onAppPromptChange: (String) -> Unit,
    onTagPromptChange: (String, String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val dimens = LocalDimens.current
    val scrollState = rememberScrollState()
    val promptKeyboardOptions =
        KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, autoCorrectEnabled = true)

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier =
            modifier
                .fillMaxWidth()
                .verticalScroll(scrollState),
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier =
                Modifier
                    .width(dimens.maxContentWidth)
                    .padding(horizontal = dimens.margin, vertical = 8.dp),
        ) {
            Text(
                text = stringResource(R.string.summary_prompts_precedence_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
            )

            Header(R.string.summary_prompt_default_header, modifier = Modifier.padding(top = 8.dp))
            OutlinedTextField(
                value = viewState.appPrompt,
                onValueChange = onAppPromptChange,
                label = { Text(stringResource(R.string.summary_prompt_default_label)) },
                placeholder = { Text(stringResource(R.string.summary_prompt_hint)) },
                keyboardOptions = promptKeyboardOptions,
                minLines = 3,
                modifier = Modifier.fillMaxWidth(),
            )

            Header(R.string.summary_prompt_per_tag_header, modifier = Modifier.padding(top = 16.dp))
            if (viewState.tagPrompts.isEmpty()) {
                Text(
                    text = stringResource(R.string.summary_prompts_no_tags),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                )
            } else {
                for (tagPrompt in viewState.tagPrompts) {
                    OutlinedTextField(
                        value = tagPrompt.prompt,
                        onValueChange = { onTagPromptChange(tagPrompt.tag, it) },
                        label = { Text(tagPrompt.tag) },
                        placeholder = { Text(stringResource(R.string.summary_prompt_hint)) },
                        keyboardOptions = promptKeyboardOptions,
                        minLines = 2,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
}

@Preview
@Composable
private fun SummaryPromptsPreview() {
    PreviewTheme {
        SummaryPromptsContent(
            viewState =
                SummaryPromptsViewState(
                    appPrompt = "Summarize in three concise sentences.",
                    tagPrompts =
                        listOf(
                            TagPrompt(tag = "News", prompt = "Focus on what changed and why it matters."),
                            TagPrompt(tag = "Tech", prompt = ""),
                        ),
                ),
            onAppPromptChange = {},
            onTagPromptChange = { _, _ -> },
        )
    }
}
