package com.nononsenseapps.feeder.aicore

import com.nononsenseapps.feeder.openai.OpenAIApi

/**
 * Which on-device ML Kit GenAI API to use.
 *
 * The implementation is flavor-specific: the `play` build talks to Gemini Nano via AICore, while
 * the FOSS `fdroid` build ships a no-op stub (the ML Kit / AICore libraries are proprietary).
 */
enum class OnDeviceAiMode {
    /** ML Kit GenAI Prompt API — free-form prompts, so it honours the custom summary prompts. */
    PROMPT,

    /** ML Kit GenAI Summarization API — fixed bullet summaries; custom prompts are ignored. */
    SUMMARY,
}

enum class OnDeviceAiStatus {
    /** Ready to run inference. */
    AVAILABLE,

    /** Supported by the device, but the model still needs to be downloaded. */
    DOWNLOADABLE,

    /** The model is currently downloading. */
    DOWNLOADING,

    /** Not supported on this device (no AICore / unsupported hardware). */
    UNAVAILABLE,

    /** This build does not bundle on-device AI at all (FOSS / F-Droid build). */
    UNSUPPORTED_BUILD,
}

interface OnDeviceSummarizer {
    /** False on builds that don't bundle the proprietary ML Kit / AICore libraries. */
    val isSupportedInBuild: Boolean

    suspend fun checkStatus(mode: OnDeviceAiMode): OnDeviceAiStatus

    /** Kicks off the on-device model download if needed. No-op when unavailable/unsupported. */
    fun requestDownload(mode: OnDeviceAiMode)

    /**
     * Summarizes [content] on-device. For [OnDeviceAiMode.PROMPT] the [customInstruction]
     * (resolved app-wide / per-tag / per-feed prompt) is used; for [OnDeviceAiMode.SUMMARY] it is
     * ignored. Returns the same result type as the cloud path so the UI is shared.
     */
    suspend fun summarize(
        content: String,
        mode: OnDeviceAiMode,
        customInstruction: String,
        appLang: String,
    ): OpenAIApi.SummaryResult
}
