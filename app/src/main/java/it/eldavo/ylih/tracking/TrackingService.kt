package it.eldavo.ylih.tracking

import android.Manifest
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
import it.eldavo.ylih.Distribution
import it.eldavo.ylih.R
import it.eldavo.ylih.YlihApp
import it.eldavo.ylih.data.AppContainer
import it.eldavo.ylih.data.DeviceIdentity
import it.eldavo.ylih.data.trackedKinds
import it.eldavo.ylih.ui.formatDurationShort
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Runs only in "detailed tracking" mode. Wired plug events are never delivered to a manifest
 * receiver, so observing them at all requires a live process — that is the entire reason this
 * service (and its notification) exists. While it is up it also measures playback time.
 */
class TrackingService : LifecycleService() {

    private lateinit var container: AppContainer
    private val audioManager: AudioManager by lazy { getSystemService(AudioManager::class.java) }
    private val handler = Handler(Looper.getMainLooper())

    /** Playback is credited to the most recently connected pair. */
    private var playbackTargetKey: String? = null
    private var playbackWatcher: PlaybackWatcher? = null

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

    override fun onCreate() {
        super.onCreate()
        container = (application as YlihApp).container

        Notifications.ensureChannel(this)
        startForegroundCompat(getString(R.string.notification_starting))

        playbackWatcher = PlaybackWatcher(audioManager) { deltaMs -> creditPlayback(deltaMs) }
            .also { it.start(handler) }
        audioManager.registerAudioDeviceCallback(deviceCallback, handler)

        lifecycleScope.launch {
            container.trackingController.syncWithSystem()
            refreshNotification()
            while (isActive) {
                delay(TICK_MS)
                tick()
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        return START_STICKY
    }

    override fun onDestroy() {
        val now = container.clock.now()
        audioManager.unregisterAudioDeviceCallback(deviceCallback)
        playbackWatcher?.stop(now)
        playbackWatcher = null
        super.onDestroy()
    }

    private fun onDevicesChanged(identities: List<DeviceIdentity>, connected: Boolean) {
        if (identities.isEmpty()) return
        val kinds = trackedKinds(detailedTracking = true)
        lifecycleScope.launch {
            val now = container.clock.now()
            for (identity in identities.filter { it.kind in kinds }) {
                if (connected) {
                    container.repository.onConnected(identity, now, measurePlayback = true)
                    // Any accrued playback belongs to the previous target, not this one.
                    playbackWatcher?.rebase(now)
                    playbackTargetKey = identity.key
                } else {
                    container.repository.onDisconnected(identity.key, now)
                    if (playbackTargetKey == identity.key) {
                        playbackWatcher?.refresh(now)
                        playbackTargetKey = null
                    }
                }
            }
            refreshNotification()
        }
    }

    private suspend fun tick() {
        val now = container.clock.now()
        container.repository.heartbeat(now)
        playbackWatcher?.refresh(now)
        refreshNotification()
    }

    private fun creditPlayback(deltaMs: Long) {
        val key = playbackTargetKey ?: return
        lifecycleScope.launch {
            container.repository.openSessionIdFor(key)?.let { sessionId ->
                container.repository.addPlayback(sessionId, deltaMs)
            }
        }
    }

    private suspend fun refreshNotification() {
        val now = container.clock.now()
        val open = container.repository.openSessionsSnapshot()
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
     * @return false if the platform refused the foreground start, in which case the service has
     *   already stopped itself rather than waiting to be killed for never calling
     *   startForeground.
     */
    private fun startForegroundCompat(text: String): Boolean = try {
        ServiceCompat.startForeground(
            this,
            Notifications.ID_TRACKING,
            Notifications.trackingNotification(this, text),
            foregroundServiceType(),
        )
        true
    } catch (e: Exception) {
        Log.e(TAG, "Foreground start refused; detailed tracking cannot run", e)
        stopSelf()
        false
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
