package io.github.calendaralarm.worker

import android.content.Context
import io.github.calendaralarm.sync.SyncRepository

/**
 * P2A B2 (Opus review): injectable source for alarm plans, so doWork()
 * is end-to-end testable without network (fake fetcher in tests).
 *
 * Production: [DefaultFetcher] delegates to the real [SyncRepository] (Ktor).
 */
interface AlarmPlanFetcher {
    suspend fun fetchAlarmPlan(date: String): SyncRepository.SyncResult
    fun close()
}

/** Production implementation: real HTTPS pull via SyncRepository. */
class SyncRepositoryFetcher(context: Context) : AlarmPlanFetcher {
    private val repo = SyncRepository(context)
    override suspend fun fetchAlarmPlan(date: String): SyncRepository.SyncResult =
        repo.fetchAlarmPlan(date)
    override fun close() = repo.close()
}
