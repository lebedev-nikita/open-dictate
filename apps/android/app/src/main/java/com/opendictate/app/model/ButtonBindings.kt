package com.opendictate.app.model

import org.json.JSONArray
import org.json.JSONObject

/** What a phone button can run. Every action except [AGENT_COMMAND] works in the focused text field. */
enum class ButtonAction {
    DICTATION,
    /** Dictation with post-processing set the other way than for ordinary dictation. */
    ALTERNATE_DICTATION,
    VOICE_EDIT,
    AGENT_COMMAND,
    ;

    val needsField: Boolean get() = this != AGENT_COMMAND
}

/**
 * How a button runs its action. A press starts the action and the next press of a bound button
 * finishes it; a hold runs the action until the button comes up.
 */
enum class ButtonGesture { PRESS, HOLD, DOUBLE_PRESS }

/** A phone button: a key Android reports to the app, or one found by its line in the system log. */
sealed interface PhoneButton {
    data class Key(val keyCode: Int) : PhoneButton
    data class Log(val signature: LogButtonSignature) : PhoneButton

    /**
     * Gestures the button can tell apart. A hold needs the release, which some log buttons do not
     * leave. Volume keys keep their short press for the volume, so they offer only a hold.
     */
    val gestures: Set<ButtonGesture>
        get() = when {
            this is Key && DictationKeys.holdsBeforeStart(keyCode) -> setOf(ButtonGesture.HOLD)
            this is Log && !signature.releaseLogged -> setOf(ButtonGesture.PRESS, ButtonGesture.DOUBLE_PRESS)
            else -> ButtonGesture.entries.toSet()
        }

    /** A short press still does what the key does without the app. */
    val keepsShortPress: Boolean get() = this is Key && DictationKeys.holdsBeforeStart(keyCode)
}

data class ButtonBinding(val action: ButtonAction, val button: PhoneButton, val gesture: ButtonGesture)

/** Which button and gesture run each action. An action has at most one; a button's gesture runs one action. */
data class ButtonBindings(val bindings: List<ButtonBinding> = emptyList()) {
    fun forAction(action: ButtonAction): ButtonBinding? = bindings.firstOrNull { it.action == action }

    fun actionFor(button: PhoneButton, gesture: ButtonGesture): ButtonAction? =
        bindings.firstOrNull { it.button == button && it.gesture == gesture }?.action

    /** Buttons in the order they were first bound. */
    val buttons: List<PhoneButton> get() = bindings.map { it.button }.distinct()

    val logSignatures: List<LogButtonSignature> get() = buttons.filterIsInstance<PhoneButton.Log>().map { it.signature }

    val keyCodes: Set<Int> get() = buttons.filterIsInstance<PhoneButton.Key>().mapTo(HashSet()) { it.keyCode }

    /** Gestures of [button] that run an action other than [action]. */
    fun takenGestures(button: PhoneButton, action: ButtonAction): Map<ButtonGesture, ButtonAction> =
        bindings.filter { it.button == button && it.action != action }.associate { it.gesture to it.action }

    /**
     * The gesture to use when [action] moves to [button]: [preferred] when the button offers it and
     * no other action uses it, otherwise the first such gesture, or null when none is left.
     */
    fun freeGesture(action: ButtonAction, button: PhoneButton, preferred: ButtonGesture?): ButtonGesture? {
        val free = button.gestures - takenGestures(button, action).keys
        return preferred?.takeIf { it in free } ?: ButtonGesture.entries.firstOrNull { it in free }
    }

    /** Returns the bindings with [action] on [button] and [gesture], or these when that is not possible. */
    fun bind(action: ButtonAction, button: PhoneButton, gesture: ButtonGesture): ButtonBindings {
        if (gesture !in button.gestures || gesture in takenGestures(button, action)) return this
        val binding = ButtonBinding(action, button, gesture)
        val index = bindings.indexOfFirst { it.action == action }
        return ButtonBindings(if (index < 0) bindings + binding else bindings.toMutableList().also { it[index] = binding })
    }

    fun unbind(action: ButtonAction): ButtonBindings = ButtonBindings(bindings.filter { it.action != action })

    fun toJson(): String = JSONArray(bindings.map { binding ->
        JSONObject()
            .put("action", binding.action.name)
            .put("gesture", binding.gesture.name)
            .apply {
                when (val button = binding.button) {
                    is PhoneButton.Key -> put("key", button.keyCode)
                    is PhoneButton.Log -> put("logTag", button.signature.tag)
                        .put("logMessage", button.signature.message)
                        .put("logRelease", button.signature.releaseLogged)
                }
            }
    }).toString()

    companion object {
        fun fromJson(json: String?): ButtonBindings {
            val array = runCatching { JSONArray(json ?: return ButtonBindings()) }.getOrNull() ?: return ButtonBindings()
            val parsed = (0 until array.length()).mapNotNull { index ->
                val item = array.optJSONObject(index) ?: return@mapNotNull null
                val action = ButtonAction.entries.firstOrNull { it.name == item.optString("action") } ?: return@mapNotNull null
                val gesture = ButtonGesture.entries.firstOrNull { it.name == item.optString("gesture") } ?: return@mapNotNull null
                val button = when {
                    item.has("key") -> PhoneButton.Key(item.optInt("key")).takeIf { DictationKeys.isBindable(it.keyCode) }
                    item.has("logTag") -> LogButtonSignature(item.optString("logTag"), item.optString("logMessage"),
                        item.optBoolean("logRelease")).takeIf { LogButtonSignature.isFilterableTag(it.tag) }?.let(PhoneButton::Log)
                    else -> null
                } ?: return@mapNotNull null
                ButtonBinding(action, button, gesture)
            }
            // Bound through the same rules, so stored data cannot break them.
            return parsed.fold(ButtonBindings()) { bindings, binding -> bindings.bind(binding.action, binding.button, binding.gesture) }
        }

        /**
         * The bindings of the single button bound before actions were chosen: a key dictated while
         * held, and a log button dictated with a press and dictated a command when held or, if it
         * cannot be held, pressed twice.
         */
        fun fromLegacy(keyCode: Int, signature: LogButtonSignature?): ButtonBindings = when {
            signature != null -> {
                val button = PhoneButton.Log(signature)
                ButtonBindings()
                    .bind(ButtonAction.DICTATION, button, ButtonGesture.PRESS)
                    .bind(ButtonAction.AGENT_COMMAND, button,
                        if (signature.releaseLogged) ButtonGesture.HOLD else ButtonGesture.DOUBLE_PRESS)
            }
            DictationKeys.isBindable(keyCode) -> ButtonBindings().bind(ButtonAction.DICTATION, PhoneButton.Key(keyCode), ButtonGesture.HOLD)
            else -> ButtonBindings()
        }
    }
}
