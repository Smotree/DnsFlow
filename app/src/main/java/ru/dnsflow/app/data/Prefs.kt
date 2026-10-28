package ru.dnsflow.app.data

import android.content.Context
import androidx.core.content.edit

class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    /** After a cargo place, ask for the product EAN. */
    var askEan: Boolean
        get() = sp.getBoolean("askEan", true)
        set(v) = sp.edit { putBoolean("askEan", v) }

    /** Look products up on dns-shop.ru in the background. */
    var autoLookup: Boolean
        get() = sp.getBoolean("autoLookup", true)
        set(v) = sp.edit { putBoolean("autoLookup", v) }

    var sound: Boolean
        get() = sp.getBoolean("sound", true)
        set(v) = sp.edit { putBoolean("sound", v) }

    var vibrate: Boolean
        get() = sp.getBoolean("vibrate", true)
        set(v) = sp.edit { putBoolean("vibrate", v) }

    /** Shown on places merged from this phone into someone else's acceptance. */
    var deviceName: String
        get() = sp.getString("deviceName", null)?.takeIf { it.isNotBlank() } ?: android.os.Build.MODEL
        set(v) = sp.edit { putString("deviceName", v.trim()) }

    /** One-time re-check of products found before the stale-page fix (1.8). */
    var lookupRechecked: Boolean
        get() = sp.getBoolean("lookupRecheckedV18", false)
        set(v) = sp.edit { putBoolean("lookupRecheckedV18", v) }

    /** Max screen brightness while showing codes for the 1C scanner. */
    var reviewBrightness: Boolean
        get() = sp.getBoolean("reviewBrightness", true)
        set(v) = sp.edit { putBoolean("reviewBrightness", v) }

    /** Also show the EAN barcode during review (to scan it into 1C). */
    var reviewShowEan: Boolean
        get() = sp.getBoolean("reviewShowEan", true)
        set(v) = sp.edit { putBoolean("reviewShowEan", v) }
}
