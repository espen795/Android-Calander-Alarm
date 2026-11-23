package io.github.calendaralarm.data

import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * P2B review#9: transactional boundary for all read-modify-write paths around
 * alarms (AlarmRepository + receivers + worker). Lost updates between worker,
 * UI and receivers were possible because each caller read/mutated/wrote separately.
 *
 * Synchronous (ReentrantLock, no coroutine Mutex): receivers and the worker
 * do not all call suspend, so one shared lock covers all paths.
 * Reentrant so nested calls (e.g. replaceForDate → schedule → cancel)
 * do not deadlock.
 *
 * Rule: NO network I/O inside [run] — sync first fetches the plan (outside
 * the lock) and then applies it inside the lock.
 */
object AlarmTransactions {
    private val lock = ReentrantLock()

    /** Run [block] under the global alarm mutex. Reentrant for nested calls. */
    fun <T> run(block: () -> T): T = lock.withLock(block)
}
