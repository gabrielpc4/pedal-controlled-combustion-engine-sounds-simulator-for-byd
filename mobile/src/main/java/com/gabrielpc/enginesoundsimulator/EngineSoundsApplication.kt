package com.gabrielpc.enginesoundsimulator

import android.app.Application
import com.gabrielpc.enginesoundsimulator.audio.EngineSampleProfiles
import com.gabrielpc.enginesoundsimulator.drive.DriveController

class EngineSoundsApplication : Application() {
    lateinit var driveController: DriveController
        private set

    @Volatile
    private var engineShutdown = false

    override fun onCreate() {
        super.onCreate()
        EngineSampleProfiles.initialize(this)
        driveController = DriveController(this)
    }

    fun shutdownEngine() {
        if (engineShutdown) {
            return
        }

        engineShutdown = true
        driveController.stop()
        stopService(EngineRuntimeService.stopIntent(this))
    }
}
