package com.metavitae.marcohunt

import android.content.Context
import android.content.res.Configuration
import java.util.Locale

/** English or Spanish: follows the phone unless the player switched it in the app. */
object Lang {
    private const val PREFS = "marcohunt"

    fun wrap(base: Context): Context {
        val tag = base.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("lang", null) ?: return base
        val cfg = Configuration(base.resources.configuration)
        cfg.setLocale(Locale(tag))
        return base.createConfigurationContext(cfg)
    }

    fun current(ctx: Context): String = ctx.resources.configuration.locales[0].language

    fun toggle(ctx: Context) {
        val next = if (current(ctx) == "es") "en" else "es"
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("lang", next).apply()
    }
}
