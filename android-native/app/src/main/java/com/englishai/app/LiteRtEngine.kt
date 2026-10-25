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
 * LiteRT-LM owner. Tuned for phones with 6-8 GB RAM:
 *  - CPU backend (stable on every device).
 *  - maxNumTokens = 2048 keeps the KV cache small (it is input + output tokens).
 *  - The Conversation is REUSED between turns, so the model does not re-read the whole
 *    history every time (this is what removes the lag after the first message).
 *  - History is rebuilt from a short window every few turns so memory never grows.
 */
class LiteRtEngine(private val context: Context) {
    @Volatile private var engine: Engine? = null
    @Volatile private var conversation: Conversation? = null
    private var convoChat = ""
    private var convoSystem = ""
    private var convoCount = -1      // message count the live conversation has already seen
    private var convoTurns = 0
    @Volatile private var cancelled = false

    /** Context window / history size adapted to the phone's total RAM (bytes). */
    private var historyKeep = 8
    private var maxTurns = 6
    @Synchronized fun load(path: String, totalRamBytes: Long) {
        close()
        val gb = totalRamBytes / (1024.0 * 1024.0 * 1024.0)
        // KV cache grows with maxNumTokens (input + output). Smaller on smaller phones.
        val tokens = when { gb >= 7.0 -> 2048; gb >= 5.0 -> 1536; else -> 1024 }
        maxTurns = (tokens / 250).coerceAtLeast(3)   // each turn can add ~250 tokens (question + English + Portuguese)
        historyKeep = when { gb >= 7.0 -> 8; gb >= 5.0 -> 6; else -> 4 }
        // Persistent cache folder (cacheDir can be wiped by the system / "clear cache"),
        // so the next app start loads faster.
        val cache = java.io.File(context.filesDir, "litert-cache").apply { mkdirs() }
        val candidate = Engine(
            EngineConfig(
                modelPath = path,
                backend = Backend.CPU(),
                maxNumTokens = tokens,
                cacheDir = cache.absolutePath
            )
        )
        // LiteRT-LM's Android guidance says not to close an Engine whose initialize() failed.
        // Assign only after initialize succeeds, so close() cannot treat a failed candidate as live.
        candidate.initialize()
        engine = candidate
    }

    private fun dropConversation() {
        try { conversation?.close() } catch (_: Exception) {}
        conversation = null; convoChat = ""; convoSystem = ""; convoCount = -1; convoTurns = 0
    }

    /** chat = conversation id from the UI, count = number of messages including the new user message. */
    fun generate(chat: String, count: Int, system: String, messages: List<Pair<String, String>>, onToken: (String) -> Unit) = runBlocking {
        val e = checkNotNull(engine) { "Load a compatible .litertlm model in Settings first." }
        val lastIndex = messages.indexOfLast { it.first == "user" }
        require(lastIndex >= 0) { "No user message was provided." }
        cancelled = false

        val reuse = conversation != null && chat.isNotEmpty() && chat == convoChat &&
            count == convoCount + 1 && convoTurns < maxTurns && system == convoSystem
        if (!reuse) {
            val keep = if (convoTurns >= maxTurns) minOf(4, historyKeep) else historyKeep   // read BEFORE dropConversation() resets the counter
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
            convoChat = chat; convoSystem = system; convoTurns = 0
        }
        val c = checkNotNull(conversation)
        var ok = false
        try {
            c.sendMessageAsync(messages[lastIndex].second.take(1000)).collect { chunk ->
                onToken(chunk.toString())
            }
            ok = !cancelled
        } finally {
            if (ok) { convoCount = count + 1; convoTurns++ }   // keep it warm for the next turn
            else dropConversation()                              // cancelled/failed: start clean next time
        }
    }

    /** Free the conversation (KV cache) without unloading the model. Safe to call when memory is tight. */
    @Synchronized fun trimConversation() { dropConversation() }

    fun cancel() { cancelled = true; try { conversation?.cancelProcess() } catch (_: Exception) {} }

    @Synchronized fun close() {
        dropConversation()
        try { engine?.close() } catch (_: Exception) {}
        engine = null
    }
}
