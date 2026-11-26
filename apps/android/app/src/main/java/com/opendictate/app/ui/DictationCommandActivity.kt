package com.opendictate.app.ui

import android.app.Activity
import android.os.Bundle
import com.opendictate.app.model.DictationCommand
import com.opendictate.app.service.DictationCommands

/**
 * Accepts the dictation commands from apps that can only open activities, such as button
 * remappers. It has no window and closes at once. Opening it switches tasks, so Android closes the
 * keyboard; a session it starts therefore continues while its field keeps focus.
 */
class DictationCommandActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) {
            DictationCommand.fromAction(intent?.action)?.let {
                DictationCommands.perform(applicationContext, it, keyboardOptional = true)
            }
        }
        // Theme.NoDisplay requires finishing before the activity resumes.
        finish()
    }
}
