package com.example.intervaltimer

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import java.util.Locale

enum class Mode { TIMER, STOPWATCH }
enum class Status { IDLE, RUNNING, PAUSED, FINISHED }

data class UiState(
    val mode: Mode = Mode.TIMER,
    val status: Status = Status.IDLE,
    val elapsedMs: Long = 0L,
    val totalMs: Long = 0L,
    val intervalMs: Long = 0L,
    val laps: List<Long> = emptyList()
)

/** Shared between the service (writes) and the screen (reads). */
object TimerState {
    val state = MutableStateFlow(UiState())
}

object Prefs {
    fun of(ctx: Context): SharedPreferences =
        ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)
}

/** mm:ss (or h:mm:ss). Use roundUp=true for countdowns so 0:00 only shows at the very end. */
fun fmt(ms: Long, roundUp: Boolean): String {
    val s = if (roundUp) (ms + 999) / 1000 else ms / 1000
    val h = s / 3600
    val m = (s % 3600) / 60
    val sec = s % 60
    return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, sec)
    else String.format(Locale.US, "%02d:%02d", m, sec)
}

/** Text read aloud, e.g. "1 minute 30 seconds". */
fun spoken(ms: Long): String {
    val s = ms / 1000
    val h = s / 3600
    val m = (s % 3600) / 60
    val sec = s % 60
    val parts = mutableListOf<String>()
    if (h > 0) parts.add("$h hour" + if (h > 1) "s" else "")
    if (m > 0) parts.add("$m minute" + if (m > 1) "s" else "")
    if (sec > 0 || parts.isEmpty()) parts.add("$sec second" + if (sec != 1L) "s" else "")
    return parts.joinToString(" ")
}
