package dev.morrow.llm

/** Thin JNI surface. All work happens on the caller's thread. */
object Llama {
    init {
        System.loadLibrary("morrow")
        nativeInit()
    }

    fun interface TokenSink {
        fun onToken(piece: String)
    }

    @JvmStatic external fun nativeInit(): Long

    @JvmStatic external fun nativeLoad(path: String, threads: Int, nCtx: Int, nBatch: Int): Long

    @JvmStatic external fun nativeGenerate(
        handle: Long,
        prompt: String,
        grammar: String,
        maxTokens: Int,
        sink: TokenSink?,
        stats: LongArray,
    ): String?

    @JvmStatic external fun nativeStop(handle: Long)

    @JvmStatic external fun nativeFree(handle: Long)
}
