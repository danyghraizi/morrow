package dev.morrow.llm

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Owns the llama.cpp context.
 *
 * Battery and memory policy:
 *  * the model is loaded on first use, never at boot;
 *  * it is unloaded after [IDLE_UNLOAD_MS] without a request, and immediately on
 *    Activity stop or a memory-trim signal, which releases the mmap'd weights;
 *  * threads are capped at 4: beyond that the phone spends more energy on
 *    co-ordination than it saves in latency;
 *  * generations are greedy and capped at [MAX_TOKENS] tokens.
 */
class LlamaEngine(private val scope: CoroutineScope) {
    sealed interface State {
        data object Unloaded : State
        data object Loading : State
        data object Ready : State
        data class Failed(val message: String) : State
    }

    private val mutex = Mutex()
    private var handle: Long = 0L
    private var idleJob: Job? = null

    private val _state = MutableStateFlow<State>(State.Unloaded)
    val state: StateFlow<State> = _state.asStateFlow()

    private val _lastLatencyMs = MutableStateFlow(0L)
    val lastLatencyMs: StateFlow<Long> = _lastLatencyMs.asStateFlow()

    var modelPath: String? = null
        set(value) {
            field = value
            if (value == null) release()
        }

    val isLoaded: Boolean get() = handle != 0L

    /**
     * Prefill dominates the cost of a short request, so use the cores we have but
     * cap at four: phones throttle quickly and the fifth thread buys ~5% latency
     * for a lot more heat.
     */
    private fun threads(): Int =
        Runtime.getRuntime().availableProcessors().coerceIn(2, 2)

    /** Prefill is compute bound; a short burst of four threads is worth it. */
    private fun batchThreads(): Int =
        Runtime.getRuntime().availableProcessors().coerceIn(2, 4)

    private suspend fun ensureLoaded(): Long = withContext(Dispatchers.Default) {
        if (handle != 0L) return@withContext handle
        val path = modelPath ?: return@withContext 0L
        _state.value = State.Loading
        val h = Llama.nativeLoad(path, batchThreads(), CONTEXT_TOKENS, BATCH_TOKENS)
        if (h == 0L) {
            _state.value = State.Failed("Could not load the model file")
            return@withContext 0L
        }
        handle = h
        _state.value = State.Ready
        Log.i(TAG, "model loaded from $path")
        h
    }

    /**
     * Runs one constrained generation. Returns null when the engine is not
     * available; callers treat that as a soft failure.
     */
    suspend fun generate(
        prompt: String,
        grammar: String,
        maxTokens: Int = MAX_TOKENS,
        onToken: (String) -> Unit = {},
    ): String? = mutex.withLock {
        idleJob?.cancel()
        val h = ensureLoaded()
        if (h == 0L) return@withLock null
        val started = System.nanoTime()
        val stats = LongArray(4)
        val output = try {
            withContext(Dispatchers.Default) {
                Llama.nativeGenerate(h, prompt, grammar, maxTokens, { piece -> onToken(piece) }, stats)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "generation failed", t)
            null
        }
        _lastLatencyMs.value = (System.nanoTime() - started) / 1_000_000
        Log.i(
            TAG,
            "prefill ${stats[0]} tok in ${stats[2]} ms, decode ${stats[1]} tok in ${stats[3]} ms " +
                "(batch ${batchThreads()} thr, decode ${threads()} thr) -> total ${_lastLatencyMs.value} ms",
        )
        scheduleIdleUnload()
        output
    }

    fun cancel() {
        val h = handle
        if (h != 0L) Llama.nativeStop(h)
    }

    /** Drops the weights; the next request pays a ~0.5 s reload. */
    fun release() {
        idleJob?.cancel()
        // Freeing under the lock would block; a running generation finishes first.
        scope.launch {
            mutex.withLock {
                if (handle != 0L) {
                    Llama.nativeFree(handle)
                    handle = 0L
                    _state.value = State.Unloaded
                }
            }
        }
    }

    private fun scheduleIdleUnload() {
        idleJob?.cancel()
        idleJob = scope.launch {
            delay(IDLE_UNLOAD_MS)
            mutex.withLock {
                if (handle != 0L) {
                    Llama.nativeFree(handle)
                    handle = 0L
                    _state.value = State.Unloaded
                }
            }
        }
    }

    companion object {
        private const val TAG = "MorrowEngine"
        private const val IDLE_UNLOAD_MS = 90_000L
        private const val CONTEXT_TOKENS = 1024
        private const val BATCH_TOKENS = 128
        const val MAX_TOKENS = 64
    }
}
