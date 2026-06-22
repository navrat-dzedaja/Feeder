package com.nononsenseapps.feeder.aicore

import android.app.Application
import android.util.Log
import androidx.concurrent.futures.await
import com.google.mlkit.genai.common.DownloadCallback
import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.common.GenAiException
import com.google.mlkit.genai.prompt.Generation
import com.google.mlkit.genai.prompt.GenerativeModel
import com.google.mlkit.genai.summarization.Summarization
import com.google.mlkit.genai.summarization.SummarizationRequest
import com.google.mlkit.genai.summarization.Summarizer
import com.google.mlkit.genai.summarization.SummarizerOptions
import com.nononsenseapps.feeder.ApplicationCoroutineScope
import com.nononsenseapps.feeder.openai.OpenAIApi
import kotlinx.coroutines.launch
import org.kodein.di.DI
import org.kodein.di.DIAware
import org.kodein.di.instance
import java.time.Instant
import java.util.Locale

/**
 * Google Play build implementation: runs summaries on-device with Gemini Nano via AICore using the
 * ML Kit GenAI APIs.
 *
 * - [OnDeviceAiMode.PROMPT] uses the GenAI Prompt API (free-form), so the resolved custom prompt
 *   (app-wide / per-tag / per-feed) is applied.
 * - [OnDeviceAiMode.SUMMARY] uses the GenAI Summarization API (fixed bullet output); custom prompts
 *   are ignored, and only English/Japanese/Korean are supported.
 */
class OnDeviceSummarizerImpl(
    override val di: DI,
) : OnDeviceSummarizer,
    DIAware {
    private val application: Application by instance()
    private val applicationScope: ApplicationCoroutineScope by instance()

    override val isSupportedInBuild: Boolean = true

    private val promptModel: GenerativeModel by lazy { Generation.getClient() }

    private fun newSummarizer(language: Int): Summarizer =
        Summarization.getClient(
            SummarizerOptions
                .builder(application)
                .setInputType(SummarizerOptions.InputType.ARTICLE)
                .setOutputType(SummarizerOptions.OutputType.THREE_BULLETS)
                .setLanguage(language)
                .build(),
        )

    override suspend fun checkStatus(mode: OnDeviceAiMode): OnDeviceAiStatus =
        runCatching {
            when (mode) {
                OnDeviceAiMode.PROMPT -> mapStatus(promptModel.checkStatus())
                OnDeviceAiMode.SUMMARY -> {
                    val summarizer = newSummarizer(SummarizerOptions.Language.ENGLISH)
                    try {
                        mapStatus(summarizer.checkFeatureStatus().await())
                    } finally {
                        summarizer.close()
                    }
                }
            }
        }.getOrElse {
            Log.e(LOG_TAG, "checkStatus failed", it)
            OnDeviceAiStatus.UNAVAILABLE
        }

    override fun requestDownload(mode: OnDeviceAiMode) {
        applicationScope.launch {
            runCatching {
                when (mode) {
                    OnDeviceAiMode.PROMPT -> promptModel.download().collect { }
                    OnDeviceAiMode.SUMMARY -> {
                        val summarizer = newSummarizer(SummarizerOptions.Language.ENGLISH)
                        summarizer.downloadFeature(
                            object : DownloadCallback {
                                override fun onDownloadStarted(bytesToDownload: Long) = Unit

                                override fun onDownloadProgress(totalBytesDownloaded: Long) = Unit

                                override fun onDownloadCompleted() = summarizer.close()

                                override fun onDownloadFailed(e: GenAiException) = summarizer.close()
                            },
                        )
                    }
                }
            }.onFailure { Log.e(LOG_TAG, "requestDownload failed", it) }
        }
    }

    override suspend fun summarize(
        content: String,
        mode: OnDeviceAiMode,
        customInstruction: String,
        appLang: String,
    ): OpenAIApi.SummaryResult {
        val text = content.take(MAX_INPUT_CHARS)
        return try {
            when (checkStatus(mode)) {
                OnDeviceAiStatus.AVAILABLE ->
                    when (mode) {
                        OnDeviceAiMode.PROMPT -> runPrompt(text, customInstruction)
                        OnDeviceAiMode.SUMMARY -> runSummary(text, appLang)
                    }

                OnDeviceAiStatus.DOWNLOADABLE -> {
                    requestDownload(mode)
                    OpenAIApi.SummaryResult.Error(
                        content = "The on-device model is downloading. Please try again in a moment.",
                    )
                }

                OnDeviceAiStatus.DOWNLOADING ->
                    OpenAIApi.SummaryResult.Error(content = "The on-device model is still downloading. Please retry shortly.")

                OnDeviceAiStatus.UNAVAILABLE ->
                    OpenAIApi.SummaryResult.Error(content = "On-device AI is not supported on this device.")

                OnDeviceAiStatus.UNSUPPORTED_BUILD ->
                    OpenAIApi.SummaryResult.Error(content = "On-device AI is not available in this build.")
            }
        } catch (e: Exception) {
            Log.e(LOG_TAG, "on-device summarize failed", e)
            OpenAIApi.SummaryResult.Error(content = e.message ?: "On-device summarization failed.")
        }
    }

    private suspend fun runPrompt(
        text: String,
        customInstruction: String,
    ): OpenAIApi.SummaryResult {
        val instruction = customInstruction.trim().ifBlank { OpenAIApi.DEFAULT_SUMMARY_INSTRUCTION }
        val prompt = "$instruction\n\nSummarize the following article:\n\n$text"

        val builder = StringBuilder()
        promptModel.generateContentStream(prompt).collect { chunk ->
            chunk.candidates
                .firstOrNull()
                ?.text
                ?.let { builder.append(it) }
        }
        val summary = builder.toString().trim()
        return if (summary.isEmpty()) {
            OpenAIApi.SummaryResult.Error(content = "The on-device model returned an empty summary.")
        } else {
            success(summary, model = "gemini-nano (prompt)")
        }
    }

    private suspend fun runSummary(
        text: String,
        appLang: String,
    ): OpenAIApi.SummaryResult {
        val summarizer = newSummarizer(languageFor(appLang))
        return try {
            val request = SummarizationRequest.builder(text).build()
            val summary =
                summarizer
                    .runInference(request)
                    .await()
                    .summary
                    .orEmpty()
                    .trim()
            if (summary.isEmpty()) {
                OpenAIApi.SummaryResult.Error(content = "The on-device model returned an empty summary.")
            } else {
                success(summary, model = "gemini-nano (summary)")
            }
        } finally {
            summarizer.close()
        }
    }

    private fun success(
        content: String,
        model: String,
    ): OpenAIApi.SummaryResult.Success =
        OpenAIApi.SummaryResult.Success(
            id = "on-device",
            created = Instant.now().epochSecond,
            model = model,
            content = content,
            promptTokens = 0,
            completeTokens = 0,
            totalTokens = 0,
            detectedLanguage = "",
        )

    private fun mapStatus(status: Int): OnDeviceAiStatus =
        when (status) {
            FeatureStatus.AVAILABLE -> OnDeviceAiStatus.AVAILABLE
            FeatureStatus.DOWNLOADABLE -> OnDeviceAiStatus.DOWNLOADABLE
            FeatureStatus.DOWNLOADING -> OnDeviceAiStatus.DOWNLOADING
            else -> OnDeviceAiStatus.UNAVAILABLE
        }

    private fun languageFor(appLang: String): Int =
        when (appLang.lowercase(Locale.ROOT).take(2)) {
            "ja", "jp" -> SummarizerOptions.Language.JAPANESE
            "ko" -> SummarizerOptions.Language.KOREAN
            else -> SummarizerOptions.Language.ENGLISH
        }

    companion object {
        private const val LOG_TAG = "FEEDER_ONDEVICE_AI"

        // ML Kit GenAI input limit is ~4000 tokens (~3000 English words).
        private const val MAX_INPUT_CHARS = 12000
    }
}
