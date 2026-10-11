package com.ortalby.aphasiakeyboard

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.SamplerConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

class ModelMissingException : Exception("לא נבחר קובץ מודל")

/** The model file is shorter than the published file: the download did not finish. */
class ModelIncompleteException(val actual: Long, val expected: Long) :
    Exception("קובץ המודל לא הורד עד הסוף ($actual מתוך $expected בתים)")

/** The runtime could not read the model file. */
class ModelCorruptException(cause: Throwable) : Exception(cause.message, cause)

/** Exact sizes of the published model files (Hugging Face, litert-community/gemma-4-E2B-it-litert-lm). */
object ModelFiles {
    val KNOWN_SIZES = mapOf(
        "gemma-4-E2B-it.litertlm" to 2_588_147_712L,
        "gemma-4-E2B-it-gpu.litertlm" to 2_008_432_640L,
    )

    sealed class Check {
        data object Ok : Check()
        data class Incomplete(val actual: Long, val expected: Long) : Check()
        data object UnknownFile : Check()
    }

    fun check(file: File): Check {
        val expected = KNOWN_SIZES[file.name] ?: return Check.UnknownFile
        val actual = file.length()
        return if (actual == expected) Check.Ok else Check.Incomplete(actual, expected)
    }
}

/**
 * Runs Gemma on the phone with LiteRT-LM. One engine for the whole app.
 * The engine is released after a few idle minutes so the model's memory is returned to the phone.
 */
object LlmManager {
    private const val IDLE_RELEASE_MS = 5 * 60_000L

    private val mutex = Mutex()
    private var engine: Engine? = null
    private var loadedKey: String? = null

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val releaseRunnable = Runnable { scope.launch { release() } }

    @Volatile
    var isLoaded: Boolean = false
        private set

    suspend fun generate(
        context: Context,
        systemInstruction: String,
        prompt: String,
        maxOutputTokens: Int = 300,
    ): String = withContext(Dispatchers.IO) {
        mutex.withLock {
            mainHandler.removeCallbacks(releaseRunnable)
            try {
                val eng = ensureEngine(context.applicationContext)
                val config = ConversationConfig(
                    systemInstruction = Contents.of(systemInstruction),
                    samplerConfig = SamplerConfig(topK = 40, topP = 0.95, temperature = 0.7),
                    maxOutputToken = maxOutputTokens,
                )
                eng.createConversation(config).use { conversation ->
                    conversation.sendMessage(prompt).toString().trim()
                }
            } finally {
                mainHandler.postDelayed(releaseRunnable, IDLE_RELEASE_MS)
            }
        }
    }

    private fun ensureEngine(context: Context): Engine {
        val prefs = AppPrefs(context)
        val path = prefs.modelPath ?: throw ModelMissingException()
        val file = File(path)
        if (!file.isFile) throw ModelMissingException()
        val check = ModelFiles.check(file)
        if (check is ModelFiles.Check.Incomplete) throw ModelIncompleteException(check.actual, check.expected)

        val key = "$path|gpu=${prefs.useGpu}"
        engine?.let { current ->
            if (loadedKey == key) return current
            current.close()
            engine = null
            loadedKey = null
            isLoaded = false
        }

        val newEngine = Engine(
            EngineConfig(
                modelPath = path,
                backend = if (prefs.useGpu) Backend.GPU() else Backend.CPU(),
                maxNumTokens = 2048,
                cacheDir = context.cacheDir.absolutePath,
            )
        )
        try {
            newEngine.initialize()
        } catch (e: Exception) {
            // A failed initialize leaves no native engine behind, so there is nothing to close.
            if (e.message?.contains("flatbuffer", ignoreCase = true) == true) throw ModelCorruptException(e)
            throw e
        }
        engine = newEngine
        loadedKey = key
        isLoaded = true
        return newEngine
    }

    suspend fun release() {
        mutex.withLock {
            engine?.close()
            engine = null
            loadedKey = null
            isLoaded = false
        }
    }
}
