package com.seema.assistant

import android.Manifest
import android.annotation.SuppressLint
import android.app.StatusBarManager
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color as AndroidColor
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.seema.assistant.ui.jarvis.SeemaHudScreen
import com.seema.assistant.ui.theme.SeemaTheme

/**
 * The only activity: shows the JARVIS interface and makes sure the always-on voice service
 * can actually run.
 *
 * Permissions are requested in waves instead of one giant dialog, because a ten-permission
 * prompt on first launch is the fastest way to get a personal assistant uninstalled. The
 * voice service only needs the microphone; everything else has a degraded but working path,
 * except the two silent killers handled below — the overlay grant (needed by openApp,
 * playMusic and setAlarm on Android 10+) and the notification listener (needed by
 * readNotifications and replyToMessage).
 */
class MainActivity : ComponentActivity() {

    private var micGranted by mutableStateOf(false)
    private var askedOnce = false
    private var batteryPromptShown = false
    private var overlayPromptShown = false
    private var listenerPromptShown = false

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        micGranted = hasMicPermission()
        if (micGranted) {
            startVoiceService()
            requestBackgroundLocation()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(AndroidColor.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(AndroidColor.TRANSPARENT)
        )

        micGranted = hasMicPermission()
        SeemaRoutines.init(this)

        setContent {
            SeemaTheme {
                val state by SeemaState.state.collectAsStateWithLifecycle()
                val lastResponse by SeemaState.lastResponse.collectAsStateWithLifecycle()
                val audioLevel by SeemaState.audioLevel.collectAsStateWithLifecycle()
                val serviceRunning by SeemaState.serviceRunning.collectAsStateWithLifecycle()
                val routines by SeemaRoutines.routinesFlow.collectAsStateWithLifecycle()

                SeemaHudScreen(
                    state = state,
                    lastResponse = lastResponse,
                    audioLevel = audioLevel,
                    serviceRunning = serviceRunning,
                    micGranted = micGranted,
                    routineNames = routines.map { it.name },
                    onGrantClick = ::onGrantClick,
                    onTalk = { SeemaState.send(SeemaCommand.START_CONVERSATION) },
                    onSendText = { text -> SeemaState.sendText(text) }
                )
            }
        }

        if (micGranted) {
            startVoiceService()
            requestBackgroundLocation()
        } else {
            requestPermissions()
        }
    }

    override fun onResume() {
        super.onResume()
        // Covers grants made from system Settings while the app was in the background.
        if (!micGranted && hasMicPermission()) {
            micGranted = true
            startVoiceService()
        }
        requestMissingCapabilities()
    }

    private fun hasMicPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    // ------------------------------------------------------------------ permission flow

    /** Wave one: the microphone, plus the everyday tools. Optional ones are skipped, not fatal. */
    private fun requestPermissions() {
        askedOnce = true
        val permissions = buildList {
            add(Manifest.permission.RECORD_AUDIO)
            add(Manifest.permission.READ_CONTACTS)
            add(Manifest.permission.CALL_PHONE)
            add(Manifest.permission.SEND_SMS)
            add(Manifest.permission.READ_SMS)
            add(Manifest.permission.READ_PHONE_STATE)
            add(Manifest.permission.READ_CALL_LOG)
            add(Manifest.permission.ACCESS_COARSE_LOCATION)
            add(Manifest.permission.ACCESS_FINE_LOCATION)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                add(Manifest.permission.BLUETOOTH_CONNECT)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        permissionLauncher.launch(permissions.toTypedArray())
    }

    /**
     * Wave two: "allow all the time" location, requested only after fine location is granted —
     * Android refuses to show the background dialog otherwise, and geofence reminders stay dead
     * without it. The system page is the only reliable route on Android 11+.
     */
    private fun requestBackgroundLocation() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) !=
            PackageManager.PERMISSION_GRANTED
        ) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_BACKGROUND_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        ) return
        runCatching {
            startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                    .setData(Uri.fromParts("package", packageName, null))
            )
        }
    }

    /**
     * The two silent killers. Both are special accesses with no runtime dialog, both are
     * checked by tools at call time, and without them "open YouTube", "play music", "set an
     * alarm" and "read my notifications" all fail.
     */
    private fun requestMissingCapabilities() {
        if (!overlayPromptShown && !canDrawOverlays()) {
            overlayPromptShown = true
            runCatching {
                startActivity(
                    Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION)
                        .setData(Uri.fromParts("package", packageName, null))
                )
            }
        }
        if (!listenerPromptShown && !hasNotificationAccess()) {
            listenerPromptShown = true
            runCatching { startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }
        }
    }

    private fun canDrawOverlays(): Boolean = Settings.canDrawOverlays(this)

    private fun hasNotificationAccess(): Boolean {
        val flat = Settings.Secure.getString(contentResolver, "enabled_notification_listeners")
            ?: return false
        return flat.contains(packageName)
    }

    private fun onGrantClick() {
        val permanentlyDenied = askedOnce &&
            !shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO)
        if (permanentlyDenied) {
            startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                    .setData(Uri.fromParts("package", packageName, null))
            )
        } else {
            requestPermissions()
        }
    }

    // ------------------------------------------------------------------ quick settings / widget

    /** Android 13+: system dialog that adds the SEEMA tile to Quick Settings in one tap. */
    private fun requestAddTile() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        getSystemService(StatusBarManager::class.java).requestAddTileService(
            ComponentName(this, SeemaTileService::class.java),
            "SEEMA",
            Icon.createWithResource(this, R.drawable.ic_seema_tile),
            mainExecutor
        ) { result -> Log.i("SeemaTile", "Add tile result: $result") }
    }

    /** Launcher dialog that places the SEEMA widget on the home screen. */
    private fun requestAddWidget() {
        AppWidgetManager.getInstance(this)
            .requestPinAppWidget(ComponentName(this, SeemaWidgetProvider::class.java), null, null)
    }

    // ------------------------------------------------------------------ service lifecycle

    private fun startVoiceService() {
        ContextCompat.startForegroundService(this, Intent(this, SeemaVoiceService::class.java))
        requestBatteryExemption()
    }

    /** Asks once per launch to exclude SEEMA from Doze so the service is not throttled. */
    @SuppressLint("BatteryLife")
    private fun requestBatteryExemption() {
        if (batteryPromptShown) return
        val power = getSystemService(PowerManager::class.java)
        if (power.isIgnoringBatteryOptimizations(packageName)) return
        batteryPromptShown = true
        startActivity(
            Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                .setData(Uri.fromParts("package", packageName, null))
        )
    }
}