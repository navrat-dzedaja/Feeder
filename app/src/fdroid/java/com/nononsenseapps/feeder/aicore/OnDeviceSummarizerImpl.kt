package com.nononsenseapps.feeder.aicore

import com.nononsenseapps.feeder.openai.OpenAIApi
import org.kodein.di.DI
import org.kodein.di.DIAware

/**
 * FOSS / F-Droid build stub.
 *
 * The ML Kit GenAI / AICore libraries are proprietary and are bundled only in the Google Play
 * build, so on-device summarization is unavailable here. The Play flavor provides a real
 * implementation with the same fully-qualified name in `src/play`.
 */
@Suppress("unused")
class OnDeviceSummarizerImpl(
    override val di: DI,
) : OnDeviceSummarizer,
    DIAware {
    override val isSupportedInBuild: Boolean = false

    override suspend fun checkStatus(mode: OnDeviceAiMode): OnDeviceAiStatus = OnDeviceAiStatus.UNSUPPORTED_BUILD

    override fun requestDownload(mode: OnDeviceAiMode) = Unit

    override suspend fun summarize(
        content: String,
        mode: OnDeviceAiMode,
        customInstruction: String,
        appLang: String,
    ): OpenAIApi.SummaryResult =
        OpenAIApi.SummaryResult.Error(
            content = "On-device AI is not available in this build. Install the Google Play build to use Gemini Nano.",
        )
}
