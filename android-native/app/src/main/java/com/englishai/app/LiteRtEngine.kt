package com.englishai.app

import android.content.Context
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.Message
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.runBlocking

/**
 * LiteRT-LM owner.
 * Stability-first configuration: CPU backend avoids GPU-driver memory spikes on
 * mid-range phones. Keep only a bounded conversation window to limit KV-cache use.
 */
class LiteRtEngine(private val context: Context) {
    @Volatile private var engine: Engine? = null
    @Volatile private var conversation: Conversation? = null

    @Synchronized fun load(path: String) {
        close()
        // Deliberately use CPU first. A GPU driver failure can destabilize the whole
        // device, while CPU is supported by LiteRT-LM and is safer as a baseline.
        val candidate = Engine(
            EngineConfig(
                modelPath = path,
                backend = Backend.CPU(),
                cacheDir = context.cacheDir.absolutePath
            )
        )
        try {
            candidate.initialize()
            engine = candidate
        } catch (e: Exception) {
            // An Engine whose initialize() failed may not be safely closable in all
            // LiteRT-LM versions; swallow cleanup errors and propagate the real error.
            try { candidate.close() } catch (_: Exception) {}
            throw e
        }
    }

    fun generate(system: String, messages: List<Pair<String, String>>, onToken: (String) -> Unit) = runBlocking {
        val e = checkNotNull(engine) { "Load a compatible .litertlm model in Settings first." }
        val lastIndex = messages.indexOfLast { it.first == "user" }
        require(lastIndex >= 0) { "No user message was provided." }

        // Limit prompt size and history so the conversation KV cache does not grow
        // without bound during a long session. Keep the most recent 8 messages.
        val bounded = messages.take(lastIndex).takeLast(8).mapNotNull { (role, rawText) ->
            val text = rawText.take(1200)
            when (role) {
                "user" -> Message.user(text)
                "model", "assistant" -> Message.model(text)
                else -> null
            }
        }
        val safeSystem = system.take(1500)
        val config = ConversationConfig(
            systemInstruction = Contents.of(safeSystem),
            initialMessages = bounded
        )
        val c = e.createConversation(config)
        conversation = c
        try {
            c.sendMessageAsync(messages[lastIndex].second.take(1200)).collect { chunk ->
                onToken(chunk.toString())
            }
        } finally {
            conversation = null
            try { c.close() } catch (_: Exception) {}
        }
    }

    fun cancel() { try { conversation?.cancelProcess() } catch (_: Exception) {} }

    @Synchronized fun close() {
        try { conversation?.close() } catch (_: Exception) {}
        conversation = null
        try { engine?.close() } catch (_: Exception) {}
        engine = null
    }
}
