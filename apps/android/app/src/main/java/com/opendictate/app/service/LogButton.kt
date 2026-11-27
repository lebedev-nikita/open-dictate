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
import com.opendictate.app.model.LogButtonSignature
import com.opendictate.app.model.LogLine
import com.opendictate.app.model.LogPressDetector
import java.io.Closeable
import java.io.IOException
import kotlin.concurrent.thread
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

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
 * Watches the system log for a button that Android does not deliver to apps. It needs READ_LOGS,
 * which only adb can grant, and it runs logcat only while the user has the feature on. logcat itself
 * filters by the button's tag, so no other log line reaches the app.
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

    /** Set while settings learn a button: presses are only counted there, for the user to check. */
    var learning = false
        set(value) {
            field = value
            mutableLearningPresses.value = 0
        }
    private val mutableLearningPresses = MutableStateFlow(0)
    val learningPresses = mutableLearningPresses.asStateFlow()

    private val mutableStatus = MutableStateFlow(Status.OFF)
    val status = mutableStatus.asStateFlow()

    private val handler = Handler(Looper.getMainLooper())
    private var application: Application? = null
    private var settings: SettingsStore? = null
    private var settingsObserver: Closeable? = null
    private var watched: LogButtonSignature? = null
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
            settingsObserver = store.observeLogButton { handler.post(::sync) }
        }
        sync()
    }

    /** Starts or stops watching to match the settings. Called again whenever the app comes forward. */
    fun sync() {
        val context = application ?: return
        val store = settings ?: return
        val signature = store.logButtonSignature?.takeIf { store.logButtonEnabled && isPermitted(context) }
        if (signature == null) {
            stop()
            mutableStatus.value = Status.OFF
            notifications(context).cancel(NOTIFICATION_ID)
            return
        }
        if (signature == watched && (reader != null || probe != null)) return
        stop()
        watched = signature
        mutableStatus.value = Status.NEEDS_ACCESS
        if (!isInForeground()) return remindToOpen()
        // Android answers a declined request with silence, so access is confirmed by the first line
        // of another process before the filtered reader replaces this one.
        var confirmed = false
        lateinit var started: LogReader
        started = LogReader(listOf("-T", "1"), onLine = { line ->
            if (!confirmed && line.pid != Process.myPid()) {
                confirmed = true
                handler.post { if (probe === started) watch(context, signature) }
            }
        })
        probe = started
        handler.postDelayed(abandonProbe, ACCESS_TIMEOUT_MS)
    }

    private fun watch(context: Context, signature: LogButtonSignature) {
        val detector = LogPressDetector(signature.releaseLogged)
        lateinit var started: LogReader
        started = LogReader(
            listOf("-T", "1", "${signature.tag}:V", "*:S"),
            onLine = { line ->
                if (signature.matches(line) && detector.onLine(line.timeMs)) handler.post { press(context, line.timeMs) }
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
        watched = null
    }

    private fun press(context: Context, timeMs: Long) {
        if (mutableStatus.value != Status.ACTIVE) return
        if (learning) {
            mutableLearningPresses.value++
            return
        }
        val service = OpenDictateAccessibilityService.running
        if (service == null) Toast.makeText(context, R.string.accessibility_enable_hint, Toast.LENGTH_SHORT).show()
        else service.onTapButtonPress(timeMs)
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
            NotificationChannel(CHANNEL_ID, context.getString(R.string.log_button_title), NotificationManager.IMPORTANCE_LOW),
        )
        val open = PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val text = context.getString(R.string.log_button_needs_access_notification)
        runCatching {
            manager.notify(NOTIFICATION_ID, NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_mic_chatgpt_24)
                .setContentTitle(context.getString(R.string.log_button_title))
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
