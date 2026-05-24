package com.tldv.zar

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Receives the daily alarm to enable dark mode automatically.
 * If the app is foregrounded, delegates to MainActivity; otherwise
 * just flips the shared preference so the next launch picks it up.
 */
class NightModeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        context.getSharedPreferences("zar_prefs", Context.MODE_PRIVATE)
            .edit()
            .putBoolean("dark", true)
            .apply()
    }
}
