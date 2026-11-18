package io.github.calendaralarm.data

import kotlinx.serialization.Serializable

/**
 * Cached sync status for UI display.
 */
@Serializable
data class SyncStatusCache(
    val lastSyncTime: String? = null,
    val lastSyncResult: String? = null,
    val serverReachable: Boolean? = null
)
