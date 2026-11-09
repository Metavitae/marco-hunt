package com.metavitae.marcohunt

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The game diary (Nov 7): every turn, tap, card and line of news of a real game is written to
 * Download/MarcoHunt/games/<time>_game.txt, so CC can read exactly how a game went afterwards.
 * Nothing leaves the phone.
 */
class Diary(private val ctx: Context) {
    private var uri: Uri? = null
    private val t0 = System.currentTimeMillis()
    private val buf = StringBuilder()

    fun start(head: String) {
        try {
            if (Build.VERSION.SDK_INT < 29) return
            val stamp = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date())
            val v = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, "${stamp}_game.txt")
                put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
                put(MediaStore.MediaColumns.RELATIVE_PATH, "Download/MarcoHunt/games")
            }
            uri = ctx.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v)
            val app = try { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName } catch (e: Exception) { "?" }
            write("Marco Hunt game diary · ${Build.MANUFACTURER} ${Build.MODEL} · app $app\n$head")
        } catch (e: Exception) { uri = null }
    }

    fun line(round: Int, text: String) {
        val s = (System.currentTimeMillis() - t0) / 1000
        write("[%02d:%02d] R%d · %s".format(Locale.US, s / 60, s % 60, round, text.replace("\n", " / ")))
    }

    private fun write(s: String) {
        buf.append(s).append('\n')
        val u = uri ?: return
        try { ctx.contentResolver.openOutputStream(u, "wt")?.use { it.write(buf.toString().toByteArray()) } } catch (e: Exception) { }
    }
}
