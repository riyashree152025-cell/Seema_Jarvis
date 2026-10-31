package com.seema.assistant

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat

/**
 * Brings the always-on listener back after a reboot ([Intent.ACTION_BOOT_COMPLETED] and the OEM
 * quick-boot variants) or after SEEMA is updated ([Intent.ACTION_MY_PACKAGE_REPLACED]).
 *
 * The service is only started when the user had turned it on before: [SeemaVoiceService] writes
 * [SeemaVoiceService.KEY_SERVICE_ENABLED], so a reboot can never switch the microphone on behind
 * the user's back, and "stop SEEMA" stays stopped.
 *
 * Android 12+ may reject a foreground service start from the background with
 * `ForegroundServiceStartNotAllowedException`; since Android 14 that always happens for the
 * microphone type at boot. A broadcast has no UI to explain this, so the failure is logged and
 * the user restarts SEEMA from the app (which is also why the flag stays true).
 */
class SeemaBootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action !in RESTART_ACTIONS) return
        val appContext = context.applicationContext

        val enabled = appContext.getSharedPreferences(SeemaVoiceService.PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(SeemaVoiceService.KEY_SERVICE_ENABLED, false)
        if (!enabled) {
            Log.i(TAG, "Not restarting SEEMA after $action: the user had turned it off")
            return
        }

        try {
            ContextCompat.startForegroundService(
                appContext,
                Intent(appContext, SeemaVoiceService::class.java)
            )
            Log.i(TAG, "Restarting SEEMA after $action")
        } catch (e: Exception) {
            // ForegroundServiceStartNotAllowedException on Android 12+; never crash a boot receiver.
            Log.w(TAG, "Could not restart SEEMA after $action", e)
        }
    }

    private companion object {
        const val TAG = "SeemaBootReceiver"

        /** A quick-boot resume does not always send BOOT_COMPLETED, so the OEM actions are included. */
        val RESTART_ACTIONS = setOf(
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            "android.intent.action.QUICKBOOT_POWERON",
            "com.htc.intent.action.QUICKBOOT_POWERON"
        )
    }
}