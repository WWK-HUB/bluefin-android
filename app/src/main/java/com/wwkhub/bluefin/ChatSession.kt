package com.wwkhub.bluefin

import android.content.Context
import android.os.Handler
import android.os.Looper
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.CancellationException

internal enum class ChatPhase {
    IDLE,
    THINKING,
    STREAMING,
    STOPPED,
    FAILED,
}

internal data class ChatSnapshot(
    val lines: List<ChatLine>,
    val phase: ChatPhase,
    val lastError: String?,
    val consecutiveFailures: Int,
    val configured: Boolean,
    val modelName: String,
    val requestGeneration: Int,
    val workerBusy: Boolean,
)

internal enum class ChatSendResult {
    STARTED,
    EMPTY,
    NOT_CONFIGURED,
    BUSY,
}

/**
 * The single chat session shared by the app screen and the overlay panel.
 *
 * Construction is lazy. Opening the overlay never touches this class, the
 * Keystore, encrypted preferences, or the persona file.
 */
internal class ChatSession private constructor(context: Context) {
    private val appContext = context.applicationContext
    private val handler = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArraySet<(ChatSnapshot) -> Unit>()
    private val lock = Any()
    private val lines = mutableListOf<ChatLine>()
    private var loaded = false
    private var phase = ChatPhase.IDLE
    private var lastError: String? = null
    private var consecutiveFailures = 0
    private var worker: Thread? = null
    private var client: ChatCompletionsClient? = null
    private var requestGeneration = 0
    @Volatile
    private var cancelRequested = false
    @Volatile
    private var notifyScheduled = false
    private var lastPersistAt = 0L

    fun snapshot(): ChatSnapshot = synchronized(lock) {
        ensureLoaded()
        ChatSnapshot(
            lines = lines.toList(),
            phase = phase,
            lastError = lastError,
            consecutiveFailures = consecutiveFailures,
            configured = isConfigured(),
            modelName = ModelEndpointStore.load(appContext).model,
            requestGeneration = requestGeneration,
            workerBusy = worker?.isAlive == true,
        )
    }

    fun addListener(listener: (ChatSnapshot) -> Unit) {
        listeners.add(listener)
        listener(snapshot())
    }

    fun removeListener(listener: (ChatSnapshot) -> Unit) {
        listeners.remove(listener)
    }

    fun send(text: String): ChatSendResult {
        val content = text.trim().take(4000)
        if (content.isEmpty()) return ChatSendResult.EMPTY

        val endpoint = ModelEndpointStore.load(appContext)
        if (!EndpointAddressPolicy.isConfigured(
                endpoint.url,
                endpoint.model,
                ModelEndpointStore.hasSavedKey(appContext),
            )
        ) {
            return ChatSendResult.NOT_CONFIGURED
        }
        val apiKey = ApiKeyVault.read(appContext)
        if (apiKey.isNullOrBlank()) {
            ModelEndpointStore.markKeySaved(appContext, false)
            return ChatSendResult.NOT_CONFIGURED
        }
        val persona = PersonaRepository.read(appContext)

        var requestHistory: List<ChatLine> = emptyList()
        synchronized(lock) {
            ensureLoaded()
            if (worker?.isAlive == true) return ChatSendResult.BUSY
            lines.add(ChatLine(role = ChatRole.USER, text = content))
            persistLocked()
            requestHistory = lines
                .filter { !it.failed && (it.text.isNotBlank() || it.stopped) }
                .takeLast(40)
                .toList()
            phase = ChatPhase.THINKING
            lastError = null
            cancelRequested = false
            val generation = ++requestGeneration
            worker = Thread(
                { runRequest(endpoint, apiKey, persona, requestHistory, generation) },
                "bluefin-chat",
            ).apply { isDaemon = true }
            worker?.start()
        }
        notifyListeners()
        return ChatSendResult.STARTED
    }

    fun stop() {
        synchronized(lock) {
            if (phase != ChatPhase.THINKING && phase != ChatPhase.STREAMING) return
            cancelRequested = true
            client?.cancel()
            worker?.interrupt()
        }
    }

    fun clearHistory() {
        synchronized(lock) {
            requestGeneration += 1
            cancelRequested = true
            client?.cancel()
            lines.clear()
            phase = ChatPhase.IDLE
            lastError = null
            consecutiveFailures = 0
            persistLocked()
            TranscriptFile.clear(appContext)
        }
        notifyListeners()
    }

    private fun runRequest(
        endpoint: ModelEndpoint,
        apiKey: String,
        persona: String,
        history: List<ChatLine>,
        generation: Int,
    ) {
        val requestClient = ChatCompletionsClient()
        synchronized(lock) { client = requestClient }
        var assistantIndex = -1
        try {
            val reply = requestClient.stream(
                endpoint = endpoint,
                apiKey = apiKey,
                persona = persona,
                history = history,
                onDelta = { delta ->
                    synchronized(lock) {
                        if (generation == requestGeneration) {
                            if (assistantIndex < 0) {
                                lines.add(ChatLine(role = ChatRole.ASSISTANT, text = delta))
                                assistantIndex = lines.lastIndex
                            } else if (assistantIndex in lines.indices) {
                                val current = lines[assistantIndex]
                                lines[assistantIndex] = current.copy(text = current.text + delta)
                            }
                            phase = ChatPhase.STREAMING
                            persistThrottledLocked()
                        }
                    }
                    if (generation == requestGeneration) notifyListeners()
                },
                isCancelled = { cancelRequested },
            )
            synchronized(lock) {
                if (generation == requestGeneration) {
                    if (cancelRequested) {
                        finishStoppedLocked(assistantIndex)
                    } else if (reply.isBlank() && assistantIndex < 0) {
                        finishFailureLocked("模型没有返回内容，请稍后再试。", assistantIndex)
                    } else {
                        if (assistantIndex < 0) {
                            lines.add(ChatLine(role = ChatRole.ASSISTANT, text = reply))
                            assistantIndex = lines.lastIndex
                        }
                        phase = ChatPhase.IDLE
                        consecutiveFailures = 0
                        lastError = null
                        persistLocked()
                    }
                }
                worker = null
                client = null
            }
        } catch (_: CancellationException) {
            synchronized(lock) {
                if (generation == requestGeneration) finishStoppedLocked(assistantIndex)
                worker = null
                client = null
            }
        } catch (error: ChatHttpStatusException) {
            synchronized(lock) {
                if (generation == requestGeneration) {
                    finishFailureLocked(ChatFailureText.forStatus(error.status), assistantIndex)
                }
                worker = null
                client = null
            }
        } catch (_: ChatServiceException) {
            synchronized(lock) {
                if (generation == requestGeneration) {
                    finishFailureLocked(
                        ChatFailureText.forServiceFailure(),
                        assistantIndex,
                    )
                }
                worker = null
                client = null
            }
        } catch (error: Exception) {
            synchronized(lock) {
                if (generation == requestGeneration) {
                    if (cancelRequested) {
                        finishStoppedLocked(assistantIndex)
                    } else {
                        finishFailureLocked(ChatFailureText.forThrowable(error), assistantIndex)
                    }
                }
                worker = null
                client = null
            }
        }
        // Even an invalidated request releases the busy state after clear.
        notifyListeners()
    }

    private fun finishStoppedLocked(assistantIndex: Int) {
        if (assistantIndex < 0) {
            lines.add(ChatLine(role = ChatRole.ASSISTANT, text = "", stopped = true))
        } else if (assistantIndex in lines.indices) {
            lines[assistantIndex] = lines[assistantIndex].copy(stopped = true)
        }
        phase = ChatPhase.STOPPED
        persistLocked()
    }

    private fun finishFailureLocked(message: String, assistantIndex: Int) {
        if (assistantIndex in lines.indices) {
            lines[assistantIndex] = lines[assistantIndex].copy(failed = true)
        } else {
            lines.add(ChatLine(role = ChatRole.ASSISTANT, text = "", failed = true))
        }
        phase = ChatPhase.FAILED
        lastError = ChatFailureText.oneLine(message)
        consecutiveFailures += 1
        persistLocked()
    }

    private fun isConfigured(): Boolean {
        val endpoint = ModelEndpointStore.load(appContext)
        return EndpointAddressPolicy.isConfigured(
            endpoint.url,
            endpoint.model,
            ModelEndpointStore.hasSavedKey(appContext),
        )
    }

    private fun ensureLoaded() {
        if (loaded) return
        loaded = true
        lines.addAll(TranscriptFile.read(appContext))
    }

    private fun persistThrottledLocked() {
        val now = System.currentTimeMillis()
        if (now - lastPersistAt < 700) return
        lastPersistAt = now
        persistLocked()
    }

    private fun persistLocked() {
        TranscriptFile.write(appContext, lines)
    }

    private fun notifyListeners() {
        if (listeners.isEmpty() || notifyScheduled) return
        notifyScheduled = true
        handler.post {
            notifyScheduled = false
            val current = snapshot()
            listeners.toList().forEach { it(current) }
        }
    }

    companion object {
        @Volatile
        private var instance: ChatSession? = null

        fun shared(context: Context): ChatSession {
            instance?.let { return it }
            return synchronized(this) {
                instance ?: ChatSession(context).also { instance = it }
            }
        }

        fun peek(): ChatSession? = instance
    }
}
