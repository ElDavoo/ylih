package it.eldavo.ylih.tracking

import android.service.notification.NotificationListenerService

/**
 * Exists to be granted, never to run.
 *
 * The audio callbacks [PlaybackWatcher] reads never say which app is playing: the platform
 * anonymises them for every app without `MODIFY_AUDIO_ROUTING`, a signature permission. Media
 * sessions do name their app, and `MediaSessionManager.getActiveSessions` hands them to a caller
 * holding `MEDIA_CONTENT_CONTROL` — signature again — or to one whose notification listener the
 * user has enabled. This class is that listener. The platform checks the *grant*, not a binding,
 * so nothing here needs to run and no notification is ever read; see [MediaSessions].
 *
 * Bound, a listener would keep the process alive for as long as access stays on, ending
 * Bluetooth-only mode's promise that nothing of the app runs. Android 14 reads the manifest's
 * `default_autobind_listenerservice` and never binds it; below that the system binds it at each
 * boot, and [onListenerConnected] hands the binding straight back.
 */
class SessionAccessService : NotificationListenerService() {

    override fun onListenerConnected() {
        requestUnbind()
    }
}
