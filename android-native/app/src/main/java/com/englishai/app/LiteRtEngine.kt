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
 * CPU backend is the conservative baseline for device stability.
 * A bounded token budget and reuse of the active conversation reduce repeated setup work.
 */
class LiteRtEngine(private val context: Context) {
    @Volatile private var engine: Engine? = null
    @Volatile private var conversation: Conversation? = null
    @Volatile private var cancelled = false
    private var conversationChat = ""
    private var conversationMessageCount = -1
    private var conversationTurns = 0
    private var historyKeep = 8

    @Synchronized fun load(path: String, totalRamBytes: Long) {
        close()
        val gb = totalRamBytes / (1024.0 * 1024.0 * 1024.0)
        val tokens = when { gb >= 7.0 -> 2048; gb >= 5.0 -> 1536; else -> 1024 }
        historyKeep = when { gb >= 7.0 -> 8; gb >= 5.0 -> 6; else -> 4 }
        val cache = java.io.File(context.filesDir, "litert-cache").apply { mkdirs() }
        val candidate = Engine(
            EngineConfig(
                modelPath = path,
                backend = Backend.CPU(),
                maxNumTokens = tokens,
                cacheDir = cache.absolutePath
            )
        )
        try {
            candidate.initialize()
            engine = candidate
        } catch (e: Exception) {
            try { candidate.close() } catch (_: Exception) {}
            throw e
        }
    }

    private fun dropConversation() {
        try { conversation?.close() } catch (_: Exception) {}
        conversation = null
        conversationChat = ""
        conversationMessageCount = -1
        conversationTurns = 0
    }

    /**
     * count is the current number of messages in the UI, including the newest user message.
     * Reuse only for the same chat and sequential messages; rebuild periodically to bound context.
     */
    fun generate(
        chat: String,
        count: Int,
        system: String,
        messages: List<Pair<String, String>>,
        onToken: (String) -> Unit
    ) = runBlocking {
        val e = checkNotNull(engine) { "Load a compatible .litertlm model in Settings first." }
        val lastIndex = messages.indexOfLast { it.first == "user" }
        require(lastIndex >= 0) { "No user message was provided." }
        cancelled = false

        val reuse = conversation != null &&
            chat.isNotEmpty() &&
            chat == conversationChat &&
            count == conversationMessageCount + 2 &&
            conversationTurns < 5

        if (!reuse) {
            val keep = if (conversationTurns >= 5) minOf(4, historyKeep) else historyKeep
            dropConversation()
            val bounded = messages.take(lastIndex).takeLast(keep).mapNotNull { (role, rawText) ->
                val text = rawText.take(1000)
                when (role) {
                    "user" -> Message.user(text)
                    "model", "assistant" -> Message.model(text)
                    else -> null
                }
            }
            conversation = e.createConversation(
                ConversationConfig(
                    systemInstruction = Contents.of(system.take(1200)),
                    initialMessages = bounded
                )
            )
            conversationChat = chat
            conversationTurns = 0
        }

        val c = checkNotNull(conversation)
        var completed = false
        try {
            c.sendMessageAsync(messages[lastIndex].second.take(1000)).collect { chunk ->
                if (!cancelled) onToken(chunk.toString())
            }
            completed = !cancelled
        } finally {
            if (completed) {
                conversationMessageCount = count
                conversationTurns++
            } else {
                dropConversation()
            }
        }
    }

    @Synchronized fun trimConversation() { dropConversation() }

    fun cancel() {
        cancelled = true
        try { conversation?.cancelProcess() } catch (_: Exception) {}
    }

    @Synchronized fun close() {
        dropConversation()
        try { engine?.close() } catch (_: Exception) {}
        engine = null
    }
}
