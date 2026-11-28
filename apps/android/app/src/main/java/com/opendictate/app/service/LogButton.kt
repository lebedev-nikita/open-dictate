package com.opendictate.app.service

import android.app.ActivityManager
import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.widget.Toast
import androidx.core.app.NotificationCompat
import com.opendictate.app.MainActivity
import com.opendictate.app.R
import com.opendictate.app.data.SettingsStore
import com.opendictate.app.model.LogButtonEdge
import com.opendictate.app.model.LogButtonSignature
import com.opendictate.app.model.LogLine
import com.opendictate.app.model.LogPressDetector
import java.io.Closeable
import java.io.IOException
import kotlin.concurrent.thread
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/** A press or release of a bound log button, at its time of day in the log. */
internal data class LogButtonEvent(val signature: LogButtonSignature, val edge: LogButtonEdge, val timeMs: Long)

/** Runs logcat and hands over its lines on a background thread until closed. */
internal class LogReader(
    arguments: List<String>,
    private val onLine: (LogLine) -> Unit,
    private val onEnd: () -> Unit = {},
) : Closeable {
    @Volatile
    private var closed = false
    private val process = runCatching {
        ProcessBuilder(listOf("logcat", "-v", "time") + arguments).redirectErrorStream(true).start()
    }.getOrNull()

    init {
        thread(name = "logcat", isDaemon = true) {
            try {
                process?.inputStream?.bufferedReader()?.useLines { lines ->
                    for (text in lines) {
                        if (closed) break
                        LogLine.parse(text)?.let(onLine)
                    }
                }
            } catch (_: IOException) {
                // Closing destroys the process and ends the stream.
            } finally {
                process?.destroy()
                if (!closed) onEnd()
            }
        }
    }

    override fun close() {
        closed = true
        process?.destroy()
    }

    companion object {
        /** Recent lines first, so a learner also sees what the user's last taps left in the log. */
        fun everything(recentLines: Int, onLine: (LogLine) -> Unit) = LogReader(listOf("-T", recentLines.toString()), onLine)
    }
}

/**
 * Watches the system log for buttons that Android does not deliver to apps. It needs READ_LOGS,
 * which only adb can grant, and it runs logcat only while such a button is bound. logcat itself
 * filters by the buttons' tags, so no other log line reaches the app.
 */
internal object LogButton {
    enum class Status {
        OFF,
        /** Android grants log access only while the app is open, and asks the user each time. */
        NEEDS_ACCESS,
        ACTIVE,
    }

    private const val READ_LOGS = "android.permission.READ_LOGS"
    private const val CHANNEL_ID = "log_button"
    private const val NOTIFICATION_ID = 43
    private const val ACCESS_TIMEOUT_MS = 60_000L
    private const val REMINDER_DELAY_MS = 3_000L

    /** Set while settings bind a button: presses are only shown there, for the user to try it. */
    var learning = false
    private val mutableLearningPresses = MutableSharedFlow<LogButtonEvent>(extraBufferCapacity = 8,
        onBufferOverflow = BufferOverflow.DROP_OLDEST)
    /** Presses and releases of bound buttons made while [learning]. */
    val learningPresses = mutableLearningPresses.asSharedFlow()

    private val mutableStatus = MutableStateFlow(Status.OFF)
    val status = mutableStatus.asStateFlow()

    private val handler = Handler(Looper.getMainLooper())
    private var application: Application? = null
    private var settings: SettingsStore? = null
    private var settingsObserver: Closeable? = null
    private var watched: List<LogButtonSignature> = emptyList()
    private var probe: LogReader? = null
    private var reader: LogReader? = null
    private val abandonProbe = Runnable {
        probe?.close()
        probe = null
        remindToOpen()
    }

    fun isPermitted(context: Context): Boolean =
        context.checkSelfPermission(READ_LOGS) == PackageManager.PERMISSION_GRANTED

    fun attach(application: Application) {
        if (this.application != null) return
        this.application = application
        settings = SettingsStore(application).also { store ->
            settingsObserver = store.observeButtonBindings { handler.post(::sync) }
        }
        sync()
    }

    /** Starts or stops watching to match the settings. Called again whenever the app comes forward. */
    fun sync() {
        val context = application ?: return
        val store = settings ?: return
        val signatures = store.buttonBindings.logSignatures.takeIf { isPermitted(context) }.orEmpty()
        if (signatures.isEmpty()) {
            stop()
            mutableStatus.value = Status.OFF
            notifications(context).cancel(NOTIFICATION_ID)
            return
        }
        if (signatures == watched && (reader != null || probe != null)) return
        stop()
        watched = signatures
        mutableStatus.value = Status.NEEDS_ACCESS
        if (!isInForeground()) return remindToOpen()
        // Android answers a declined request with silence, so access is confirmed by the first line
        // of another process before the filtered reader replaces this one.
        var confirmed = false
        lateinit var started: LogReader
        started = LogReader(listOf("-T", "1"), onLine = { line ->
            if (!confirmed && line.pid != Process.myPid()) {
                confirmed = true
                handler.post { if (probe === started) watch(context, signatures) }
            }
        })
        probe = started
        handler.postDelayed(abandonProbe, ACCESS_TIMEOUT_MS)
    }

    private fun watch(context: Context, signatures: List<LogButtonSignature>) {
        val detectors = signatures.associateWith { LogPressDetector(it.releaseLogged) }
        val filters = signatures.map { "${it.tag}:V" }.distinct()
        lateinit var started: LogReader
        started = LogReader(
            listOf("-T", "1") + filters + "*:S",
            onLine = { line ->
                for ((signature, detector) in detectors) {
                    if (!signature.matches(line)) continue
                    val edge = detector.onLine(line.timeMs) ?: continue
                    handler.post { press(context, LogButtonEvent(signature, edge, line.timeMs)) }
                }
            },
            onEnd = {
                handler.post {
                    if (reader === started) {
                        reader = null
                        mutableStatus.value = Status.NEEDS_ACCESS
                        remindToOpen()
                    }
                }
            },
        )
        reader = started
        // Access lasts while a reader is open, so the probe closes only now.
        handler.removeCallbacks(abandonProbe)
        probe?.close()
        probe = null
        mutableStatus.value = Status.ACTIVE
        notifications(context).cancel(NOTIFICATION_ID)
    }

    private fun stop() {
        handler.removeCallbacks(abandonProbe)
        handler.removeCallbacks(showReminder)
        probe?.close()
        reader?.close()
        probe = null
        reader = null
        watched = emptyList()
    }

    private fun press(context: Context, event: LogButtonEvent) {
        if (mutableStatus.value != Status.ACTIVE || event.signature !in watched) return
        if (learning) {
            mutableLearningPresses.tryEmit(event)
            return
        }
        val service = OpenDictateAccessibilityService.running
        when {
            service != null -> service.onLogButton(event)
            event.edge == LogButtonEdge.PRESS -> Toast.makeText(context, R.string.accessibility_enable_hint, Toast.LENGTH_SHORT).show()
        }
    }

    private fun isInForeground(): Boolean {
        val state = ActivityManager.RunningAppProcessInfo()
        ActivityManager.getMyMemoryState(state)
        return state.importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
    }

    /** The button stays dead until the app is opened, which the user cannot otherwise know. */
    private fun remindToOpen() {
        handler.removeCallbacks(showReminder)
        // A start in the background may be the app on its way to the screen.
        handler.postDelayed(showReminder, REMINDER_DELAY_MS)
    }

    private val showReminder = Runnable {
        val context = application ?: return@Runnable
        if (mutableStatus.value != Status.NEEDS_ACCESS || isInForeground()) return@Runnable
        val manager = notifications(context)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, context.getString(R.string.dictation_key_title), NotificationManager.IMPORTANCE_LOW),
        )
        val open = PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val text = context.getString(R.string.log_button_needs_access_notification)
        runCatching {
            manager.notify(NOTIFICATION_ID, NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_mic_chatgpt_24)
                .setContentTitle(context.getString(R.string.dictation_key_title))
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setOnlyAlertOnce(true)
                .setAutoCancel(true)
                .setContentIntent(open)
                .build())
        }
    }

    private fun notifications(context: Context) = context.getSystemService(NotificationManager::class.java)
}
