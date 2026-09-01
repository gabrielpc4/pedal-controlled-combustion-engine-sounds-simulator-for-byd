package com.gabrielpc.enginesoundsimulator

import android.app.Application
import com.gabrielpc.enginesoundsimulator.drive.DriveController

class EngineSoundsApplication : Application() {
    @Volatile
    private var controller: DriveController? = null

    val driveController: DriveController
        get() {
            controller?.let { return it }
            return synchronized(this) {
                controller ?: DriveController(this).also { created -> controller = created }
            }
        }

    @Volatile
    private var engineShutdown = false

    fun shutdownEngine() {
        if (engineShutdown) {
            return
        }

        engineShutdown = true
        controller?.stop()
        stopService(EngineRuntimeService.stopIntent(this))
    }
}
