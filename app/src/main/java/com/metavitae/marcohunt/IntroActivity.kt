package com.metavitae.marcohunt

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.webkit.JavascriptInterface
import android.webkit.WebView

/**
 * "Kithe presents Marco Hunt": plays on every start (tap skips). The very first time,
 * a one-time surprise comes first. Then the home screen.
 */
class IntroActivity : Activity() {

    override fun attachBaseContext(base: Context) = super.attachBaseContext(Lang.wrap(base))

    private val ui = Handler(Looper.getMainLooper())
    private var left = false

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(saved: Bundle?) {
        super.onCreate(saved)
        val prefs = getSharedPreferences("marcohunt", Context.MODE_PRIVATE)
        val firstTime = !prefs.getBoolean("unwrapped", false)
        val web = WebView(this)
        web.settings.javaScriptEnabled = true
        web.settings.mediaPlaybackRequiresUserGesture = false
        web.setBackgroundColor(if (firstTime) Art.PARCHMENT else 0xFF0D1730.toInt())
        web.addJavascriptInterface(object {
            @JavascriptInterface fun done() { ui.post { goHome(firstTime) } }
        }, "Android")
        web.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        setContentView(web)
        val lang = if (Lang.current(this) == "es") "es" else "en"
        web.loadUrl("file:///android_asset/intro/intro.html?lang=$lang" + if (firstTime) "&unwrap=1" else "")
        // safety: never get stuck on the opening (the surprise waits for a tap, so it gets longer)
        ui.postDelayed({ goHome(firstTime) }, if (firstTime) 120_000L else 16_000L)
    }

    private fun goHome(firstTime: Boolean) {
        if (left) return
        left = true
        if (firstTime) getSharedPreferences("marcohunt", Context.MODE_PRIVATE).edit().putBoolean("unwrapped", true).apply()
        startActivity(Intent(this, MainActivity::class.java))
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
        finish()
    }

    override fun onDestroy() { ui.removeCallbacksAndMessages(null); super.onDestroy() }
}
