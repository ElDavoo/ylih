package it.eldavo.ylih.ui

import android.Manifest
import android.app.Application
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The notification permission does not exist below Android 13, and asking for one that does not
 * exist is a prompt that never appears and a switch that waits for an answer it will never get.
 * So which SDK the app is on decides whether there is anything to ask at all, and that decision
 * is a plain function precisely so it can be checked on both sides of the line rather than only
 * on whichever the test runner happens to default to.
 */
@RunWith(RobolectricTestRunner::class)
class PermissionRationaleTest {

    private val context: Application = ApplicationProvider.getApplicationContext()

    @Test
    @Config(sdk = [Build.VERSION_CODES.TIRAMISU])
    fun `from android 13 a notification permission not yet held is worth asking for`() {
        shadowOf(context).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)

        assertEquals(
            Manifest.permission.POST_NOTIFICATIONS,
            notificationPermissionToAsk(context),
        )
    }

    @Test
    @Config(sdk = [Build.VERSION_CODES.TIRAMISU])
    fun `an install that already holds it is not asked again`() {
        shadowOf(context).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)

        assertNull(notificationPermissionToAsk(context))
    }

    /**
     * The Android 8 floor and the last release before the permission existed. Neither can be asked,
     * and on both the platform reports it as held, so a caller that tested "granted" rather than
     * "is there anything to ask" would reach the same answer for the wrong reason.
     */
    @Test
    @Config(sdk = [Build.VERSION_CODES.O, Build.VERSION_CODES.S_V2])
    fun `below android 13 there is no notification permission to ask for`() {
        assertNull(notificationPermissionToAsk(context))
    }

    @Test
    @Config(sdk = [Build.VERSION_CODES.TIRAMISU])
    fun `granted reads the permission the caller names, not any other`() {
        shadowOf(context).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        shadowOf(context).denyPermissions(Manifest.permission.BLUETOOTH_CONNECT)

        assertTrue(granted(context, Manifest.permission.POST_NOTIFICATIONS))
        assertFalse(granted(context, Manifest.permission.BLUETOOTH_CONNECT))
    }
}
