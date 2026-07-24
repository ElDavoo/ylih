package it.eldavo.ylih

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import it.eldavo.ylih.ui.YlihNavHost
import it.eldavo.ylih.ui.theme.YlihTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            // Denied Bluetooth just means no Bluetooth tracking; the UI explains the state.
            container().scope.launch { container().trackingController.syncWithSystem() }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            YlihTheme {
                YlihNavHost()
            }
        }
        requestMissingPermissions()
    }

    override fun onStart() {
        super.onStart()
        // Opening the app is a good moment to repair anything the system never told us about.
        container().scope.launch { container().trackingController.syncWithSystem() }
    }

    private fun container() = (application as YlihApp).container

    private fun requestMissingPermissions() {
        val wanted = buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) add(Manifest.permission.BLUETOOTH_CONNECT)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
        }.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (wanted.isNotEmpty()) permissionLauncher.launch(wanted.toTypedArray())
    }
}
