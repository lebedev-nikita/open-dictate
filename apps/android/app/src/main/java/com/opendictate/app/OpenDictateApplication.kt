package com.opendictate.app

import android.app.Application
import com.opendictate.app.audio.LastDictationAudioStore
import com.opendictate.app.data.TranscriptHistoryStore
import com.opendictate.app.model.ErrorContext
import com.opendictate.app.model.FailureDetails
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class OpenDictateApplication : Application() {
    private val errorHistoryScope = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))

    fun recordError(context: ErrorContext, failure: FailureDetails) {
        errorHistoryScope.launch { runCatching { errorHistoryStore.add(context, failure) } }
    }
    val replacementStore: com.opendictate.app.data.ReplacementStore by lazy {
        com.opendictate.app.data.ReplacementStore(this).apply { onChange = { driveSync.replacementsChanged() } }
    }
    val driveSync: com.opendictate.app.data.GoogleDriveSync by lazy { com.opendictate.app.data.GoogleDriveSync(this, replacementStore) }

    override fun onCreate() {
        super.onCreate()
        driveSync
    }
    val chatGPT by lazy { com.opendictate.app.chatgpt.ChatGPTSession(this) }
    val transcriptHistoryStore by lazy { TranscriptHistoryStore(this) }
    val errorHistoryStore by lazy { com.opendictate.app.data.ErrorHistoryStore(this) }
    val lastDictationAudioStore by lazy { LastDictationAudioStore(filesDir) }
}
