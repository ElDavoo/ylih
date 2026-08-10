package it.eldavo.ylih.tracking

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import it.eldavo.ylih.AppLocale
import it.eldavo.ylih.Distribution
import it.eldavo.ylih.R
import it.eldavo.ylih.YlihApp
import it.eldavo.ylih.data.AppContainer
import it.eldavo.ylih.data.DeviceIdentity
import it.eldavo.ylih.data.SessionEntity
import it.eldavo.ylih.data.trackedKinds
import it.eldavo.ylih.ui.formatDurationShort
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Runs only in "detailed tracking" mode. Wired plug events are never delivered to a manifest
 * receiver, so observing them at all requires a live process — that is the entire reason this
 * service (and its notification) exists. While it is up it also measures playback time.
 *
 * [TrackingController.detailedTrackingSupported] never starts this below Android 8 (API 26),
 * where notification channels and [PlaybackWatcher]'s callback both start existing.
 */
@RequiresApi(Build.VERSION_CODES.O)
class TrackingService : LifecycleService() {

    private lateinit var container: AppContainer
    private val audioManager: AudioManager by lazy { getSystemService(AudioManager::class.java) }
    private val handler = Handler(Looper.getMainLooper())

    /**
     * The keys with a session open, oldest first — playback is credited to the last of them.
     *
     * A list rather than the single "most recently connected" key it used to be, because that key
     * was a latch with two ways of being stranded, and a stranded one stops playback being measured
     * at all while the session it belongs to goes on looking perfectly normal. A pair connecting
     * took it *whether or not a session had been opened for it*, so an ignored device — a car
     * stereo, the thing the ignore list is for — held it for the whole drive and credited every
     * slice to a pair that has no open session to write to. And the pair holding it disconnecting
     * cleared it with nothing to take over, so unplugging wired headphones while Bluetooth ones
     * were still on left them unmeasured until they were disconnected and reconnected: the audio
     * callback only reports *changes* after the first delivery, so nothing would say so again.
     *
     * Keeping the whole set makes both cases the same one line — a key is in here exactly while it
     * has a session to credit, and the newest one that still does is the target.
     */
    private val playbackTargets = mutableListOf<String>()
    private val playbackTargetKey: String? get() = playbackTargets.lastOrNull()
    private var playbackWatcher: PlaybackWatcher? = null

    /**
     * What the notification already reads, so text that has not changed is not posted again.
     * Beyond the wakeup it saves, the notification is dismissible on Android 13+, and re-posting
     * it would keep putting back something the user had just swiped away.
     */
    private var postedText: String? = null

    private val deviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) {
            val identities = addedDevices.orEmpty().mapNotNull { AudioDevices.identityOf(it) }
            onDevicesChanged(identities, connected = true)
        }

        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) {
            val identities = removedDevices.orEmpty().mapNotNull { AudioDevices.identityOf(it) }
            onDevicesChanged(identities, connected = false)
        }
    }

    /**
     * The notification is the one piece of the app that speaks outside the activity, so it follows
     * the same in-app language below Android 13 rather than reverting to the system one.
     */
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppLocale.wrap(newBase))
    }

    override fun onCreate() {
        super.onCreate()
        container = (application as YlihApp).container

        // No ensureChannel here: YlihApp.onCreate has already run in this process and made it.
        // Creating it on the line above this call is exactly the ordering that broke detailed
        // tracking for anyone who had denied the notification permission.
        startForegroundCompat(getString(R.string.notification_starting))

        playbackWatcher = PlaybackWatcher(audioManager, container.clock) { deltaMs ->
            creditFromCallback(deltaMs)
        }.also { it.start(handler) }
        audioManager.registerAudioDeviceCallback(deviceCallback, handler)

        lifecycleScope.launch {
            container.trackingController.syncWithSystem()
            // Detailed tracking is a setting, not a state: this service is up whether or not
            // anything is plugged in, and a tick with nothing connected heartbeats an empty
            // table and re-posts an unchanged notification. So the loop only runs while a
            // session is open — for most days a couple of hours rather than all of them.
            // Reading that from the open-session flow rather than from our own device callback
            // is what keeps a session the Bluetooth receiver opened heartbeaten too.
            container.repository.observeOpenSessions()
                .map { it.isNotEmpty() }
                .distinctUntilChanged()
                .collectLatest { anyOpen ->
                    refreshNotification()
                    if (anyOpen) tickLoop()
                }
        }
    }

    private suspend fun tickLoop(): Unit = coroutineScope {
        while (isActive) {
            delay(TICK_MS)
            tick()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        return START_STICKY
    }

    override fun onDestroy() {
        val now = container.clock.now()
        audioManager.unregisterAudioDeviceCallback(deviceCallback)
        // On `container.scope`, not `lifecycleScope`: `super.onDestroy()` below dispatches
        // ON_DESTROY and so cancels the latter, and this write's first suspension point is the
        // database — a coroutine launched here on the lifecycle scope started, suspended, and was
        // cancelled before it ever reached the table, so every service stop silently dropped the
        // slice that had accrued since the last tick.
        val key = playbackTargetKey
        val remaining = playbackWatcher?.stop(now) ?: 0L
        if (key != null) {
            container.scope.launch { container.repository.creditPlayback(key, remaining) }
        }
        playbackWatcher = null
        super.onDestroy()
    }

    // Not private: deviceCallback above is a separate class, so a private method would be
    // reached through a synthetic accessor (lint's SyntheticAccessor).
    internal fun onDevicesChanged(identities: List<DeviceIdentity>, connected: Boolean) {
        if (identities.isEmpty()) return
        val kinds = trackedKinds(detailedTracking = true)
        lifecycleScope.launch {
            val now = container.clock.now()
            for (identity in identities.filter { it.kind in kinds }) {
                if (connected) {
                    // Banked before the target moves, while [playbackTargetKey] still names the
                    // pair the audio was actually played on. Swapping headphones mid-song used to
                    // `rebase` here, which dropped that time rather than crediting it. The same
                    // call starts the clock where audio is already running — a service start, or
                    // that same swap — rather than leaving it to the first tick.
                    creditAccrued(playbackWatcher?.refresh(now) ?: 0L)
                    // Only a device that got a session becomes the target: `onConnected` returns
                    // null for one the user has ignored, and there is nothing to credit those.
                    if (container.repository.onConnected(identity, now, measurePlayback = true) != null) {
                        playbackTargets.remove(identity.key)
                        playbackTargets += identity.key
                    }
                } else {
                    if (playbackTargetKey == identity.key) {
                        // Before the close, and waited for: playback is credited to whatever
                        // session the pair has *open*, so a slice banked after the disconnect finds
                        // nothing to write to. That silently cost every session its last part-minute.
                        creditAccrued(playbackWatcher?.refresh(now) ?: 0L)
                    }
                    // Dropped rather than cleared, so whatever else is still connected takes over.
                    playbackTargets.remove(identity.key)
                    container.repository.onDisconnected(identity.key, now)
                }
            }
            refreshNotification()
            // Wired plug events reach nothing but this service, so this is the only place that can
            // tell the home screen a session just opened or closed.
            container.trackingController.onSessionsChanged()
        }
    }

    private suspend fun tick() {
        val now = container.clock.now()
        val open = container.repository.heartbeat(now)
        creditAccrued(playbackWatcher?.refresh(now) ?: 0L)
        refreshNotification(open)
        // A minute's worth of playback is a figure the widgets have no other way to learn: the
        // Chronometer counts connected time, and in playback-only mode that is not what the totals
        // show. This runs only while a session is open, which is the only time it would change
        // anything.
        container.trackingController.onFiguresChanged()
    }

    /** Credits a slice this service asked for, and waits for the write. */
    private suspend fun creditAccrued(deltaMs: Long) {
        val key = playbackTargetKey ?: return
        container.repository.creditPlayback(key, deltaMs)
    }

    /**
     * Credits a slice the watcher's own callback banked, which nothing can wait for.
     *
     * On `container.scope` rather than `lifecycleScope` for the same reason [onDestroy] is: the
     * lifecycle scope dies with the service, and the write's first suspension point is the
     * database. Nothing here closes a session, so there is no ordering to keep either.
     */
    private fun creditFromCallback(deltaMs: Long) {
        val key = playbackTargetKey ?: return
        container.scope.launch { container.repository.creditPlayback(key, deltaMs) }
    }

    /** @param known the open sessions if the caller has just read them, saving a second query. */
    private suspend fun refreshNotification(known: List<SessionEntity>? = null) {
        val now = container.clock.now()
        val open = known ?: container.repository.openSessionsSnapshot()
        val text = when {
            open.isEmpty() -> getString(R.string.notification_idle)
            else -> {
                val since = open.minOf { it.connectedAt }
                val playing = playbackWatcher?.isPlaying == true
                resources.getQuantityString(
                    if (playing) R.plurals.notification_active_playing else R.plurals.notification_active,
                    open.size,
                    open.size,
                    formatDurationShort(now - since),
                )
            }
        }
        startForegroundCompat(text)
    }

    /**
     * Posts [text], unless it is already what the notification says.
     *
     * A refusal stops the service rather than letting it be killed for never calling
     * startForeground; nothing downstream needs to know, so this reports nothing.
     */
    private fun startForegroundCompat(text: String) {
        if (text == postedText) return
        try {
            ServiceCompat.startForeground(
                this,
                Notifications.ID_TRACKING,
                Notifications.trackingNotification(this, text),
                foregroundServiceType(),
            )
            postedText = text
        } catch (e: RuntimeException) {
            // Narrow on purpose. The refusals this is written for are all RuntimeExceptions —
            // SecurityException where the service type's permission is missing,
            // ForegroundServiceStartNotAllowedException outside an allowed window,
            // InvalidForegroundServiceTypeException, IllegalStateException — and every one of them
            // means the same thing: detailed tracking cannot run right now. Catching Throwable
            // would fold an error out of `trackingNotification` into the same silent `stopSelf`,
            // which is the shape of failure the Notifications KDoc records as painful to find.
            Log.e(TAG, "Foreground start refused; detailed tracking cannot run", e)
            stopSelf()
        }
    }

    /**
     * `connectedDevice` requires holding a Bluetooth permission on Android 14+. The classic
     * flavor also declares `specialUse`, which covers someone who wants wired headphones
     * tracked but denied Bluetooth; the Play flavor does not, so there
     * [TrackingController.detailedTrackingSupported] keeps the service from being started at all.
     */
    private fun foregroundServiceType(): Int {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return 0
        val hasBluetooth = Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) ==
            PackageManager.PERMISSION_GRANTED
        return when {
            hasBluetooth -> ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            Distribution.HAS_SPECIAL_USE_FGS &&
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE ->
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE

            else -> ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
        }
    }

    private companion object {
        const val TAG = "TrackingService"
        const val TICK_MS = 60_000L
    }
}
