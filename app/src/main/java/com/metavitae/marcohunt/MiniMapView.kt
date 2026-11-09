package com.metavitae.marcohunt

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.view.View
import com.metavitae.marcohunt.engine.Game

/**
 * The little map that floats over the camera (founder, Nov 8: "no way to know where the players start").
 * It shows the whole board from the first moment: corners A and B (the two objects of Set board),
 * every traveller, your star, where the die lets you go, and where the phone says you are standing.
 * Row 1, column 1 is corner A at the top left. Tap it to make it bigger.
 */
class MiniMapView(ctx: Context) : View(ctx) {
    var game: Game? = null
    var humanId = 0
    var lit: Set<Int> = emptySet()
    var picked = -1
    /** The square the phone says it stands on, as row * n + column, or -1. */
    var here = -1

    private val d = resources.displayMetrics.density
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = 0x663A2412; strokeWidth = d * 0.6f }
    private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = Art.INK; strokeWidth = d * 1.5f }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD) }

    override fun onDraw(c: Canvas) {
        val g = game ?: return
        val n = g.n
        val side = minOf(width, height).toFloat()
        val cell = side / n
        fill.color = 0xFFFBF3DF.toInt()
        c.drawRect(0f, 0f, side, side, fill)
        fun x(col: Float) = (col + 0.5f) * cell
        fun y(row: Float) = (row + 0.5f) * cell
        fun square(i: Int, color: Int) {
            fill.color = color
            val r = i / n; val k = i % n
            c.drawRect(k * cell, r * cell, (k + 1) * cell, (r + 1) * cell, fill)
        }
        for (i in lit) square(i, 0xAAE3C46A.toInt())
        if (picked >= 0) square(picked, 0xCC5B7FD1.toInt())
        for (i in 1 until n) {
            c.drawLine(i * cell, 0f, i * cell, side, line)
            c.drawLine(0f, i * cell, side, i * cell, line)
        }
        c.drawRect(0f, 0f, side, side, edge)

        // the two corners of Set board
        val tokenR = (cell * 0.48f).coerceAtLeast(d * 3f)
        fun corner(r: Int, k: Int, t: String) {
            fill.color = Art.MADDER
            c.drawRect(k * cell, r * cell, (k + 1) * cell, (r + 1) * cell, fill)
            label.color = 0xFFFFFFFF.toInt(); label.textSize = cell * 0.85f
            c.drawText(t, x(k.toFloat()), y(r.toFloat()) + label.textSize * 0.34f, label)
        }
        corner(0, 0, "A")
        corner(n - 1, n - 1, "B")

        // the travellers, you last so the star is never hidden
        for (p in g.players) {
            if (p.id == humanId) continue
            fill.color = Art.CLASS_COLOR[p.cls.ordinal]
            c.drawCircle(x(p.c.toFloat()), y(p.r.toFloat()), tokenR, fill)
            c.drawCircle(x(p.c.toFloat()), y(p.r.toFloat()), tokenR, edge)
        }
        // where the phone is standing: a blue ring
        if (here >= 0) {
            fill.color = 0xFF2F6FD0.toInt()
            c.drawCircle(x((here % n).toFloat()), y((here / n).toFloat()), tokenR * 1.15f, fill)
        }
        val me = g.players[humanId]
        label.color = Art.INK; label.textSize = (cell * 1.25f).coerceAtLeast(d * 9f)
        c.drawText("★", x(me.c.toFloat()), y(me.r.toFloat()) + label.textSize * 0.36f, label)
    }
}
