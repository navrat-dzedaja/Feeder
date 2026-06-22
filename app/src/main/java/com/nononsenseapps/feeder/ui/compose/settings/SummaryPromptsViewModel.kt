package com.nononsenseapps.feeder.ui.compose.settings

import androidx.compose.runtime.Immutable
import androidx.lifecycle.viewModelScope
import com.nononsenseapps.feeder.archmodel.Repository
import com.nononsenseapps.feeder.base.DIAwareViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import org.kodein.di.DI
import org.kodein.di.instance

/**
 * Backs the screen where users edit the app-wide and per-tag custom summary prompts.
 * Feed-specific prompts are edited in the Edit Feed screen instead.
 */
class SummaryPromptsViewModel(
    di: DI,
) : DIAwareViewModel(di) {
    private val repository: Repository by instance()

    private val _viewState = MutableStateFlow(SummaryPromptsViewState())
    val viewState: StateFlow<SummaryPromptsViewState>
        get() = _viewState.asStateFlow()

    fun setAppPrompt(value: String) {
        repository.setAppSummaryPrompt(value)
    }

    fun setTagPrompt(
        tag: String,
        value: String,
    ) {
        repository.setSummaryPromptForTag(tag, value)
    }

    init {
        viewModelScope.launch {
            combine(
                repository.appSummaryPrompt,
                repository.summaryPromptsByTag,
                repository.allTags,
            ) { appPrompt, byTag, tags ->
                SummaryPromptsViewState(
                    appPrompt = appPrompt,
                    tagPrompts =
                        tags
                            .filter { it.isNotBlank() }
                            .map { tag -> TagPrompt(tag = tag, prompt = byTag[tag].orEmpty()) },
                )
            }.collect { state ->
                _viewState.value = state
            }
        }
    }
}

@Immutable
data class SummaryPromptsViewState(
    val appPrompt: String = "",
    val tagPrompts: List<TagPrompt> = emptyList(),
)

@Immutable
data class TagPrompt(
    val tag: String,
    val prompt: String,
)
