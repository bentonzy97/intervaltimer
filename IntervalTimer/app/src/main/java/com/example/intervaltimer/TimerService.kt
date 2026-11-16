package com.example.intervaltimer

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.speech.tts.TextToSpeech
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * The heart of the app. Runs as a foreground service so it keeps going
 * (and beeping) when the screen is off or the app is minimised.
 * Everything runs on the main thread, so there are no threading races.
 */
class TimerService : Service() {

    companion object {
        const val ACTION_START = "com.example.intervaltimer.START"
        const val ACTION_PAUSE = "com.example.intervaltimer.PAUSE"
        const val ACTION_RESUME = "com.example.intervaltimer.RESUME"
        const val ACTION_RESET = "com.example.intervaltimer.RESET"
        const val EXTRA_MODE = "mode"
        const val EXTRA_TOTAL = "total"
        const val EXTRA_INTERVAL = "interval"
        private const val CHANNEL = "timer"
        private const val NOTIF_ID = 1
        private const val DONE_ID = 2
    }

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var loop: Job? = null
    private var stopJob: Job? = null

    private var startedAt = 0L      // clock reading when the current run segment began
    private var accumulated = 0L    // time banked from earlier segments (before pauses)
    private var lapsFired = 0       // how many interval boundaries we've already handled
    private var lastSecond = -1L

    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var tone: ToneGenerator? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "Timer", NotificationManager.IMPORTANCE_LOW)
        )
        tts = TextToSpeech(this) { status ->
            ttsReady = status == TextToSpeech.SUCCESS
            if (ttsReady) tts?.language = Locale.getDefault()
        }
        tone = try {
            ToneGenerator(AudioManager.STREAM_MUSIC, 100)
        } catch (e: Exception) {
            null
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val mode = if (intent.getStringExtra(EXTRA_MODE) == "stopwatch") Mode.STOPWATCH else Mode.TIMER
                accumulated = 0L
                lapsFired = 0
                lastSecond = -1L
                TimerState.state.value = UiState(
                    mode = mode,
                    status = Status.RUNNING,
                    totalMs = intent.getLongExtra(EXTRA_TOTAL, 0L),
                    intervalMs = intent.getLongExtra(EXTRA_INTERVAL, 0L)
                )
                beginRun()
            }
            ACTION_PAUSE -> pauseRun()
            ACTION_RESUME -> if (TimerState.state.value.status == Status.PAUSED) beginRun()
            ACTION_RESET -> resetAll()
            else -> stopSelf()
        }
        return START_NOT_STICKY
    }

    // ---------- run control ----------

    private fun beginRun() {
        stopJob?.cancel()
        startedAt = SystemClock.elapsedRealtime()
        TimerState.state.value = TimerState.state.value.copy(status = Status.RUNNING)
        goForeground()
        acquireWake()
        loop?.cancel()
        loop = scope.launch {
            while (isActive) {
                if (!tick()) break
                delay(50)
            }
        }
    }

    private fun pauseRun() {
        if (TimerState.state.value.status != Status.RUNNING) return
        accumulated += SystemClock.elapsedRealtime() - startedAt
        loop?.cancel()
        releaseWake()
        TimerState.state.value = TimerState.state.value.copy(status = Status.PAUSED, elapsedMs = accumulated)
        updateNotification()
    }

    private fun resetAll() {
        loop?.cancel()
        stopJob?.cancel()
        releaseWake()
        tts?.stop()
        accumulated = 0L
        lapsFired = 0
        TimerState.state.value = UiState()
        stopForeground(STOP_FOREGROUND_REMOVE)
        getSystemService(NotificationManager::class.java).cancel(DONE_ID)
        stopSelf()
    }

    /** Called ~20 times a second. Returns false when the run is over. */
    private fun tick(): Boolean {
        val s = TimerState.state.value
        val isTimer = s.mode == Mode.TIMER
        var elapsed = accumulated + (SystemClock.elapsedRealtime() - startedAt)
        if (isTimer && elapsed > s.totalMs) elapsed = s.totalMs

        var laps = s.laps
        if (s.intervalMs > 0) {
            val count = (elapsed / s.intervalMs).toInt()
            while (lapsFired < count) {
                lapsFired++
                val t = lapsFired * s.intervalMs
                laps = laps + t
                // A boundary that lands exactly on the end gets the final alert instead.
                if (!(isTimer && t >= s.totalMs)) intervalAlert(t)
            }
        }

        val finished = isTimer && elapsed >= s.totalMs
        TimerState.state.value = s.copy(
            elapsedMs = elapsed,
            laps = laps,
            status = if (finished) Status.FINISHED else Status.RUNNING
        )

        if (finished) {
            finishRun()
            return false
        }
        val sec = elapsed / 1000
        if (sec != lastSecond) {
            lastSecond = sec
            updateNotification()
        }
        return true
    }

    private fun finishRun() {
        accumulated = 0L
        releaseWake()
        finalAlert()
        stopForeground(STOP_FOREGROUND_REMOVE)
        val done = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle("Time's up")
            .setContentText("Your timer has finished")
            .setAutoCancel(true)
            .setContentIntent(openAppIntent())
            .build()
        getSystemService(NotificationManager::class.java).notify(DONE_ID, done)
        // Give the spoken "time's up" a few seconds to finish before shutting down.
        stopJob = scope.launch {
            delay(5000)
            stopSelf()
        }
    }

    // ---------- alerts ----------

    private fun intervalAlert(elapsedAtBoundary: Long) {
        val p = Prefs.of(this)
        if (p.getBoolean("sound", true)) tone?.startTone(ToneGenerator.TONE_PROP_BEEP, 250)
        if (p.getBoolean("vibrate", true)) vibrate(longArrayOf(0, 250))
        if (p.getBoolean("voice", true) && ttsReady) {
            tts?.speak(spoken(elapsedAtBoundary), TextToSpeech.QUEUE_FLUSH, null, "lap")
        }
    }

    private fun finalAlert() {
        val p = Prefs.of(this)
        if (p.getBoolean("sound", true)) tone?.startTone(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 1500)
        if (p.getBoolean("vibrate", true)) vibrate(longArrayOf(0, 400, 200, 400, 200, 800))
        if (p.getBoolean("voice", true) && ttsReady) {
            tts?.speak("Time's up", TextToSpeech.QUEUE_FLUSH, null, "done")
        }
    }

    @Suppress("DEPRECATION")
    private fun vibrate(pattern: LongArray) {
        try {
            val v = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            v.vibrate(VibrationEffect.createWaveform(pattern, -1))
        } catch (_: Exception) {
        }
    }

    // ---------- notification, foreground, wake lock ----------

    private fun goForeground() {
        val n = buildNotification()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIF_ID, n)
        }
    }

    private fun updateNotification() {
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification())
    }

    private fun buildNotification(): Notification {
        val s = TimerState.state.value
        val isTimer = s.mode == Mode.TIMER
        val shown = if (isTimer) fmt(s.totalMs - s.elapsedMs, true) else fmt(s.elapsedMs, false)
        val running = s.status == Status.RUNNING
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle(if (isTimer) "Timer" else "Stopwatch")
            .setContentText(if (running) shown else "$shown (paused)")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openAppIntent())
            .addAction(0, if (running) "Pause" else "Resume", actionIntent(if (running) ACTION_PAUSE else ACTION_RESUME))
            .addAction(0, "Stop", actionIntent(ACTION_RESET))
            .build()
    }

    private fun actionIntent(action: String): PendingIntent =
        PendingIntent.getService(
            this, action.hashCode(),
            Intent(this, TimerService::class.java).setAction(action),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    private fun openAppIntent(): PendingIntent =
        PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE
        )

    private fun acquireWake() {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "intervaltimer:run").apply {
            acquire(6 * 60 * 60 * 1000L)
        }
    }

    private fun releaseWake() {
        wakeLock?.let { if (it.isHeld) it.release() }
    }

    override fun onDestroy() {
        scope.cancel()
        releaseWake()
        tts?.shutdown()
        tone?.release()
        super.onDestroy()
    }
}
