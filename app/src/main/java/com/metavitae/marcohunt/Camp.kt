package com.metavitae.marcohunt

import android.content.Context
import java.io.File

/** The one camp save on this phone (app-private storage; no personal data, only the game). */
object Camp {
    private fun file(ctx: Context) = File(ctx.filesDir, "camp.txt")
    fun write(ctx: Context, text: String) = try { file(ctx).writeText(text) } catch (e: Exception) { }
    fun read(ctx: Context): String? = try { file(ctx).takeIf { it.exists() }?.readText() } catch (e: Exception) { null }
    /** Only games of the current rules can continue; a save from the older rules stays in the file but isn't offered. */
    fun exists(ctx: Context) = read(ctx)?.contains("rules.hiddenMarco=true") == true
    fun clear(ctx: Context) { try { file(ctx).delete() } catch (e: Exception) { } }
}
