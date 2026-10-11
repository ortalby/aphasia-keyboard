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
        if (!File(path).isFile) throw ModelMissingException()

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
        newEngine.initialize()
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
