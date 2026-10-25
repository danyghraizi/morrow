package dev.morrow.model

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest

/**
 * The model ships inside the APK, so the app is usable the moment it is
 * installed: no download, no account, no network permission at all.
 *
 * Android cannot mmap a compressed asset, so the GGUF is unpacked once into
 * noBackupFilesDir (excluded from cloud backup) while its SHA-256 is verified.
 */
class ModelPack(
    private val context: Context,
    private val scope: CoroutineScope,
) {
    sealed interface State {
        data object Idle : State
        data class Unpacking(val progress: Float) : State
        data class Ready(val path: String, val bytes: Long) : State
        data class Failed(val message: String) : State
    }

    private val mutex = Mutex()
    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    val targetFile: File
        get() = File(File(context.noBackupFilesDir, "models"), Info.ASSET_NAME)

    /** Clears a failure so the unpack can be attempted again. */
    fun retry() {
        if (_state.value is State.Failed) {
            _state.value = State.Idle
            ensureReady()
        }
    }

    fun ensureReady() {
        val current = _state.value
        if (current is State.Ready || current is State.Unpacking) return
        scope.launch {
            mutex.withLock {
                val state = _state.value
                if (state is State.Ready) return@withLock
                _state.value = unpack()
            }
        }
    }

    private suspend fun unpack(): State = withContext(Dispatchers.IO) {
        val target = targetFile
        if (target.length() == Info.BYTES) {
            return@withContext State.Ready(target.absolutePath, target.length())
        }
        target.parentFile?.mkdirs()
        val temp = File(target.parentFile, "${target.name}.part")
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(1 shl 20)
        var copied = 0L
        try {
            context.assets.open("models/${Info.ASSET_NAME}").use { input ->
                temp.outputStream().use { output ->
                    while (true) {
                        val read = input.read(buffer)
                        if (read <= 0) break
                        output.write(buffer, 0, read)
                        digest.update(buffer, 0, read)
                        copied += read
                        val progress = (copied.toFloat() / Info.BYTES).coerceIn(0f, 1f)
                        _state.value = State.Unpacking(progress)
                    }
                }
            }
            val hex = digest.digest().joinToString("") { "%02x".format(it) }
            if (copied != Info.BYTES || hex != Info.SHA256) {
                temp.delete()
                Log.e(TAG, "model integrity check failed: $copied bytes, $hex")
                return@withContext State.Failed("Model file is damaged. Reinstall the app.")
            }
            if (target.exists()) target.delete()
            if (!temp.renameTo(target)) {
                return@withContext State.Failed("Could not move the model into app storage")
            }
            State.Ready(target.absolutePath, target.length())
        } catch (t: Throwable) {
            temp.delete()
            Log.e(TAG, "unpack failed", t)
            State.Failed("Could not unpack the model: ${t.message}")
        }
    }

    object Info {
        const val ASSET_NAME = "functiongemma-270m-it-q8_0.gguf"
        const val BYTES = 291_557_792L
        const val SHA256 = "83940d4dd9676710856f43523bed096164a595a96f6b34771610a03937de5270"
        const val TITLE = "FunctionGemma 270M"
        const val QUANT = "Q8_0 · 268M params"
        const val LICENSE = "Gemma Terms of Use"
        const val SOURCE = "ggml-org/functiongemma-270m-it-GGUF"
    }

    private companion object {
        const val TAG = "MorrowModel"
    }
}
