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

/** LiteRT-LM owner. Model weights remain in app-private storage. */
class LiteRtEngine(private val context: Context) {
    @Volatile private var engine: Engine? = null
    @Volatile private var conversation: Conversation? = null
    @Synchronized fun load(path: String) {
        close()
        var candidate = Engine(EngineConfig(modelPath = path, backend = Backend.GPU(), cacheDir = context.cacheDir.absolutePath))
        try { candidate.initialize(); engine = candidate }
        catch (gpuError: Exception) {
            try { candidate.close() } catch (_: Exception) {}
            candidate = Engine(EngineConfig(modelPath = path, backend = Backend.CPU(), cacheDir = context.cacheDir.absolutePath))
            try { candidate.initialize(); engine = candidate }
            catch (cpuError: Exception) { try { candidate.close() } catch (_: Exception) {}; cpuError.addSuppressed(gpuError); throw cpuError }
        }
    }
    fun generate(system: String, messages: List<Pair<String, String>>, onToken: (String) -> Unit) = runBlocking {
        val e = checkNotNull(engine) { "Load a compatible .litertlm model in Settings first." }
        val lastIndex = messages.indexOfLast { it.first == "user" }
        require(lastIndex >= 0) { "No user message was provided." }
        val initial = messages.take(lastIndex).mapNotNull { (role, text) -> when (role) { "user" -> Message.user(text); "model", "assistant" -> Message.model(text); else -> null } }
        val config = ConversationConfig(systemInstruction = Contents.of(system), initialMessages = initial)
        val c = e.createConversation(config); conversation = c
        try { c.sendMessageAsync(messages[lastIndex].second).collect { chunk -> onToken(chunk.toString()) } }
        finally { conversation = null; c.close() }
    }
    fun cancel() { try { conversation?.cancelProcess() } catch (_: Exception) {} }
    @Synchronized fun close() { try { conversation?.close() } catch (_: Exception) {}; conversation = null; try { engine?.close() } catch (_: Exception) {}; engine = null }
}
