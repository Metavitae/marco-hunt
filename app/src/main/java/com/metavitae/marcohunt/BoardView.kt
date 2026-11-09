package com.metavitae.marcohunt

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import com.metavitae.marcohunt.engine.Game
import com.metavitae.marcohunt.engine.Types.Content

/** The plain on-screen board (M1): parchment grid, decorations, opened squares, landmarks, travellers. */
class BoardView(ctx: Context) : View(ctx) {
    var game: Game? = null
    var humanId = 0
    /** squares to light up (the human's reachable squares or picked Divination squares) */
    var lit: Set<Int> = emptySet()
    var picked: Set<Int> = emptySet()
    var revealedMarco = -1
    /** Tokens on the move (walk animation): player id -> (row, col) as floats. */
    val anim = HashMap<Int, FloatArray>()
    var onTap: ((Int, Int) -> Unit)? = null
    var onLongTap: ((Int, Int) -> Unit)? = null
    private var downAt = 0L

    private val grid = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Art.INK; alpha = 90; strokeWidth = 1.5f }
    private val litPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Art.GOLD; alpha = 120 }
    private val pickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Art.LAPIS; alpha = 140 }
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 5f; color = Art.MADDER }
    private val bg = Paint().apply { color = Art.PARCHMENT }
    private val frame = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 5f; color = Art.INK }

    override fun onMeasure(w: Int, h: Int) {
        val size = minOf(MeasureSpec.getSize(w), MeasureSpec.getSize(h).takeIf { it > 0 } ?: MeasureSpec.getSize(w))
        setMeasuredDimension(size, size)
    }

    private fun cell(): Float { val g = game ?: return 1f; return width / g.n.toFloat() }

    override fun onDraw(c: Canvas) {
        val g = game ?: return
        val s = cell()
        val board = RectF(0f, 0f, width.toFloat(), height.toFloat())
        if (Art.bitmap(context, Art.BOARD) != null) Art.draw(context, c, Art.BOARD, board, 0, "") else c.drawRect(board, bg)
        // lit squares
        for (i in lit) c.drawRect(rect(i / g.n, i % g.n, s, 0f), litPaint)
        for (i in picked) c.drawRect(rect(i / g.n, i % g.n, s, 0f), pickPaint)
        for (k in 0..g.n) {
            c.drawLine(0f, k * s, width.toFloat(), k * s, grid)
            c.drawLine(k * s, 0f, k * s, height.toFloat(), grid)
        }
        // the Oracle's landmarks and the small marks near them (placed at random each game)
        for ((k, lm) in g.landmarks.withIndex())
            Art.draw(context, c, Art.LANDMARK[k], rect(lm[0], lm[1], s, s * 0.06f), 0x66C9A227, Art.LANDMARK_LABEL[k])
        for (m in g.marks)
            Art.draw(context, c, Art.MARK[m[2]], rect(m[0], m[1], s, s * 0.22f), 0x553F8A74, Art.MARK_LABEL[m[2]], round = true)
        // decorated squares: all look alike until opened
        for (i in 0 until g.n * g.n) {
            if (!g.decorated(i)) continue
            val r = rect(i / g.n, i % g.n, s, s * 0.2f)
            if (!g.opened[i]) {
                Art.draw(context, c, Art.DECO[(i * 7 + 3) % 4], r, 0x55A0815A, Art.DECO_LABEL[(i * 7 + 3) % 4])
            } else {
                val ct = g.content[i]
                when {
                    ct == Content.ORACLE -> Art.draw(context, c, Art.ORACLE, r, Art.GOLD, "☉", round = true)
                    ct.isPeril -> {
                        val cr = Art.creatureFor(ct, i)
                        if (cr != null && Art.bitmap(context, cr) != null) Art.draw(context, c, cr, rect(i / g.n, i % g.n, s, s * 0.04f), 0, "")
                        else Art.draw(context, c, Art.HOLE, r, 0x99402A1A.toInt(), "")
                    }
                    ct.isPowerUp -> Art.draw(context, c, Art.CONTAINER[i % 3], r, 0x99C9A227.toInt(), "")
                    else -> Art.draw(context, c, Art.CONTAINER[i % 3], r, 0x44A0815A, "")
                }
            }
        }
        // travellers: a pawn with a soft shadow, class colour and number (several on one square are offset a little)
        val seen = HashMap<Int, Int>()
        for (p in g.players) {
            val a = anim[p.id]
            val pr = a?.get(0) ?: p.r.toFloat(); val pc = a?.get(1) ?: p.c.toFloat()
            val i = g.idx(Math.round(pr), Math.round(pc))
            val k = if (a != null) 0 else seen.getOrDefault(i, 0).also { seen[i] = it + 1 }
            val off = k * s * 0.16f
            Art.pawn(c, RectF(pc * s + s * 0.08f + off, pr * s + s * 0.04f + off, pc * s + s * 0.92f + off, pr * s + s * 0.96f + off),
                Art.CLASS_COLOR[p.cls.ordinal], if (p.id == humanId) "★" else p.id.toString(),
                when { p.id == humanId -> Art.LAPIS; p.id == revealedMarco -> Art.MADDER; else -> 0 }, moving = a != null)
        }
        c.drawRect(1f, 1f, width - 1f, height - 1f, frame)
    }

    private fun rect(r: Int, c: Int, s: Float, inset: Float) = RectF(c * s + inset, r * s + inset, (c + 1) * s - inset, (r + 1) * s - inset)

    override fun onTouchEvent(e: MotionEvent): Boolean {
        val g = game ?: return false
        if (e.action == MotionEvent.ACTION_DOWN) downAt = e.eventTime
        if (e.action == MotionEvent.ACTION_UP) {
            val s = cell()
            val c = (e.x / s).toInt(); val r = (e.y / s).toInt()
            if (r in 0 until g.n && c in 0 until g.n) {
                if (e.eventTime - downAt > 450) onLongTap?.invoke(r, c) else onTap?.invoke(r, c)
            }
            performClick()
        }
        return true
    }

    override fun performClick(): Boolean { super.performClick(); return true }
}
