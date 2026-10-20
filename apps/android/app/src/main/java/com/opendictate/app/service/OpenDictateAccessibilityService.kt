package com.opendictate.app.service

import android.Manifest
import android.accessibilityservice.AccessibilityService
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PersistableBundle
import android.os.SystemClock
import android.util.Log
import android.view.Gravity
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.opendictate.app.MainActivity
import com.opendictate.app.OpenDictateApplication
import com.opendictate.app.R
import com.opendictate.app.data.SecureApiKeyStore
import com.opendictate.app.data.SettingsStore
import com.opendictate.app.data.addToDictionary
import com.opendictate.app.model.AppFilter
import com.opendictate.app.overlay.DictationOverlayMenuView
import com.opendictate.app.overlay.DictationOverlayView
import com.opendictate.app.overlay.OverlayMotion
import com.opendictate.app.overlay.OverlayPositionMotion
import com.opendictate.app.overlay.OverlayVisibilitySession
import java.io.Closeable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class OpenDictateAccessibilityService : AccessibilityService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val handler = Handler(Looper.getMainLooper())
    private val pendingTextRestoration = PendingEditableTextRestoration()
    private var textRestorationScheduled = false
    private lateinit var windowManager: WindowManager
    private lateinit var settings: SettingsStore
    private var appFilter = AppFilter()
    private var appFilterObserver: Closeable? = null
    private var buttonAppearanceObserver: Closeable? = null
    private var overlay: DictationOverlayView? = null
    private var overlayMenu: DictationOverlayMenuView? = null
    private var overlayAttached = false
    private var overlayMenuAttached = false
    private var overlayMenuLayoutParams: WindowManager.LayoutParams? = null
    private var overlayWindowOffsetY: Int? = null
    private val overlayVisibleDisplayFrame = Rect()
    private var overlayTopInsetPx = 0
    private var overlayLayoutParams: WindowManager.LayoutParams? = null
    private var overlayPositionMotion: OverlayPositionMotion? = null
    private var overlayPositionAnimationScheduled = false
    private var lastOverlayPositionFrameNanos = 0L
    private var overlayUpdateScheduled = false
    private val overlayVisibilitySession = OverlayVisibilitySession()
    private var overlayMenuExpanded = false
    private var transformationAvailable = false
    private var dictionaryAvailable = false
    private var overlayTargetPackage: CharSequence? = null
    private var editableSnapshot: EditableTextSnapshot? = null
    private var targetPackage: CharSequence? = null
    private var activeSessionId = 0L
    private var ignoredSessionId = 0L
    private val transcriptDelivery = TranscriptDeliveryTracker()

    override fun onServiceConnected() {
        super.onServiceConnected()
        windowManager = getSystemService(WindowManager::class.java)
        settings = SettingsStore(this)
        appFilter = settings.appFilter
        appFilterObserver = settings.observeAppFilter { filter ->
            appFilter = filter
            scheduleOverlayUpdate()
        }
        buttonAppearanceObserver = settings.observeDictationButtonAppearance {
            overlay?.setAppearance(it)
            scheduleOverlayUpdate()
        }
        overlay = DictationOverlayView(
            context = this,
            onDictationClick = ::onOverlayTapped,
            onOperationCancel = ::onOverlayCancelled,
            onDismiss = ::onOverlayDismissed,
            onMenuToggle = ::onOverlayMenuToggled,
        )
        overlayMenu = DictationOverlayMenuView(
            context = this,
            onTransformationClick = ::onTransformationTapped,
            onAddToDictionaryClick = ::onAddToDictionaryTapped,
            onPasteLastClick = ::onPasteLastTapped,
            onLastDictationClick = ::onLastDictationTapped,
            onAlternateDictationClick = ::onAlternateDictationTapped,
        )
        scope.launch {
            DictationStateBus.state.collectLatest { state ->
                overlay?.render(state)
                overlayMenu?.render(state)
                handleTranscriptState(state)
            }
        }
        scheduleOverlayUpdate()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        scheduleEditableSnapshotRestoration()
        scheduleOverlayUpdate()
    }

    override fun onInterrupt() {
        stopDictation()
    }

    override fun onDestroy() {
        appFilterObserver?.close()
        buttonAppearanceObserver?.close()
        handler.removeCallbacksAndMessages(null)
        removeOverlay()
        scope.coroutineContext[Job]?.cancel()
        super.onDestroy()
    }

    private fun scheduleOverlayUpdate() {
        if (overlayUpdateScheduled) return
        overlayUpdateScheduled = true
        handler.postDelayed(updateOverlayRunnable, OVERLAY_UPDATE_DELAY_MS)
    }

    private val updateOverlayRunnable = Runnable {
        overlayUpdateScheduled = false
        val ime = windows.firstOrNull { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD }
        // Node queries can block the main thread; there is no eligible target without an IME.
        val focused = if (ime != null) findFocusedEditable() else null
        val hasEligibleTarget = ime != null && focused?.isTextInput() == true &&
            appFilter.allows(focused.packageName)
        if (overlayVisibilitySession.shouldShow(hasEligibleTarget)) {
            showOrMoveOverlay(ime, requireNotNull(focused))
        } else {
            if (!hasEligibleTarget) {
                overlayMenuExpanded = false
                transformationAvailable = false
                dictionaryAvailable = false
                overlayTargetPackage = null
                overlay?.setMenuState(expanded = false)
            }
            removeOverlay()
            if (!hasEligibleTarget && DictationStateBus.state.value.isActive) {
                if (ime == null) onOverlayCancelled() else stopDictation()
            }
        }
    }

    private fun showOrMoveOverlay(
        ime: AccessibilityWindowInfo?,
        focused: AccessibilityNodeInfo,
    ) {
        val view = overlay ?: return
        view.setAppearance(settings.dictationButtonAppearance)
        if (view.isAttachedToWindow) {
            // WindowManager clamps floating windows below system decorations. Keep our requested
            // button position inside the same frame so menu offsets use the rendered anchor.
            view.getWindowVisibleDisplayFrame(overlayVisibleDisplayFrame)
            overlayTopInsetPx = overlayVisibleDisplayFrame.top.coerceAtLeast(0)
        }
        val captured = focused.captureEditableText()
        transformationAvailable = settings.transformationButtonEnabled && captured.hasText
        dictionaryAvailable = !focused.isPassword && captured.selectedText() != null
        overlayTargetPackage = focused.packageName
        val showMenu = overlayMenuExpanded
        view.setMenuState(expanded = showMenu)
        val params = overlayParams(ime, focused)
        if (overlayAttached) {
            val currentParams = overlayLayoutParams ?: return
            if (currentParams.width != params.width || currentParams.height != params.height ||
                currentParams.x != params.x
            ) {
                val previousWidth = currentParams.width
                val previousHeight = currentParams.height
                val previousX = currentParams.x
                currentParams.width = params.width
                currentParams.height = params.height
                currentParams.x = params.x
                runCatching { windowManager.updateViewLayout(view, currentParams) }
                    .onFailure {
                        currentParams.width = previousWidth
                        currentParams.height = previousHeight
                        currentParams.x = previousX
                        Log.w(TAG, "Could not resize accessibility overlay", it)
                    }
            }
            val motion = overlayPositionMotion ?: OverlayPositionMotion(
                overlayWindowOffsetY ?: params.y,
            ).also { overlayPositionMotion = it }
            motion.retarget(params.y)
            if (!motion.isAtRest) scheduleOverlayPositionAnimation()
        } else {
            runCatching {
                windowManager.addView(view, params)
                overlayAttached = true
                overlayWindowOffsetY = params.y
                overlayLayoutParams = params
                overlayPositionMotion = OverlayPositionMotion(params.y)
                // The visible frame becomes available only after the overlay is attached.
                view.post { if (overlayAttached) scheduleOverlayUpdate() }
            }.onFailure { Log.w(TAG, "Could not add accessibility overlay", it) }
        }
        if (showMenu && overlayAttached) {
            showOrMoveMenuOverlay(
                primaryOffsetY = requireNotNull(overlayLayoutParams).y,
                showTransformation = transformationAvailable,
                showDictionary = dictionaryAvailable,
            )
        } else {
            removeMenuOverlay()
        }
    }

    private fun showOrMoveMenuOverlay(
        primaryOffsetY: Int,
        showTransformation: Boolean,
        showDictionary: Boolean,
    ) {
        val view = overlayMenu ?: return
        view.setPrimaryPostProcessingEnabled(settings.postProcessing.forMode(settings.model).enabled)
        view.setTransformationAvailable(showTransformation)
        view.setDictionaryAvailable(showDictionary)
        val density = resources.displayMetrics.density
        val width = OverlayMotion.windowWidthPx(density, showMenu = true)
        val menuLayout = OverlayMotion.menuWindowLayout(
            primaryOffsetY = primaryOffsetY,
            displayHeightPx = resources.displayMetrics.heightPixels,
            density = density,
            showTransformation = showTransformation,
            showDictionary = showDictionary,
            appearance = settings.dictationButtonAppearance,
            topInsetPx = overlayTopInsetPx,
        )
        val height = menuLayout.height
        val y = menuLayout.offsetY
        val x = OverlayMotion.windowLeftPx(resources.displayMetrics.widthPixels, width,
            settings.dictationButtonAppearance.position)
        val currentParams = overlayMenuLayoutParams
        if (overlayMenuAttached && currentParams != null) {
            if (currentParams.width == width && currentParams.height == height &&
                currentParams.y == y && currentParams.x == x
            ) return
            currentParams.width = width
            currentParams.height = height
            currentParams.y = y
            currentParams.x = x
            runCatching { windowManager.updateViewLayout(view, currentParams) }
                .onFailure { Log.w(TAG, "Could not update accessibility menu", it) }
        } else {
            val params = WindowManager.LayoutParams(
                width,
                height,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT,
            ).apply {
                gravity = Gravity.LEFT or Gravity.BOTTOM
                this.x = x
                this.y = y
            }
            runCatching {
                windowManager.addView(view, params)
                overlayMenuAttached = true
                overlayMenuLayoutParams = params
            }.onFailure { Log.w(TAG, "Could not add accessibility menu", it) }
        }
    }

    private fun removeMenuOverlay() {
        if (!overlayMenuAttached) return
        overlayMenu?.let { view ->
            runCatching { windowManager.removeView(view) }
                .onFailure { Log.w(TAG, "Could not remove accessibility menu", it) }
        }
        overlayMenuAttached = false
        overlayMenuLayoutParams = null
    }

    private fun scheduleOverlayPositionAnimation() {
        if (overlayPositionAnimationScheduled) return
        val view = overlay ?: return
        overlayPositionAnimationScheduled = true
        view.postOnAnimation(updateOverlayPositionRunnable)
    }

    private val updateOverlayPositionRunnable = Runnable {
        overlayPositionAnimationScheduled = false
        val view = overlay
        val params = overlayLayoutParams
        val motion = overlayPositionMotion
        if (!overlayAttached || view == null || params == null || motion == null) {
            lastOverlayPositionFrameNanos = 0L
            return@Runnable
        }

        val frameNanos = SystemClock.elapsedRealtimeNanos()
        val elapsedMillis = if (lastOverlayPositionFrameNanos == 0L) {
            DEFAULT_FRAME_MILLIS
        } else {
            (frameNanos - lastOverlayPositionFrameNanos) / NANOS_PER_MILLISECOND
        }
        lastOverlayPositionFrameNanos = frameNanos
        val nextOffsetY = motion.advanceByMillis(elapsedMillis)
        if (params.y != nextOffsetY) {
            params.y = nextOffsetY
            val moved = runCatching { windowManager.updateViewLayout(view, params) }
                .fold(
                    onSuccess = {
                        overlayWindowOffsetY = nextOffsetY
                        true
                    },
                    onFailure = {
                        Log.w(TAG, "Could not move accessibility overlay", it)
                        false
                    },
                )
            if (!moved) {
                lastOverlayPositionFrameNanos = 0L
                return@Runnable
            }
        }

        if (overlayMenuExpanded) {
            showOrMoveMenuOverlay(nextOffsetY, transformationAvailable, dictionaryAvailable)
        }

        if (motion.isAtRest) {
            lastOverlayPositionFrameNanos = 0L
        } else {
            scheduleOverlayPositionAnimation()
        }
    }

    private fun overlayParams(
        ime: AccessibilityWindowInfo?,
        focused: AccessibilityNodeInfo,
    ): WindowManager.LayoutParams {
        val density = resources.displayMetrics.density
        val displayHeight = resources.displayMetrics.heightPixels
        val imeBounds = Rect()
        ime?.getBoundsInScreen(imeBounds)
        val focusedBounds = Rect()
        focused.getBoundsInScreen(focusedBounds)
        val keyboardTop = if (!imeBounds.isEmpty) {
            imeBounds.top
        } else {
            displayHeight - (DEFAULT_KEYBOARD_HEIGHT_DP * density).toInt()
        }
        return WindowManager.LayoutParams(
            OverlayMotion.primaryWindowWidthPx(density, settings.dictationButtonAppearance),
            OverlayMotion.primaryWindowHeightPx(density, settings.dictationButtonAppearance),
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.LEFT or Gravity.BOTTOM
            x = OverlayMotion.windowLeftPx(resources.displayMetrics.widthPixels, width,
                settings.dictationButtonAppearance.position)
            y = OverlayMotion.windowOffsetY(
                displayHeightPx = displayHeight,
                keyboardTopPx = keyboardTop,
                focusedFieldTopPx = focusedBounds.takeUnless(Rect::isEmpty)?.top,
                density = density,
                showMenu = false,
                appearance = settings.dictationButtonAppearance,
                topInsetPx = overlayTopInsetPx,
            )
        }
    }

    private fun removeOverlay() {
        val view = overlay ?: return
        overlayTargetPackage = null
        removeMenuOverlay()
        view.removeCallbacks(updateOverlayPositionRunnable)
        overlayPositionAnimationScheduled = false
        lastOverlayPositionFrameNanos = 0L
        if (overlayAttached) {
            runCatching { windowManager.removeView(view) }
            overlayAttached = false
            overlayWindowOffsetY = null
            overlayTopInsetPx = 0
            overlayLayoutParams = null
            overlayPositionMotion = null
        }
    }

    private fun onOverlayTapped() {
        if (DictationStateBus.state.value.isActive) {
            stopDictation()
            return
        }
        startOrdinaryDictation()
    }

    private fun onAlternateDictationTapped(postProcessingEnabled: Boolean) {
        if (DictationStateBus.state.value.isActive) return
        startOrdinaryDictation(postProcessingEnabled)
    }

    private fun startOrdinaryDictation(postProcessingOverride: Boolean? = null) {
        setOverlayMenuExpanded(false)
        if (!isReadyToStart()) return
        val focused = findFocusedEditable()
        if (focused?.isTextInput() != true) return
        if (!appFilter.allows(focused.packageName)) return
        editableSnapshot = focused.captureEditableText()
        clearPendingTextRestoration()
        targetPackage = focused.packageName
        activeSessionId = 0L
        ignoredSessionId = 0L
        transcriptDelivery.reset()
        val intent = Intent(this, DictationForegroundService::class.java)
            .setAction(DictationForegroundService.ACTION_START)
        postProcessingOverride?.let { intent.putExtra(DictationForegroundService.EXTRA_POST_PROCESSING_ENABLED, it) }
        ContextCompat.startForegroundService(this, intent)
    }

    private fun onTransformationTapped() {
        if (DictationStateBus.state.value.isActive) {
            stopDictation()
            return
        }
        setOverlayMenuExpanded(false)
        if (!isReadyToStart()) return
        val focused = findFocusedEditable()
        if (focused?.isTextInput() != true) return
        if (!appFilter.allows(focused.packageName)) return
        if (focused.isPassword) {
            showOverlayError(R.string.error_password_field_transformation)
            return
        }
        val captured = focused.captureEditableText()
        val target = captured.transformationTarget()
        if (target == null) {
            showOverlayError(R.string.error_nothing_to_transform)
            return
        }
        if (target.sourceText.length > MAX_TRANSFORMATION_CHARS) {
            showOverlayError(R.string.error_text_too_long)
            return
        }
        editableSnapshot = target.replacementSnapshot
        clearPendingTextRestoration()
        targetPackage = focused.packageName
        activeSessionId = 0L
        ignoredSessionId = 0L
        transcriptDelivery.reset()
        val intent = Intent(this, DictationForegroundService::class.java)
            .setAction(DictationForegroundService.ACTION_START_TRANSFORMATION)
            .putExtra(DictationForegroundService.EXTRA_SOURCE_TEXT, target.sourceText)
        ContextCompat.startForegroundService(this, intent)
    }

    private fun onAddToDictionaryTapped() {
        if (DictationStateBus.state.value.isActive) return
        setOverlayMenuExpanded(false)
        val focused = findFocusedEditable()
        val selected = focused
            ?.takeIf {
                it.isTextInput() && it.isFocused && !it.isPassword &&
                    appFilter.allows(it.packageName) &&
                    it.packageName == overlayTargetPackage
            }
            ?.captureEditableText()
            ?.selectedText()
        Toast.makeText(
            this,
            settings.addToDictionary(selected),
            Toast.LENGTH_SHORT,
        ).show()
    }

    private fun onPasteLastTapped() {
        if (DictationStateBus.state.value.isActive) return
        setOverlayMenuExpanded(false)
        val target = findFocusedEditable()
        if (target == null || !target.isTextInput() || !target.isFocused) {
            Toast.makeText(this, R.string.overlay_paste_unavailable, Toast.LENGTH_SHORT).show()
            return
        }
        if (!appFilter.allows(target.packageName)) return
        if (target.isPassword) {
            Toast.makeText(this, R.string.overlay_paste_password, Toast.LENGTH_SHORT).show()
            return
        }
        scope.launch {
            val latest = try {
                withContext(Dispatchers.IO) {
                    (application as OpenDictateApplication).transcriptHistoryStore.getLatestText()
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                Toast.makeText(
                    this@OpenDictateAccessibilityService,
                    R.string.overlay_paste_load_error,
                    Toast.LENGTH_SHORT,
                ).show()
                return@launch
            }
            if (latest == null) {
                Toast.makeText(
                    this@OpenDictateAccessibilityService,
                    R.string.overlay_no_transcripts,
                    Toast.LENGTH_SHORT,
                ).show()
                return@launch
            }
            val focused = findFocusedEditable()
            if (DictationStateBus.state.value.isActive ||
                windows.none { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD } ||
                focused == null || !focused.isFocused || focused != target ||
                focused.packageName != target.packageName || focused.isPassword
            ) {
                Toast.makeText(
                    this@OpenDictateAccessibilityService,
                    R.string.overlay_paste_unavailable,
                    Toast.LENGTH_SHORT,
                ).show()
                return@launch
            }
            val snapshot = focused.captureEditableText()
            val cursor = snapshot.cursorAfter(latest)
            val inserted = replaceFocusedText(
                text = snapshot.compose(latest),
                selectionStart = cursor,
                selectionEnd = cursor,
                expectedPackage = target.packageName,
                expectedNode = target,
            )
            Toast.makeText(
                this@OpenDictateAccessibilityService,
                if (inserted) R.string.overlay_pasted else R.string.overlay_paste_unavailable,
                Toast.LENGTH_SHORT,
            ).show()
        }
    }

    private fun onLastDictationTapped() {
        if (DictationStateBus.state.value.isActive) return
        setOverlayMenuExpanded(false)
        val focused = findFocusedEditable()
        if (focused?.isTextInput() != true || !focused.isFocused ||
            !appFilter.allows(focused.packageName)
        ) {
            Toast.makeText(this, R.string.overlay_paste_unavailable, Toast.LENGTH_SHORT).show()
            return
        }
        if (!SecureApiKeyStore(this).hasKey()) {
            startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return
        }
        scope.launch {
            val exists = try {
                withContext(Dispatchers.IO) {
                    (application as OpenDictateApplication).lastDictationAudioStore.latestFile() != null
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                Toast.makeText(this@OpenDictateAccessibilityService,
                    R.string.error_transcription_failed, Toast.LENGTH_SHORT).show()
                return@launch
            }
            if (!exists) {
                Toast.makeText(this@OpenDictateAccessibilityService,
                    R.string.error_no_last_dictation, Toast.LENGTH_SHORT).show()
                return@launch
            }
            val current = findFocusedEditable()
            if (DictationStateBus.state.value.isActive ||
                windows.none { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD } ||
                current == null || !current.isFocused || current != focused ||
                !appFilter.allows(current.packageName)
            ) return@launch
            editableSnapshot = current.captureEditableText()
            clearPendingTextRestoration()
            targetPackage = current.packageName
            activeSessionId = 0L
            ignoredSessionId = 0L
            transcriptDelivery.reset()
            ContextCompat.startForegroundService(
                this@OpenDictateAccessibilityService,
                Intent(this@OpenDictateAccessibilityService, DictationForegroundService::class.java)
                    .setAction(DictationForegroundService.ACTION_RETRY_LAST),
            )
        }
    }

    private fun onOverlayDismissed() {
        if (DictationStateBus.state.value.isActive) return
        overlayVisibilitySession.dismiss()
        overlayMenuExpanded = false
        overlay?.setMenuState(expanded = false)
        removeOverlay()
    }

    private fun onOverlayMenuToggled() {
        if (DictationStateBus.state.value.isActive) return
        setOverlayMenuExpanded(!overlayMenuExpanded)
    }

    private fun setOverlayMenuExpanded(expanded: Boolean) {
        if (overlayMenuExpanded == expanded) return
        overlayMenuExpanded = expanded
        if (expanded) overlayMenu?.scrollTo(0, 0)
        overlay?.setMenuState(expanded = overlayMenuExpanded)
        if (!expanded) removeMenuOverlay()
        scheduleOverlayUpdate()
    }

    private fun isReadyToStart(): Boolean {
        if (
            SecureApiKeyStore(this).hasKey() &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            return true
        }
        startActivity(
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        return false
    }

    private fun showOverlayError(message: Int) {
        val sessionId = SystemClock.elapsedRealtimeNanos()
        activeSessionId = sessionId
        DictationStateBus.set(
            DictationState(
                sessionId = sessionId,
                phase = DictationPhase.ERROR,
                operation = DictationOperation.TRANSFORMATION,
                message = getString(message),
            ),
        )
        handler.postDelayed(
            {
                if (DictationStateBus.state.value.sessionId == sessionId) {
                    DictationStateBus.set(
                        DictationState(
                            sessionId = sessionId,
                            phase = DictationPhase.IDLE,
                            operation = DictationOperation.TRANSFORMATION,
                        ),
                    )
                }
            },
            ERROR_DISPLAY_DURATION_MS,
        )
    }

    private fun stopDictation() {
        startService(
            Intent(this, DictationForegroundService::class.java)
                .setAction(DictationForegroundService.ACTION_STOP),
        )
    }

    private fun onOverlayCancelled() {
        val state = DictationStateBus.state.value
        if (!state.isActive) return
        ignoredSessionId = state.sessionId
        if (state.shouldRestoreTextOnCancellation()) {
            editableSnapshot?.let(pendingTextRestoration::begin)
            restoreEditableSnapshot()
        }
        startService(
            Intent(this, DictationForegroundService::class.java)
                .setAction(DictationForegroundService.ACTION_CANCEL)
                .putExtra(DictationForegroundService.EXTRA_SESSION_ID, state.sessionId),
        )
    }

    private fun handleTranscriptState(state: DictationState) {
        if (state.sessionId != 0L && state.sessionId == ignoredSessionId) return
        if (state.sessionId != 0L && activeSessionId == 0L) activeSessionId = state.sessionId
        if (state.sessionId != activeSessionId || state.transcript.isBlank()) return
        if (transcriptDelivery.wasDelivered(state.transcript)) return
        val shouldInsert = if (state.operation == DictationOperation.TRANSFORMATION) {
            state.phase == DictationPhase.COMPLETED
        } else {
            state.phase == DictationPhase.LISTENING ||
                state.phase == DictationPhase.PROCESSING ||
                state.phase == DictationPhase.COMPLETED
        }
        if (shouldInsert) insertTranscript(state.transcript, state.phase)
    }

    private fun insertTranscript(transcript: String, phase: DictationPhase) {
        val snapshot = editableSnapshot ?: return
        val inserted = replaceFocusedText(
            text = snapshot.compose(transcript),
            selectionStart = snapshot.cursorAfter(transcript),
            selectionEnd = snapshot.cursorAfter(transcript),
        )
        when (transcriptDelivery.record(transcript, phase, inserted)) {
            TranscriptDeliveryOutcome.COPY_TO_CLIPBOARD -> copyTranscript(transcript)
            TranscriptDeliveryOutcome.INSERTED,
            TranscriptDeliveryOutcome.PENDING,
            -> Unit
        }
    }

    private fun copyTranscript(transcript: String) {
        val clip = ClipData.newPlainText(
            getString(R.string.clipboard_transcript_label),
            transcript,
        ).apply {
            description.extras = PersistableBundle().apply {
                putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true)
            }
        }
        getSystemService(ClipboardManager::class.java).setPrimaryClip(clip)
        Toast.makeText(this, R.string.transcript_copied_to_clipboard, Toast.LENGTH_SHORT).show()
    }

    private fun restoreEditableSnapshot() {
        val focused = findFocusedEditable()
        if (
            focused != null &&
            focused.isTextInput() &&
            focused.packageName == targetPackage &&
            pendingTextRestoration.confirmApplied(
                displayedText = focused.captureEditableText().original,
                selectionStart = focused.textSelectionStart,
                selectionEnd = focused.textSelectionEnd,
            )
        ) {
            transcriptDelivery.reset()
            return
        }
        val update = pendingTextRestoration.nextAttempt() ?: return
        // Cancellation may still restore our own partial text after the app is disabled.
        replaceFocusedText(update.text, update.selectionStart, update.selectionEnd, restoringSnapshot = true)
        scheduleEditableSnapshotRestoration()
    }

    private val restoreEditableSnapshotRunnable = Runnable {
        textRestorationScheduled = false
        restoreEditableSnapshot()
    }

    private fun scheduleEditableSnapshotRestoration() {
        if (!pendingTextRestoration.isPending || textRestorationScheduled) return
        textRestorationScheduled = true
        handler.postDelayed(restoreEditableSnapshotRunnable, RESTORE_RETRY_DELAY_MS)
    }

    private fun clearPendingTextRestoration() {
        handler.removeCallbacks(restoreEditableSnapshotRunnable)
        textRestorationScheduled = false
        pendingTextRestoration.clear()
    }

    private fun replaceFocusedText(
        text: String,
        selectionStart: Int,
        selectionEnd: Int,
        expectedPackage: CharSequence? = targetPackage,
        expectedNode: AccessibilityNodeInfo? = null,
        restoringSnapshot: Boolean = false,
    ): Boolean {
        val focused = findFocusedEditable() ?: return false
        if (
            !focused.isTextInput() ||
            focused.packageName != expectedPackage ||
            !restoringSnapshot && !appFilter.allows(focused.packageName) ||
            expectedNode != null && focused != expectedNode
        ) return false
        val setText = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        if (focused.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, setText)) {
            val selection = Bundle().apply {
                putInt(
                    AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT,
                    selectionStart,
                )
                putInt(
                    AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT,
                    selectionEnd,
                )
            }
            focused.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, selection)
            return true
        }
        return false
    }

    private fun findFocusedEditable(): AccessibilityNodeInfo? {
        val roots = buildList {
            rootInActiveWindow?.let(::add)
            windows.asSequence()
                .filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
                .mapNotNull { it.root }
                .forEach(::add)
        }
        roots.forEach { root ->
            root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
                ?.takeIf { it.isTextInput() }
                ?.let { return it }
            val queue = ArrayDeque<AccessibilityNodeInfo>()
            queue.add(root)
            var visited = 0
            var editableFallback: AccessibilityNodeInfo? = null
            while (queue.isNotEmpty() && visited++ < MAX_NODE_SCAN) {
                val node = queue.removeFirst()
                if (node.isTextInput()) {
                    if (node.isFocused) return node
                    editableFallback = node
                }
                repeat(node.childCount) { index -> node.getChild(index)?.let(queue::addLast) }
            }
            if (editableFallback != null) return editableFallback
        }
        return null
    }

    private fun AccessibilityNodeInfo.isTextInput(): Boolean =
        isEditable ||
            className == "android.widget.EditText" ||
            actionList.any { it.id == AccessibilityNodeInfo.ACTION_SET_TEXT }

    private fun AccessibilityNodeInfo.captureEditableText(): EditableTextSnapshot =
        EditableTextSnapshot.capture(
            displayedText = text,
            hintText = hintText,
            selectionStart = textSelectionStart.coerceAtLeast(0),
            selectionEnd = textSelectionEnd.coerceAtLeast(0),
            isShowingHintText = isShowingHintText,
            supportsTextSelection = actionList.any {
                it.id == AccessibilityNodeInfo.ACTION_SET_SELECTION
            },
        )

    companion object {
        private const val TAG = "OpenDictateA11y"
        private const val MAX_NODE_SCAN = 200
        private const val DEFAULT_KEYBOARD_HEIGHT_DP = 300
        private const val OVERLAY_UPDATE_DELAY_MS = 32L
        private const val DEFAULT_FRAME_MILLIS = 16L
        private const val NANOS_PER_MILLISECOND = 1_000_000L
        private const val ERROR_DISPLAY_DURATION_MS = 3_000L
        private const val RESTORE_RETRY_DELAY_MS = 80L
        private const val MAX_TRANSFORMATION_CHARS = 80_000
    }
}
