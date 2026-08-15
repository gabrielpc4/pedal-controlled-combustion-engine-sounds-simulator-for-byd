package com.gabrielpc.bydmotorsound

import android.app.Activity
import android.app.Application
import android.os.Bundle
import com.gabrielpc.bydmotorsound.diagnostics.PersistentDiagnosticLog

/** Installs diagnostics before the dashboard, controller, or audio engine are created. */
class BydMotorSoundApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        PersistentDiagnosticLog.install(this)
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity: Activity, state: Bundle?) {
                PersistentDiagnosticLog.event("activity_created", "activity=${activity.javaClass.simpleName}")
            }

            override fun onActivityStarted(activity: Activity) {
                PersistentDiagnosticLog.event("activity_started", "activity=${activity.javaClass.simpleName}")
            }

            override fun onActivityResumed(activity: Activity) {
                PersistentDiagnosticLog.event("activity_resumed", "activity=${activity.javaClass.simpleName}")
            }

            override fun onActivityPaused(activity: Activity) {
                PersistentDiagnosticLog.event("activity_paused", "activity=${activity.javaClass.simpleName}")
            }

            override fun onActivityStopped(activity: Activity) {
                PersistentDiagnosticLog.event("activity_stopped", "activity=${activity.javaClass.simpleName}")
            }

            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit

            override fun onActivityDestroyed(activity: Activity) {
                PersistentDiagnosticLog.event("activity_destroyed", "activity=${activity.javaClass.simpleName}")
            }
        })
    }

    override fun onTrimMemory(level: Int) {
        PersistentDiagnosticLog.warning("trim_memory", "level=$level")
        super.onTrimMemory(level)
    }
}
