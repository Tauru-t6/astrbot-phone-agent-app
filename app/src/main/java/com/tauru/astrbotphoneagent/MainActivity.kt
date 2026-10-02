package com.tauru.astrbotphoneagent

import android.Manifest
import android.content.pm.PackageManager
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.tauru.astrbotphoneagent.ui.AppRoot

class MainActivity : ComponentActivity() {

    // The AppState singleton lives in PhoneAgentApp so the debug receiver
    // and the UI share one instance.
    private val appState: com.tauru.astrbotphoneagent.data.RealAppState
        get() = (application as PhoneAgentApp).appState

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestRuntimePermissions()
        ContextCompat.startForegroundService(this, Intent(this, PhoneConnectionService::class.java))
        setContent {
            AppRoot(appState)
        }
    }

    private fun requestRuntimePermissions() {
        val wanted = (listOf(
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.ACCESS_FINE_LOCATION,
        ) + if (Build.VERSION.SDK_INT >= 33) listOf(Manifest.permission.POST_NOTIFICATIONS) else emptyList()).filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (wanted.isNotEmpty()) {
            permissionLauncher.launch(wanted.toTypedArray())
        }
    }
}
