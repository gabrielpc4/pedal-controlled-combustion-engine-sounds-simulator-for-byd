package com.gabrielpc.enginesoundsimulator.drive

/** A user-facing state message shown on the dashboard until dismissed. */
data class UserVisibleMessage(
    val id: Long,
    val title: String,
    val detail: String,
    val severity: UserVisibleMessageSeverity = UserVisibleMessageSeverity.ERROR,
)

enum class UserVisibleMessageSeverity {
    INFO,
    ERROR,
}
