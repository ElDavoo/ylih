package it.eldavo.ylih

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import it.eldavo.ylih.ui.YlihNavHost
import it.eldavo.ylih.ui.theme.YlihTheme
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    /**
     * Pair ids arriving from a home-screen widget tap.
     *
     * Conflated and consumed once: a rotation must not replay the tap that started the activity,
     * and a second tap on a different row while the app is already open must re-navigate rather
     * than stack another copy of it — which is what `singleTop` plus [onNewIntent] arrange.
     */
    private val openPair = Channel<Long>(Channel.CONFLATED)

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            // Denied Bluetooth just means no Bluetooth tracking; the UI explains the state.
            container().scope.launch { container().trackingController.syncWithSystem() }
        }

    /**
     * Below Android 13 the app carries its own language setting, and a context only ever gets one
     * configuration — the one it is attached with. Recreating the activity is what applies a
     * change; see [AppLocale].
     */
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppLocale.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Only on a genuinely new launch: after a rotation the same intent is still attached, and
        // acting on it again would drag the user back out of wherever they had navigated to.
        if (savedInstanceState == null) intent?.let(::offerPair)
        setContent {
            YlihTheme {
                YlihNavHost(openPair = openPair.receiveAsFlow())
            }
        }
        // The first run asks for Bluetooth itself, on a page that says what it is for, so doing it
        // here as well would put a second prompt behind the one the user just answered. What is
        // left for this to cover is every launch after that: an install upgraded from a version
        // that asked in one unexplained batch, and a permission denied once that the user may
        // since have changed their mind about. Android stops showing the prompt after two
        // refusals, so this cannot nag.
        lifecycleScope.launch {
            if (container().settings.onboardingDoneNow()) requestMissingPermissions()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        offerPair(intent)
    }

    override fun onStart() {
        super.onStart()
        // Opening the app is a good moment to repair anything the system never told us about.
        container().scope.launch { container().trackingController.syncWithSystem() }
    }

    private fun offerPair(intent: Intent) {
        val pairId = intent.getLongExtra(EXTRA_PAIR_ID, NO_PAIR)
        if (pairId != NO_PAIR) openPair.trySend(pairId)
    }

    private fun container() = (application as YlihApp).container

    /**
     * Bluetooth only. Notifications are asked for by the detailed-tracking switch, which is the
     * one thing in the app that posts one — asking here would be asking every install for a
     * permission most of them never give anything to use.
     */
    private fun requestMissingPermissions() {
        val wanted = buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) add(Manifest.permission.BLUETOOTH_CONNECT)
        }.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (wanted.isNotEmpty()) permissionLauncher.launch(wanted.toTypedArray())
    }

    companion object {
        /** Set by the lifetime widget's rows; opens that pair's detail screen. */
        const val EXTRA_PAIR_ID = "it.eldavo.ylih.PAIR_ID"

        private const val NO_PAIR = -1L
    }
}
