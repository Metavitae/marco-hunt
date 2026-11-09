package com.metavitae.marcohunt

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import com.metavitae.marcohunt.engine.Spiral

/**
 * The flat spiral board (founder, Nov 9): one road from Venice in the corner to the Khan in the middle.
 * Each land has its own colour; walls keep the rings apart, so the road reads as a spiral; red bars are the passes.
 */
class SpiralBoardView(ctx: Context) : View(ctx) {
    var game: Spiral? = null
    var reach: Spiral.Reach? = null
    val anim = HashMap<Int, FloatArray>()
    var onTap: ((Int) -> Unit)? = null
    var onLongTap: ((Int) -> Unit)? = null
    private var downAt = 0L

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val wall = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Art.INK; style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val passBar = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Art.MADDER; style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val chevron = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Art.INK; alpha = 70; style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    private val mark = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val strong = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; isFakeBoldText = true; color = Art.MADDER }
    private val rect = RectF()

    override fun onMeasure(w: Int, h: Int) {
        val hs = MeasureSpec.getSize(h)
        val size = if (hs > 0) minOf(MeasureSpec.getSize(w), hs) else MeasureSpec.getSize(w)
        setMeasuredDimension(size, size)
    }

    private fun cell() = width / Spiral.SIDE.toFloat()
    private fun cx(i: Int, s: Float) = (Spiral.COL[i] + 0.5f) * s
    private fun cy(i: Int, s: Float) = (Spiral.ROW[i] + 0.5f) * s

    private fun emoji(c: Canvas, text: String, x: Float, y: Float, size: Float, alpha: Int = 255) {
        mark.textSize = size; mark.alpha = alpha
        c.drawText(text, x, y - (mark.descent() + mark.ascent()) / 2, mark)
        mark.alpha = 255
    }

    override fun onDraw(c: Canvas) {
        val g = game ?: return
        val s = cell()
        val r = reach
        val you = g.ps[0]
        wall.strokeWidth = s * 0.07f
        passBar.strokeWidth = s * 0.17f
        chevron.strokeWidth = s * 0.045f
        for (i in 0 until Spiral.N) {
            val col = Spiral.COL[i]; val row = Spiral.ROW[i]
            rect.set(col * s, row * s, (col + 1) * s, (row + 1) * s)
            fill.color = LAND[Spiral.RING[i]]
            c.drawRect(rect, fill)
            if (r != null && r.safe[i]) { fill.color = 0xD9E8C04A.toInt(); c.drawRect(rect, fill) }
            if (r != null && r.risky[i]) { fill.color = 0xB3C8554E.toInt(); c.drawRect(rect, fill) }
        }
        // the road's direction: inward, or homeward once you carry the treasure
        val dir = if (you.chest) -1 else 1
        var i = 3
        while (i < Spiral.N - 2) {
            val nx = i + dir
            val dx = (Spiral.COL[nx] - Spiral.COL[i]).toFloat(); val dy = (Spiral.ROW[nx] - Spiral.ROW[i]).toFloat()
            val x = cx(i, s); val y = cy(i, s); val a = s * 0.13f
            c.drawLine(x - dx * a - dy * a, y - dy * a + dx * a, x + dx * a * 0.4f, y + dy * a * 0.4f, chevron)
            c.drawLine(x - dx * a + dy * a, y - dy * a - dx * a, x + dx * a * 0.4f, y + dy * a * 0.4f, chevron)
            i += 4
        }
        // walls between squares that the road doesn't join
        for (a in 0 until Spiral.N) {
            for (d in 0..1) {
                val row = Spiral.ROW[a] + d; val col = Spiral.COL[a] + (1 - d)
                if (row >= Spiral.SIDE || col >= Spiral.SIDE) continue
                val b = Spiral.AT[row][col]
                if (kotlin.math.abs(a - b) == 1 || g.isPass[a][b]) continue
                drawEdge(c, a, b, s, 0.5f, wall)
            }
        }
        rect.set(s * 0.02f, s * 0.02f, width - s * 0.02f, height - s * 0.02f)
        c.drawRect(rect, wall)
        for (e in g.passes) drawEdge(c, e[0], e[1], s, 0.3f, passBar)
        // what lies on the road
        for (a in 0 until Spiral.N) {
            val x = cx(a, s); val y = cy(a, s)
            if (g.gift[a]) emoji(c, "🎁", x, y, s * 0.56f, if (g.mayTakeGift(you, a)) 255 else 95)
            if (g.tablet[a]) emoji(c, "🥇", x, y, s * 0.56f)
            if (g.horse[a]) emoji(c, "🐎", x, y, s * 0.56f)
            if (r != null && r.risky[a]) {
                strong.textSize = s * 0.38f
                c.drawText("!", col0(a, s) + s * 0.82f, row0(a, s) + s * 0.34f, strong)
            }
        }
        emoji(c, "⚓", cx(Spiral.VENICE, s), cy(Spiral.VENICE, s), s * 0.62f)
        emoji(c, "👑", cx(Spiral.KHAN, s), cy(Spiral.KHAN, s), s * 0.72f)
        // travellers (several on one square sit a little apart)
        val seen = HashMap<Int, Int>()
        for (p in g.ps) {
            val a = anim[p.id]
            val row = a?.get(0) ?: Spiral.ROW[p.at].toFloat(); val col = a?.get(1) ?: Spiral.COL[p.at].toFloat()
            val k = if (a != null) 0 else seen.getOrDefault(p.at, 0).also { seen[p.at] = it + 1 }
            val off = k * s * 0.15f
            rect.set(col * s + s * 0.14f + off, row * s + s * 0.04f, col * s + s * 0.86f + off, row * s + s * 0.92f)
            Art.pawn(c, rect, COLOR[p.id], if (p.id == 0) "★" else p.id.toString(), if (p.id == 0) Art.LAPIS else 0, moving = a != null)
            if (p.chest) emoji(c, "💰", rect.right - s * 0.08f, rect.top + s * 0.14f, s * 0.42f)
        }
    }

    private fun col0(i: Int, s: Float) = Spiral.COL[i] * s
    private fun row0(i: Int, s: Float) = Spiral.ROW[i] * s

    /** A line along the side two touching squares share. half = half its length, in squares. */
    private fun drawEdge(c: Canvas, a: Int, b: Int, s: Float, half: Float, p: Paint) {
        val mx = (cx(a, s) + cx(b, s)) / 2; val my = (cy(a, s) + cy(b, s)) / 2
        if (Spiral.ROW[a] == Spiral.ROW[b]) c.drawLine(mx, my - s * half, mx, my + s * half, p)
        else c.drawLine(mx - s * half, my, mx + s * half, my, p)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (e.action == MotionEvent.ACTION_DOWN) downAt = e.eventTime
        if (e.action == MotionEvent.ACTION_UP) {
            val s = cell()
            val col = (e.x / s).toInt(); val row = (e.y / s).toInt()
            if (row in 0 until Spiral.SIDE && col in 0 until Spiral.SIDE) {
                val i = Spiral.AT[row][col]
                if (e.eventTime - downAt > 450) onLongTap?.invoke(i) else onTap?.invoke(i)
            }
            performClick()
        }
        return true
    }

    override fun performClick(): Boolean { super.performClick(); return true }

    companion object {
        /** Holy Land, Persia, Pamir, Lop desert, Cathay. */
        val LAND = intArrayOf(0xFFC6DDD0.toInt(), 0xFFEBD3A6.toInt(), 0xFFDBE4EE.toInt(), 0xFFE3BE86.toInt(), 0xFFEBC6BC.toInt())
        val COLOR = intArrayOf(0xFFFBF3DF.toInt(), 0xFFB0B7C3.toInt(), 0xFF8E7CC3.toInt(), 0xFF7FB27A.toInt())
    }
}
