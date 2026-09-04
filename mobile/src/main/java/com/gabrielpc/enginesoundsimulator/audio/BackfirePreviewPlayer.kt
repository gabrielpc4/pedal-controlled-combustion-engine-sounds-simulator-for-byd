package com.gabrielpc.enginesoundsimulator.audio

import android.content.Context
import android.media.MediaPlayer
import com.gabrielpc.enginesoundsimulator.drive.AlfaBackfireSources

/** Small settings-only preview player for the shared Alfa Romeo backfire sample set. */
internal class BackfirePreviewPlayer(context: Context) {
    private val appContext = context.applicationContext
    private var player: MediaPlayer? = null

    @Synchronized
    fun play(sample: Int) {
        if (sample !in AlfaBackfireSources.indices) return

        player?.runCatching { stop() }
        player?.release()
        player = runCatching {
            val sourceName = AlfaBackfireSources.names[sample - 1]
            appContext.assets.openFd("backfire/alfa/$sourceName.wav").let { descriptor ->
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
