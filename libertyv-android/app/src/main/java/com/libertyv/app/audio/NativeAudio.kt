package com.libertyv.app.audio

import com.libertyv.core.ConsolePlatform
import com.libertyv.core.audio.AudioDecoder
import com.libertyv.core.audio.AudioDecoderFactory
import java.io.IOException

/** Puente con el decodificador nativo (FFmpeg): XMA2 en Xbox 360, MP3 en PS3. */
object NativeAudio {
    init {
        System.loadLibrary("awcdec")
    }

    @JvmStatic external fun nativeInit(isMp3: Boolean, sampleRate: Int): Long

    @JvmStatic external fun nativeFeed(handle: Long, data: ByteArray, off: Int, len: Int): ShortArray?

    @JvmStatic external fun nativeErrors(handle: Long): Int

    @JvmStatic external fun nativeClose(handle: Long)

    val factory = AudioDecoderFactory { platform, sampleRate ->
        val handle = nativeInit(platform == ConsolePlatform.PS3, sampleRate)
        if (handle == 0L) throw IOException("No se pudo iniciar el decodificador de audio")
        object : AudioDecoder {
            private var h = handle

            override fun feed(data: ByteArray, off: Int, len: Int): ShortArray =
                nativeFeed(h, data, off, len) ?: ShortArray(0)

            override fun close() {
                if (h != 0L) {
                    nativeClose(h)
                    h = 0L
                }
            }
        }
    }
}
