package com.metavitae.marcohunt

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF

/**
 * Every picture in the game has a stable name. If assets/art/<name>.png exists it is drawn;
 * otherwise a plain placeholder (flat shape + short label) is drawn. Swapping in real art =
 * dropping the PNG into app/src/main/assets/art/ with that name. The list is in the M1 Log.
 */
object Art {
    private val cache = HashMap<String, Bitmap?>()
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ink = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2f; color = INK }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = INK; textAlign = Paint.Align.CENTER; isFakeBoldText = true }

    const val PARCHMENT = 0xFFF1E4C8.toInt()
    const val INK = 0xFF5A3A1E.toInt()
    const val MADDER = 0xFFA8322D.toInt()
    const val VERDIGRIS = 0xFF3F8A74.toInt()
    const val LAPIS = 0xFF2F4F9A.toInt()
    const val GOLD = 0xFFC9A227.toInt()

    fun bitmap(ctx: Context, name: String): Bitmap? = cache.getOrPut(name) {
        try { ctx.assets.open("art/$name.png").use { BitmapFactory.decodeStream(it) } } catch (e: Exception) { null }
    }

    /** Draws the named piece into r. color/label are only used by the placeholder. */
    fun draw(ctx: Context, c: Canvas, name: String, r: RectF, color: Int, label: String, round: Boolean = false) {
        val bmp = bitmap(ctx, name)
        if (bmp != null) { c.drawBitmap(bmp, null, r, null); return }
        fill.color = color
        if (round) { c.drawOval(r, fill); c.drawOval(r, ink) }
        else { c.drawRoundRect(r, r.width() * 0.18f, r.height() * 0.18f, fill); c.drawRoundRect(r, r.width() * 0.18f, r.height() * 0.18f, ink) }
        if (label.isNotEmpty()) {
            val glyphs = label.codePointCount(0, label.length)
            text.textSize = r.height() * (when { glyphs == 1 && label.codePointAt(0) > 0x2000 -> 0.62f; label.length <= 2 -> 0.5f; else -> 0.28f })
            c.drawText(label, r.centerX(), r.centerY() - (text.descent() + text.ascent()) / 2, text)
        }
    }

    private val shadow = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x55301A08 }
    private val rim = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }

    /** A game piece (until painted tokens exist): head and base, soft shadow, class colour, number on the base. */
    fun pawn(c: Canvas, r: RectF, color: Int, label: String, ring: Int, moving: Boolean = false) {
        val w = r.width(); val h = r.height()
        val lift = if (moving) h * 0.06f else 0f
        c.drawOval(RectF(r.left + w * 0.14f, r.bottom - h * 0.16f, r.right - w * 0.14f, r.bottom), shadow)
        val base = RectF(r.left + w * 0.12f, r.top + h * 0.40f - lift, r.right - w * 0.12f, r.bottom - h * 0.06f - lift)
        val head = RectF(r.left + w * 0.28f, r.top + h * 0.02f - lift, r.right - w * 0.28f, r.top + h * 0.46f - lift)
        fill.color = color
        c.drawRoundRect(base, w * 0.22f, w * 0.22f, fill); c.drawRoundRect(base, w * 0.22f, w * 0.22f, ink)
        c.drawOval(head, fill); c.drawOval(head, ink)
        if (ring != 0) { rim.color = ring; rim.strokeWidth = w * 0.07f; c.drawRoundRect(base, w * 0.22f, w * 0.22f, rim) }
        text.textSize = base.height() * 0.62f
        c.drawText(label, base.centerX(), base.centerY() - (text.descent() + text.ascent()) / 2, text)
    }

    // stable names (see the asset list in the Log)
    val CLASS = arrayOf("class_knight", "class_traveler", "class_magus", "class_mongol")
    val CLASS_COLOR = intArrayOf(0xFFB0B7C3.toInt(), 0xFFD9A86C.toInt(), 0xFF8E7CC3.toInt(), 0xFF7FB27A.toInt())
    val DECO = arrayOf("deco_stone", "deco_banner", "deco_reeds", "deco_tent")
    val CONTAINER = arrayOf("open_crate", "open_box", "open_trunk")
    const val HOLE = "open_hole"

    /** The era's creature that appears when a peril is found (painted with Canva AI; the siren is left out, care rule). */
    fun creatureFor(peril: com.metavitae.marcohunt.engine.Types.Content, square: Int): String? = when (peril) {
        com.metavitae.marcohunt.engine.Types.Content.SERPENT -> arrayOf("creature_serpent", "creature_dragon", "creature_basilisk")[square % 3]
        com.metavitae.marcohunt.engine.Types.Content.SCATTER -> arrayOf("creature_hafgufa", "creature_ruc")[square % 2]
        com.metavitae.marcohunt.engine.Types.Content.FREEZE -> "creature_whitebear"
        else -> null
    }
    const val BOARD = "board_map"
    const val ORACLE = "square_oracle"
    const val SPOTLIGHT = "square_spotlight"
    val LANDMARK = arrayOf("landmark_labyrinth", "landmark_lighthouse", "landmark_town", "landmark_bridge", "landmark_caravan", "landmark_fleece")
    // placeholder pictures until the painted landmarks arrive (emoji: colourful and readable at a glance)
    val LANDMARK_LABEL = arrayOf("🌀", "🗼", "🏰", "🌉", "⛺", "🌳")
    val MARK = arrayOf("mark_fountain", "mark_perch", "mark_palm", "mark_shrine")
    val MARK_LABEL = arrayOf("⛲", "🦅", "🌴", "🛕")
    /** Unopened decorations (all four hide the same kinds of things). */
    val DECO_LABEL = arrayOf("🪨", "🌾", "🏺", "🌵")
}
