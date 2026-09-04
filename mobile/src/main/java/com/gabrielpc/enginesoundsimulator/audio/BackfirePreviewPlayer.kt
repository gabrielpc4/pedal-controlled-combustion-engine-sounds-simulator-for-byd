package com.gabrielpc.enginesoundsimulator.audio

import android.content.Context
import android.media.MediaPlayer

/** Small settings-only preview player for the shared Alfa Romeo backfire sample set. */
internal class BackfirePreviewPlayer(context: Context) {
    private val appContext = context.applicationContext
    private var player: MediaPlayer? = null

    @Synchronized
    fun play(sample: Int) {
        if (sample !in 1..4) return

        player?.runCatching { stop() }
        player?.release()
        player = runCatching {
            appContext.assets.openFd("backfire/alfa/backfire_$sample.wav").let { descriptor ->
                MediaPlayer().apply {
                    setDataSource(descriptor.fileDescriptor, descriptor.startOffset, descriptor.length)
                    descriptor.close()
                    setOnCompletionListener { completed ->
                        completed.release()
                        synchronized(this@BackfirePreviewPlayer) {
                            if (player === completed) player = null
                        }
                    }
                    prepare()
                    start()
                }
            }
        }.getOrNull()
    }

    @Synchronized
    fun release() {
        player?.runCatching { stop() }
        player?.release()
        player = null
    }
}
