package com.opendictate.app

import android.app.Application
import com.opendictate.app.audio.LastDictationAudioStore
import com.opendictate.app.data.TranscriptHistoryStore

class OpenDictateApplication : Application() {
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
    val lastDictationAudioStore by lazy { LastDictationAudioStore(filesDir) }
}
