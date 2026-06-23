package com.nononsenseapps.feeder.aicore

import android.app.Application
import android.util.Log
import com.arm.aichat.AiChat
import com.arm.aichat.InferenceEngine
import com.nononsenseapps.feeder.openai.OpenAIApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.kodein.di.DI
import org.kodein.di.DIAware
import org.kodein.di.instance
import java.io.File
import java.time.Instant

/**
 * On-device summarization backed by llama.cpp (GGUF models).
 *
 * Unlike the ML Kit / AICore path (Play-only, proprietary), this is fully open source and the
 * native library is built from the bundled `llama.cpp` git submodule, so it ships in BOTH the
 * F-Droid and Play flavors.
 *
 * The underlying JNI engine ([InferenceEngine]) is a process-wide singleton that can only hold a
 * single model at a time, so every call here is serialized through [mutex] and the currently
 * loaded model is cached to avoid re-loading it on every article.
 *
 * Custom app-wide / per-tag / per-feed prompts are honoured: the resolved instruction is passed in
 * as [customInstruction] and prepended to the article text.
 */
class LlamaCppSummarizer(
    override val di: DI,
) : DIAware {
    private val application: Application by instance()

    private val mutex = Mutex()

    @Volatile
    private var loadedModelPath: String? = null

    private val engine: InferenceEngine by lazy { AiChat.getInferenceEngine(application) }

    /** True if [modelPath] points at a readable file (a .gguf model the user has selected). */
    fun isModelAvailable(modelPath: String): Boolean =
        modelPath.isNotBlank() &&
            File(modelPath).let { it.isFile && it.canRead() }

    suspend fun summarize(
        content: String,
        customInstruction: String,
        modelPath: String,
        appLang: String,
    ): OpenAIApi.SummaryResult =
        mutex.withLock {
            if (!isModelAvailable(modelPath)) {
                return@withLock OpenAIApi.SummaryResult.Error(
                    content = "No on-device model selected. Pick a .gguf model file in the AI settings.",
                )
            }

            try {
                ensureModelLoaded(modelPath)

                val instruction = customInstruction.trim().ifBlank { OpenAIApi.DEFAULT_SUMMARY_INSTRUCTION }
                val prompt =
                    buildString {
                        append(instruction)
                        if (appLang.isNotBlank()) {
                            append("\nWrite the summary in this language (ISO code): ")
                            append(appLang)
                            append('.')
                        }
                        append("\n\nArticle:\n")
                        append(content.take(MAX_INPUT_CHARS))
                    }

                val builder = StringBuilder()
                engine.sendUserPrompt(prompt, PREDICT_LENGTH).collect { token ->
                    builder.append(token)
                }

                val summary = builder.toString().trim()
                if (summary.isEmpty()) {
                    OpenAIApi.SummaryResult.Error(content = "The on-device model returned an empty summary.")
                } else {
                    OpenAIApi.SummaryResult.Success(
                        id = "llamacpp",
                        created = Instant.now().epochSecond,
                        model = File(modelPath).name,
                        content = summary,
                        promptTokens = 0,
                        completeTokens = 0,
                        totalTokens = 0,
                        detectedLanguage = "",
                    )
                }
            } catch (e: Exception) {
                // Drop the cached model so the next attempt starts from a clean state.
                loadedModelPath = null
                Log.e(TAG, "On-device (llama.cpp) summarization failed", e)
                OpenAIApi.SummaryResult.Error(
                    content = e.message ?: "On-device summarization failed.",
                )
            }
        }

    private suspend fun ensureModelLoaded(modelPath: String) {
        // The engine initializes its native backend asynchronously - wait for that to settle.
        val state =
            engine.state.first { s ->
                s !is InferenceEngine.State.Uninitialized && s !is InferenceEngine.State.Initializing
            }
        if (state is InferenceEngine.State.Error) {
            // Reset a previously failed engine back to a usable state.
            runCatching { engine.cleanUp() }
            loadedModelPath = null
        }

        if (loadedModelPath == modelPath) {
            return
        }

        // A different (or no) model is currently loaded - swap it out.
        if (loadedModelPath != null) {
            runCatching { engine.cleanUp() }
            loadedModelPath = null
        }
        engine.loadModel(modelPath)
        loadedModelPath = modelPath
    }

    companion object {
        private const val TAG = "LlamaCppSummarizer"

        // Small on-device models have small context windows; keep the input modest.
        private const val MAX_INPUT_CHARS = 8000
        private const val PREDICT_LENGTH = 512
    }
}
