package com.gabrielpc.enginesoundsimulator

/**
 * Human-readable install stamp shown in diagnostics so on-car builds are easy to identify.
 */
object AppBuildInfo {
    val buildNumber: Int = BuildConfig.BUILD_NUMBER

    val gitSha: String = BuildConfig.GIT_SHA

    val builtAtUtc: String = BuildConfig.BUILD_TIME_UTC

    val diagnosticTitleSuffix: String =
        "#$buildNumber · $gitSha"
}
