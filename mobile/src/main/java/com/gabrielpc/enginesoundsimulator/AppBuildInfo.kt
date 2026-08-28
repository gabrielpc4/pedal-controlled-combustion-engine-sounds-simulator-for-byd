package com.gabrielpc.enginesoundsimulator

/** Human-readable install stamp shown in the dashboard header. */
object AppBuildInfo {
    val buildNumber: Int = BuildConfig.BUILD_NUMBER

    val gitSha: String = BuildConfig.GIT_SHA

    val builtAtUtc: String = BuildConfig.BUILD_TIME_UTC

}
