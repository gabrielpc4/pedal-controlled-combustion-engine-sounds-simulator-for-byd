package com.gabrielpc.enginesoundsimulator.drive

/** A user-facing error or warning shown on the dashboard until dismissed. */
data class UserVisibleMessage(
    val id: Long,
    val title: String,
    val detail: String,
)
