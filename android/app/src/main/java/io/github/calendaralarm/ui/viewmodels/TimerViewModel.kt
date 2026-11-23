package io.github.calendaralarm.ui.viewmodels

import android.content.Context
import android.os.SystemClock
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import io.github.calendaralarm.data.AppLogger
import io.github.calendaralarm.data.TimerScheduler
import io.github.calendaralarm.data.TimerStateStore
import io.github.calendaralarm.receiver.TimerFireReceiver
import io.github.calendaralarm.service.TimerForegroundService

private const val TAG = "TimerViewModel"

/**
 * Timer state — immutable snapshot for the UI.
 */
data class TimerUiState(
    val remainingMs: Long = 25 * 60 * 1000L,   // default 25 min (Pomodoro)
    val totalMs: Long = 25 * 60 * 1000L,
    val isRunning: Boolean = false,
    val isPaused: Boolean = false,
    val isFinished: Boolean = false,
    val customHours: Int = 0,
    val customMinutes: Int = 25,
    val customSeconds: Int = 0,
    val progress: Float = 1f,
    val isRestored: Boolean = false  // v2.5.6: true when state was restored after app restart
)

/**
 * ViewModel for the countdown timer screen.
 *
 * Responsibilities:
 * - Holds all timer state (survives tab switches and configuration changes)
 * - Uses SystemClock.elapsedRealtime() for monotonic time tracking (in-memory)
 * - Runs a coroutine loop to update remaining time while running
 * - v2.7.0: At 0 only UI state + TimerFireReceiver.fire() (idempotent). Sound and
 *   full-screen activity are fully owned by TimerRingService — the UI layer no
 *   longer starts sound or activities itself (that caused duplicate ringing screens).
 *
 * v2.5.6: AlarmManager integratie — timer overleeft app kill en reboot:
 * - On start(): save state in TimerStateStore + schedule AlarmManager via TimerScheduler
 * - On pause(): update state + cancel AlarmManager entry
 * - On stop()/reset(): clear state + cancel AlarmManager
 * - On init: restore state from TimerStateStore (compute remaining based on endTimeWallClock)
 * - The coroutine loop remains for UI updates, but AlarmManager is the source of truth
 *
 * v2.5.0: Extracted from TimerScreen to separate UI from business logic.
 */
class TimerViewModel(
    context: Context? = null  // v2.7.0: plain param (no Activity leak); v2.5.6: optional for AlarmManager sync (null in tests)
) : ViewModel() {

    private val _uiState = MutableStateFlow(TimerUiState())
    val uiState: StateFlow<TimerUiState> = _uiState.asStateFlow()

    /** Elapsed realtime timestamp when the timer should end (0 = not set). */
    private var endTimeElapsed: Long = 0L

    /**
     * v2.5.6: Wall clock timestamp (System.currentTimeMillis) when the timer should end.
     * This is the value stored in TimerStateStore — wall clock survives a reboot.
     */
    private var endTimeWallClock: Long = 0L

    /** Coroutine job for the update loop. */
    private var updateJob: Job? = null

    /**
     * v2.5.6: TimerStateStore for persistence (null when no context available).
     */
    private val stateStore: TimerStateStore? = context?.let { TimerStateStore(it) }

    /**
     * v2.5.6: TimerScheduler for AlarmManager integration (null when no context available).
     */
    private val scheduler: TimerScheduler? = context?.let { TimerScheduler(it) }

    private val appContext: Context? = context?.applicationContext

    /**
     * v2.7.0: Timer is finished — let it fire (idempotently) via TimerFireReceiver.
     * If the AlarmManager receiver already fired, this is a no-op.
     */
    private fun fireFinished(source: String) {
        val ctx = appContext ?: return
        TimerFireReceiver.fire(ctx, source)
    }

    private fun startCountdownService(remainingMs: Long) {
        appContext?.let { TimerForegroundService.start(it, remainingMs) }
    }

    private fun stopCountdownService() {
        appContext?.let { TimerForegroundService.stop(it) }
    }

    init {
        AppLogger.i(TAG, "TimerViewModel created")
        // v2.5.6 Story 4: Restore state from TimerStateStore if there was an active timer
        restoreFromStore()
    }

    // ── v2.5.6: State restore ───────────────────────────────────────

    /**
     * v2.5.6: Restore timer state from TimerStateStore (after app kill or reboot).
     *
     * - If there was a running timer: compute remaining based on endTimeWallClock
     * - If the timer already finished (remaining <= 0): mark as finished
     * - If the timer was paused: restore with isPaused=true
     */
    private fun restoreFromStore() {
        val store = stateStore ?: return
        val savedState = store.load() ?: return

        val now = io.github.calendaralarm.data.AppClock.now()

        if (savedState.isRunning) {
            // Timer was running — compute remaining time
            val remaining = savedState.endTimeWallClock - now

            if (remaining <= 0) {
                // Timer already finished while the app was dead
                // AlarmManager should have fired, but just in case:
                AppLogger.i(TAG, "Restored timer already finished (remaining=$remaining)")
                _uiState.update {
                    it.copy(
                        remainingMs = 0L,
                        totalMs = savedState.totalMs,
                        isRunning = false,
                        isFinished = true,
                        progress = 0f,
                        customHours = savedState.customHours,
                        customMinutes = savedState.customMinutes,
                        customSeconds = savedState.customSeconds,
                        isRestored = true
                    )
                }
                // v2.7.0: Slightly expired (AlarmManager late/missed) → fire anyway.
                // Long expired → close silently (don't ring an hour late).
                if (-remaining <= LATE_FIRE_WINDOW_MS) {
                    fireFinished("restore")
                } else {
                    store.claimFinished()
                    AppLogger.w(TAG, "Timer ${-remaining}ms late on restore — no longer ringing")
                }
            } else {
                // Timer still running — restore with the correct remaining time
                AppLogger.i(TAG, "Restored running timer: ${formatDuration(remaining)} remaining")
                endTimeWallClock = savedState.endTimeWallClock
                endTimeElapsed = SystemClock.elapsedRealtime() + remaining
                _uiState.update {
                    it.copy(
                        remainingMs = remaining,
                        totalMs = savedState.totalMs,
                        isRunning = true,
                        isPaused = false,
                        isFinished = false,
                        progress = if (savedState.totalMs > 0) remaining.toFloat() / savedState.totalMs.toFloat() else 0f,
                        customHours = savedState.customHours,
                        customMinutes = savedState.customMinutes,
                        customSeconds = savedState.customSeconds,
                        isRestored = true
                    )
                }
                // Restart the update loop + countdown notification
                startUpdateLoop()
                startCountdownService(remaining)
            }
        } else {
            // Timer was paused — restore with the saved remaining time
            AppLogger.i(TAG, "Restored paused timer: ${formatDuration(savedState.remainingMsAtSave)} remaining")
            _uiState.update {
                it.copy(
                    remainingMs = savedState.remainingMsAtSave,
                    totalMs = savedState.totalMs,
                    isRunning = false,
                    isPaused = true,
                    isFinished = false,
                    progress = if (savedState.totalMs > 0) savedState.remainingMsAtSave.toFloat() / savedState.totalMs.toFloat() else 0f,
                    customHours = savedState.customHours,
                    customMinutes = savedState.customMinutes,
                    customSeconds = savedState.customSeconds,
                    isRestored = true
                )
            }
        }
    }

    // ── Public actions ──────────────────────────────────────────────

    /** Set the timer to a specific duration (in milliseconds). */
    fun setDuration(ms: Long) {
        if (ms <= 0) return
        val wasRunning = _uiState.value.isRunning
        stopUpdateLoop()
        endTimeElapsed = 0L
        endTimeWallClock = 0L
        // v2.5.6: cancel AlarmManager + clear state if the timer is running
        if (wasRunning) {
            scheduler?.cancel()
            stateStore?.clear()
            stopCountdownService()
        }
        _uiState.update {
            it.copy(
                totalMs = ms,
                remainingMs = ms,
                isRunning = false,
                isPaused = false,
                isFinished = false,
                progress = 1f,
                customHours = (ms / 3_600_000).toInt(),
                customMinutes = ((ms % 3_600_000) / 60_000).toInt(),
                customSeconds = ((ms % 60_000) / 1_000).toInt(),
                isRestored = false
            )
        }
        AppLogger.i(TAG, "Duration set to ${formatDuration(ms)} (wasRunning=$wasRunning)")
    }

    /** Set custom time from hours/minutes/seconds. */
    fun setCustomTime(hours: Int, minutes: Int, seconds: Int) {
        val ms = (hours * 3_600 + minutes * 60 + seconds) * 1_000L
        if (ms <= 0) return
        if (_uiState.value.isRunning) return // Don't change while running

        _uiState.update {
            it.copy(
                customHours = hours,
                customMinutes = minutes,
                customSeconds = seconds,
                totalMs = ms,
                remainingMs = ms,
                isFinished = false,
                progress = 1f
            )
        }
        AppLogger.i(TAG, "Custom time set: ${hours}u ${minutes}m ${seconds}s")
    }

    /** Start or resume the timer. */
    fun start() {
        val state = _uiState.value
        if (state.isRunning) return
        if (state.remainingMs <= 0) {
            AppLogger.w(TAG, "Cannot start: remaining time is 0")
            return
        }

        // v2.5.6: Compute wall clock end time (for persistence + AlarmManager)
        endTimeWallClock = io.github.calendaralarm.data.AppClock.now() + state.remainingMs
        endTimeElapsed = SystemClock.elapsedRealtime() + state.remainingMs

        _uiState.update {
            it.copy(isRunning = true, isPaused = false, isFinished = false, isRestored = false)
        }

        // v2.5.6 Story 4: Save state + schedule AlarmManager
        persistAndSchedule(state.remainingMs)
        startCountdownService(state.remainingMs)

        startUpdateLoop()
        AppLogger.i(TAG, "Timer started: ${formatDuration(state.remainingMs)} remaining (endTimeWallClock=$endTimeWallClock)")
    }

    /** Pause the timer. */
    fun pause() {
        val state = _uiState.value
        if (!state.isRunning) return

        // Calculate remaining time before stopping
        val remaining = endTimeElapsed - SystemClock.elapsedRealtime()
        stopUpdateLoop()
        endTimeElapsed = 0L

        _uiState.update {
            it.copy(
                remainingMs = remaining.coerceAtLeast(0L),
                isRunning = false,
                isPaused = true,
                progress = if (it.totalMs > 0) remaining.toFloat() / it.totalMs.toFloat() else 0f
            )
        }

        // v2.5.6 Story 4: Update state (paused) + cancel AlarmManager
        persistPausedState(remaining.coerceAtLeast(0L))
        scheduler?.cancel()
        stopCountdownService()

        AppLogger.i(TAG, "Timer paused at ${formatDuration(remaining)}")
    }

    /** Reset the timer to the full duration. */
    fun reset() {
        stopUpdateLoop()
        endTimeElapsed = 0L
        endTimeWallClock = 0L

        // v2.5.6 Story 4: Clear state + cancel AlarmManager
        stateStore?.clear()
        scheduler?.cancel()
        stopCountdownService()

        _uiState.update {
            it.copy(
                remainingMs = it.totalMs,
                isRunning = false,
                isPaused = false,
                isFinished = false,
                progress = 1f,
                isRestored = false
            )
        }
        AppLogger.i(TAG, "Timer reset to ${formatDuration(_uiState.value.totalMs)}")
    }

    /** Stop the timer (same as reset but logs differently). */
    fun stop() {
        stopUpdateLoop()
        endTimeElapsed = 0L
        endTimeWallClock = 0L

        // v2.5.6 Story 4: Clear state + cancel AlarmManager
        stateStore?.clear()
        scheduler?.cancel()
        stopCountdownService()

        _uiState.update {
            it.copy(
                remainingMs = it.totalMs,
                isRunning = false,
                isPaused = false,
                isFinished = false,
                progress = 1f,
                isRestored = false
            )
        }
        AppLogger.i(TAG, "Timer stopped")
    }

    /** Toggle between start and pause. */
    fun toggleStartPause() {
        if (_uiState.value.isRunning) pause() else start()
    }

    /**
     * v2.5.6: Force a state refresh (e.g. after returning to the app).
     * Recomputes remaining time based on wall clock.
     */
    fun refreshState() {
        val state = _uiState.value
        if (!state.isRunning) return

        val remaining = endTimeWallClock - io.github.calendaralarm.data.AppClock.now()
        if (remaining <= 0) {
            // Timer is finished
            _uiState.update {
                it.copy(
                    remainingMs = 0L,
                    isRunning = false,
                    isFinished = true,
                    progress = 0f
                )
            }
            AppLogger.i(TAG, "Timer completed (refresh): ${formatDuration(state.totalMs)}")
            fireFinished("refresh")
        } else {
            val progress = if (state.totalMs > 0) {
                remaining.toFloat() / state.totalMs.toFloat()
            } else 0f
            _uiState.update {
                it.copy(
                    remainingMs = remaining,
                    progress = progress.coerceIn(0f, 1f)
                )
            }
        }
    }

    // ── v2.5.6: Persistence helpers ─────────────────────────────────

    /**
     * v2.5.6: Save state in TimerStateStore + schedule AlarmManager.
     */
    private fun persistAndSchedule(remainingMs: Long) {
        val store = stateStore ?: return
        val sched = scheduler ?: return
        val state = _uiState.value

        val timerState = TimerStateStore.TimerState(
            isRunning = true,
            endTimeWallClock = endTimeWallClock,
            totalMs = state.totalMs,
            remainingMsAtSave = remainingMs,
            customHours = state.customHours,
            customMinutes = state.customMinutes,
            customSeconds = state.customSeconds
        )
        store.save(timerState)
        sched.schedule(endTimeWallClock)
    }

    /**
     * v2.5.6: Save paused state (without scheduling AlarmManager).
     */
    private fun persistPausedState(remainingMs: Long) {
        val store = stateStore ?: return
        val state = _uiState.value

        val timerState = TimerStateStore.TimerState(
            isRunning = false,  // Paused — no AlarmManager entry
            endTimeWallClock = 0L,
            totalMs = state.totalMs,
            remainingMsAtSave = remainingMs,
            customHours = state.customHours,
            customMinutes = state.customMinutes,
            customSeconds = state.customSeconds
        )
        store.save(timerState)
    }

    // ── Internal helpers ────────────────────────────────────────────

    private fun startUpdateLoop() {
        stopUpdateLoop()
        updateJob = viewModelScope.launch {
            while (true) {
                val state = _uiState.value
                if (!state.isRunning) break

                // v2.5.6: Use wall clock time (consistent with TimerStateStore)
                val remaining = endTimeWallClock - io.github.calendaralarm.data.AppClock.now()
                if (remaining <= 0) {
                    // Timer finished
                    _uiState.update {
                        it.copy(
                            remainingMs = 0L,
                            isRunning = false,
                            isFinished = true,
                            progress = 0f
                        )
                    }
                    // v2.7.0: idempotent firing (no-op if the AlarmManager receiver already fired)
                    AppLogger.i(TAG, "Timer completed: ${formatDuration(state.totalMs)}")
                    fireFinished("viewmodel")
                    break
                } else {
                    val progress = if (state.totalMs > 0) {
                        remaining.toFloat() / state.totalMs.toFloat()
                    } else 0f
                    _uiState.update {
                        it.copy(
                            remainingMs = remaining,
                            progress = progress.coerceIn(0f, 1f)
                        )
                    }
                }
                delay(100) // Update 10x per second for smooth progress
            }
        }
    }

    private fun stopUpdateLoop() {
        updateJob?.cancel()
        updateJob = null
    }

    override fun onCleared() {
        super.onCleared()
        stopUpdateLoop()
        // v2.5.6: do NOT clear state here — the timer must survive ViewModel destroy
        // (ViewModel is destroyed on config changes, but the timer must keep running)
        AppLogger.i(TAG, "TimerViewModel cleared (state preserved in TimerStateStore)")
    }

    companion object {
        /** Format milliseconds as a human-readable duration. */
        /** v2.7.0: max. delay at which a missed timer still fires on app start. */
        private const val LATE_FIRE_WINDOW_MS = 5 * 60 * 1000L

        fun formatDuration(ms: Long): String {
            val totalSeconds = (ms / 1000).toInt()
            val hours = totalSeconds / 3600
            val minutes = (totalSeconds % 3600) / 60
            val seconds = totalSeconds % 60
            return if (hours > 0) {
                String.format(java.util.Locale.getDefault(), "%d:%02d:%02d", hours, minutes, seconds)
            } else {
                String.format(java.util.Locale.getDefault(), "%02d:%02d", minutes, seconds)
            }
        }
    }
}
