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
import it.eldavo.ylih.data.DeviceKind
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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Runs only in "detailed tracking" mode. Wired plug events are never delivered to a manifest
 * receiver, so observing them needs a live process — why this service (and its notification)
 * exists. While up it also measures playback time.
 */
class TrackingService : LifecycleService() {

    private lateinit var container: AppContainer
    private val audioManager: AudioManager by lazy { getSystemService(AudioManager::class.java) }
    private val handler = Handler(Looper.getMainLooper())

    /**
     * The keys with a session open, oldest first — playback credits to the last.
     *
     * A list, not the single "most recently connected" key it used to be: that key was a latch
     * that could strand. Connecting took it whether or not a session opened, so an ignored
     * device — a car stereo, what the ignore list is for — held it for the whole drive, crediting
     * every slice to a pair with no open session. Disconnecting the holder cleared it with
     * nothing to take over, so unplugging wired headphones while Bluetooth ones stayed on left
     * them unmeasured until reconnected — the audio callback reports only *changes*.
     *
     * The set fixes both: a key stays while it has a session to credit, and the newest is the
     * target.
     */
    private val playbackTargets = mutableListOf<String>()
    private val playbackTargetKey: String? get() = playbackTargets.lastOrNull()
    private var playbackWatcher: PlaybackWatcher? = null

    /**
     * Applies the audio callbacks in arrival order.
     *
     * Each callback launched its own coroutine and suspended on the database, so a connect and
     * the disconnect right behind it could interleave: the disconnect dropped the pair from
     * [playbackTargets] and closed its session while the connect still suspended inside
     * `onConnected`, then re-appended the pair. The unplugged pair stayed the playback target
     * with no open session to credit, so later slices were silently dropped — the notification
     * kept reading "playing" while the connected pair stopped accruing, until a later plug event
     * moved the target off it.
     */
    private val deviceEvents = Mutex()

    /**
     * What the notification already reads, so unchanged text is not reposted. Beyond saving a
     * wakeup, the notification is dismissible on Android 13+, and reposting would restore
     * something the user just swiped away.
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
     * The notification is the one piece of the app that speaks outside the activity, so below
     * Android 13 it follows the in-app language rather than reverting to the system one.
     */
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppLocale.wrap(newBase))
    }

    override fun onCreate() {
        super.onCreate()
        container = (application as YlihApp).container

        // No ensureChannel here: YlihApp.onCreate already made it in this process. Creating it
        // on the line above this call is the ordering that broke detailed tracking for anyone
        // who denied the notification permission.
        startForegroundCompat(getString(R.string.notification_starting))

        playbackWatcher = PlaybackWatcher(audioManager, container.clock) { deltaMs ->
            creditFromCallback(deltaMs)
        }.also { it.start(handler) }
        audioManager.registerAudioDeviceCallback(deviceCallback, handler)

        lifecycleScope.launch {
            container.trackingController.syncWithSystem()
            // Detailed tracking is a setting, not a state: this service runs whether or not
            // anything is plugged in, and an idle tick would heartbeat an empty table and repost
            // an unchanged notification. The loop runs only while a session is open — most days
            // a couple of hours, not all. Reading from the open-session flow, not the device
            // callback, also heartbeats sessions the Bluetooth receiver opened.
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
        // ON_DESTROY and cancels it, and the write's first suspension point is the database —
        // a coroutine on the lifecycle scope would start, suspend, and be cancelled before
        // reaching the table, silently dropping the slice since the last tick.
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
            deviceEvents.withLock { applyDeviceChange(identities, kinds, connected) }
        }
    }

    private suspend fun applyDeviceChange(
        identities: List<DeviceIdentity>,
        kinds: Set<DeviceKind>,
        connected: Boolean,
    ) {
        val now = container.clock.now()
        for (identity in identities.filter { it.kind in kinds }) {
            if (connected) {
                // Banked before the target moves, while [playbackTargetKey] still names the pair
                // that played it. Swapping headphones mid-song used to `rebase` here, dropping
                // that time instead of crediting it. The call also starts the clock where audio
                // is already running — a service start, or that same swap — instead of waiting
                // for the first tick.
                creditAccrued(playbackWatcher?.refresh(now) ?: 0L)
                // Only a device that got a session becomes the target: `onConnected` returns
                // null for an ignored one, and there is nothing to credit those.
                if (container.repository.onConnected(identity, now, measurePlayback = true) != null) {
                    playbackTargets.remove(identity.key)
                    playbackTargets += identity.key
                }
            } else {
                if (playbackTargetKey == identity.key) {
                    // Before the close, and waited for: a slice banked after disconnect finds
                    // no open session to write to, costing each session its last part-minute.
                    creditAccrued(playbackWatcher?.refresh(now) ?: 0L)
                }
                // Dropped rather than cleared, so whatever else is still connected takes over.
                playbackTargets.remove(identity.key)
                container.repository.onDisconnected(identity.key, now)
            }
        }
        refreshNotification()
        // Wired plug events reach only this service, the one place that can tell the home
        // screen a session opened or closed.
        container.trackingController.onSessionsChanged()
    }

    private suspend fun tick() {
        val now = container.clock.now()
        val open = container.repository.heartbeat(now)
        creditAccrued(playbackWatcher?.refresh(now) ?: 0L)
        refreshNotification(open)
        // A minute of playback is a figure widgets have no other way to learn: the Chronometer
        // counts connected time, not playback-only totals. Runs only while a session is open,
        // the only time it would change anything.
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
     * On `container.scope` rather than `lifecycleScope`, as in [onDestroy]: the lifecycle scope
     * dies with the service, and the write's first suspension point is the database. Nothing
     * here closes a session, so there's no ordering to keep.
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
     * Posts [text] unless it already matches. A refusal stops the service rather than letting it
     * be killed for never calling startForeground; nothing downstream needs to know, so this
     * reports nothing.
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
            // Narrow on purpose: the refusals here are all RuntimeExceptions — SecurityException
            // (missing service-type permission), ForegroundServiceStartNotAllowedException,
            // InvalidForegroundServiceTypeException, IllegalStateException — each meaning
            // detailed tracking cannot run right now. Catching Throwable would
            // fold an error from `trackingNotification` into the same silent `stopSelf`, the
            // failure shape the Notifications KDoc records as painful to find.
            Log.e(TAG, "Foreground start refused; detailed tracking cannot run", e)
            stopSelf()
        }
    }

    /**
     * Mirrors [TrackingController.detailedTrackingSupported]'s check, so as not to declare a
     * type it lacks.
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
