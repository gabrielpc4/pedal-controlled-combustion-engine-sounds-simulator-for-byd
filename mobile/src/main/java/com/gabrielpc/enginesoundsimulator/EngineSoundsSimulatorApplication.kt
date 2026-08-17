package com.gabrielpc.enginesoundsimulator

import android.app.Application
import com.gabrielpc.enginesoundsimulator.diagnostics.DebugEventLog

/** Installs in-memory diagnostics before the dashboard, controller, or audio engine are created. */
class EngineSoundsSimulatorApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        DebugEventLog.install(this)
    }

    override fun onTrimMemory(level: Int) {
        DebugEventLog.warning("trim_memory", "level=$level")
        super.onTrimMemory(level)
    }
}
