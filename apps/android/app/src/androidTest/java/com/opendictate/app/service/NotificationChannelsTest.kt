package com.opendictate.app.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.opendictate.app.MainActivity
import com.opendictate.app.R
import org.junit.Assert.*
import org.junit.Test

class NotificationChannelsTest {
    @Test fun serviceRetiresModelMessagesAndKeepsErrorsAndRequiredSilentForegroundChannel() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("transformation_messages",
            "Legacy messages", NotificationManager.IMPORTANCE_DEFAULT))
        val intent = Intent(context, DictationForegroundService::class.java)
        ActivityScenario.launch(MainActivity::class.java).use {
            try {
                // Create the real service without starting audio or making a provider request.
                context.startService(intent)
                val deadline = SystemClock.elapsedRealtime() + 5_000
                while ((manager.getNotificationChannel("transformation_messages") != null ||
                    manager.getNotificationChannel("errors") == null) &&
                    SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(20)
                assertNull(manager.getNotificationChannel("transformation_messages"))
                val errors = requireNotNull(manager.getNotificationChannel("errors"))
                assertEquals(context.getString(R.string.notification_errors_channel), errors.name.toString())
                assertEquals(NotificationManager.IMPORTANCE_DEFAULT, errors.importance)
                val foreground = requireNotNull(manager.getNotificationChannel("dictation"))
                assertEquals(NotificationManager.IMPORTANCE_LOW, foreground.importance)
                assertNull(foreground.sound)
                assertTrue(manager.activeNotifications.none { it.id == 42 })
            } finally {
                context.stopService(intent)
            }
        }
    }
}
