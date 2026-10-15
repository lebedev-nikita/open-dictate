package com.opendictate.app.data

import android.content.Context
import android.os.SystemClock
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.ClearTokenRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import com.opendictate.app.network.DriveSyncException
import com.opendictate.app.network.GoogleDriveSettingsClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

data class DriveSyncState(val enabled: Boolean = false, val busy: Boolean = false,
    val lastSyncedAt: Long = 0, val needsAuthorization: Boolean = false, val failed: Boolean = false,
    val settingsRevision: Long = 0, val needsSourceSelection: Boolean = false, val waiting: Boolean = false) {
    val canSyncNow: Boolean get() = enabled && !busy && !needsAuthorization && !needsSourceSelection
}

/** Process-owned: includes dictionary edits from the share and selection activities. */
class GoogleDriveSync(context: Context, private val replacements: ReplacementStore,
    private val client: GoogleDriveSettingsClient = GoogleDriveSettingsClient(),
    private val tokenProvider: (suspend () -> String)? = null,
    private val automaticallySync: Boolean = true,
) {
    private val settings = SettingsStore(context)
    private val authorization = Identity.getAuthorizationClient(context)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mutex = Mutex()
    private data class ConnectionChoice(val local: SettingsSyncDocument, val cloud: SettingsSyncDocument,
        val source: SettingsSyncDocument.ConnectionSource? = null)
    private var connectionChoice: ConnectionChoice? = null
    private var generation = 0
    private var schedule = SettingsSyncSchedule()
    private var networkAvailable = true
    private val mutableState = MutableStateFlow(DriveSyncState(enabled = settings.driveSyncEnabled))
    val state = mutableState.asStateFlow()
    private var changeJob: kotlinx.coroutines.Job? = null
    private val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == "sync_local_revision" && settings.driveSyncEnabled) {
            schedule.changed(SystemClock.elapsedRealtime())
            pump()
        }
    }

    init {
        // Seed before the first local edit, including migration from the original five fields.
        runCatching { settings.syncDocument() }
        context.getSharedPreferences("opendictate_settings", Context.MODE_PRIVATE).registerOnSharedPreferenceChangeListener(listener)
        if (automaticallySync) {
            val connectivity = context.getSystemService(ConnectivityManager::class.java)
            networkAvailable = connectivity.getNetworkCapabilities(connectivity.activeNetwork)
                ?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
            connectivity.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                    val available = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                    scope.launch {
                        val restored = !networkAvailable && available
                        networkAvailable = available
                        if (restored) schedule.networkRestored(SystemClock.elapsedRealtime())
                        pump()
                    }
                }
                override fun onLost(network: Network) { scope.launch { networkAvailable = false; changeJob?.cancel() } }
            })
            pump()
        }
    }

    fun connect(token: String) {
        settings.driveConnectionPending = true
        connectionChoice = null
        settings.driveSyncEnabled = true
        mutableState.value = DriveSyncState(enabled = true)
        schedule.refresh()
        pump(token)
    }

    fun disconnect() {
        generation++
        settings.driveSyncEnabled = false
        settings.driveConnectionPending = false
        connectionChoice = null
        changeJob?.cancel()
        schedule = SettingsSyncSchedule()
        mutableState.value = DriveSyncState()
    }

    fun chooseSource(source: SettingsSyncDocument.ConnectionSource) {
        val choice = connectionChoice ?: return
        if (!mutableState.value.needsSourceSelection) return
        connectionChoice = choice.copy(source = source)
        mutableState.value = mutableState.value.copy(needsSourceSelection = false)
        requestSync()
    }

    fun authorizationFailed() {
        schedule.finish(SystemClock.elapsedRealtime(), SettingsSyncSchedule.Completion.AUTHORIZATION)
        changeJob?.cancel()
        mutableState.value = mutableState.value.copy(busy = false, failed = true, needsAuthorization = true)
    }

    fun requestSync() {
        if (!mutableState.value.canSyncNow) return
        mutableState.value = mutableState.value.copy(waiting = true, failed = false)
        schedule.refresh()
        pump()
    }

    fun refreshIfStale() {
        if (!settings.driveSyncEnabled) return
        schedule.refreshIfStale(SystemClock.elapsedRealtime())
        pump()
    }

    fun replacementsChanged() { settings.recordReplacementChange() }

    private fun pump(suppliedToken: String? = null) {
        if (!settings.driveSyncEnabled || mutableState.value.needsSourceSelection || mutex.isLocked || schedule.busy) return
        changeJob?.cancel()
        if (!networkAvailable) return
        val now = SystemClock.elapsedRealtime()
        if (automaticallySync) schedule.refreshIfStale(now, SettingsSyncSchedule.BACKGROUND_INTERVAL)
        if (!schedule.begin(now)) {
            schedule.delayUntilReady(now, includePeriodic = automaticallySync)?.let { wait ->
                changeJob = scope.launch { delay(wait); pump() }
            }
            return
        }
        val run = generation
        scope.launch {
            var completion = SettingsSyncSchedule.Completion.DEFERRED
            try { completion = performSync(suppliedToken) }
            finally {
                if (generation == run) schedule.finish(SystemClock.elapsedRealtime(), completion)
                pump()
            }
        }
    }

    private suspend fun performSync(suppliedToken: String?) = mutex.withLock {
        if (!settings.driveSyncEnabled) return@withLock SettingsSyncSchedule.Completion.DEFERRED
        val runGeneration = generation
        mutableState.value = mutableState.value.copy(busy = true, failed = false, waiting = false)
        var token: String? = suppliedToken
        try {
            if (token == null && tokenProvider != null) token = tokenProvider.invoke()
            if (token == null) token = suspendCancellableCoroutine { continuation ->
                authorization.authorize(request()).addOnSuccessListener { result ->
                    if (continuation.isActive) {
                        if (result.hasResolution() || result.accessToken == null) continuation.resumeWithException(DriveSyncException(401))
                        else continuation.resume(result.accessToken)
                    }
                }.addOnFailureListener { if (continuation.isActive) continuation.resumeWithException(DriveSyncException(401)) }
            }
            val accessToken = token ?: throw DriveSyncException(401)
            val remote = client.download(accessToken, settings.syncDeviceId, forceRefresh = settings.driveConnectionPending)
            if (runGeneration != generation) return@withLock SettingsSyncSchedule.Completion.DEFERRED
            // A pull must not publish an unfinished dictionary edit made during the download.
            if (schedule.hasPendingChange) {
                mutableState.value = mutableState.value.copy(busy = false, waiting = true)
                return@withLock SettingsSyncSchedule.Completion.DEFERRED
            }
            var source: SettingsSyncDocument.ConnectionSource? = null
            if (settings.driveConnectionPending) {
                settings.validateSyncDocument(remote.document)
                val local = settings.syncDocument()
                if (local.needsConnectionChoice(remote.document)) {
                    val choice = connectionChoice
                    if (choice?.source != null && choice.local.hasSameSettings(local) && choice.cloud.hasSameSettings(remote.document)) {
                        source = choice.source
                    } else {
                        connectionChoice = ConnectionChoice(local, remote.document)
                        mutableState.value = mutableState.value.copy(busy = false, needsSourceSelection = true)
                        return@withLock SettingsSyncSchedule.Completion.DEFERRED
                    }
                }
            }
            val merged = settings.mergeSyncDocument(remote.document, replacements, source)
            // Refresh open editors as soon as incoming preferences apply, before the upload suspends.
            mutableState.value = mutableState.value.copy(settingsRevision = mutableState.value.settingsRevision + 1)
            if (runGeneration != generation) return@withLock SettingsSyncSchedule.Completion.DEFERRED
            if (remote.needsUpload(merged)) {
                client.upload(accessToken, settings.syncDeviceId, remote.ownFileId, merged)
            }
            if (runGeneration == generation) {
                settings.driveConnectionPending = false
                connectionChoice = null
                mutableState.value = mutableState.value.copy(enabled = true, busy = false, failed = false,
                    needsAuthorization = false, lastSyncedAt = System.currentTimeMillis())
            }
            SettingsSyncSchedule.Completion.SUCCESS
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            if (error is DriveSyncException && error.status == 401) token?.let {
                authorization.clearToken(ClearTokenRequest.builder().setToken(it).build())
            }
            if (runGeneration == generation) mutableState.value = mutableState.value.copy(busy = false,
                failed = true, needsAuthorization = error is DriveSyncException && error.status == 401)
            if (error is DriveSyncException && error.status == 401) SettingsSyncSchedule.Completion.AUTHORIZATION
            else SettingsSyncSchedule.Completion.RETRY
        }
    }

    companion object {
        const val SCOPE = "https://www.googleapis.com/auth/drive.appdata"
        fun request(): AuthorizationRequest = AuthorizationRequest.builder().setRequestedScopes(listOf(Scope(SCOPE))).build()
    }
}
