package com.metavitae.marcohunt

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.view.MotionEvent
import android.view.View
import com.metavitae.marcohunt.engine.Game
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tan

/**
 * Camera-lite board (M2, for phones without Google AR such as the POCO M6 Pro, and as a fallback):
 * the grid is drawn over the camera picture using only the phone's orientation sensors.
 * It knows which way the phone points, not where it moves, so the view is centred on the
 * player's square and re-centres every time the player taps "I'm here".
 */
class CameraBoardView(ctx: Context) : View(ctx), SensorEventListener {
    var game: Game? = null
    var humanId = 0
    var lit: Set<Int> = emptySet()
    var picked: Int = -1
    var revealedMarco = -1
    /** Tokens on the move (walk animation): player id -> (row, col). */
    val anim = HashMap<Int, FloatArray>()
    var onTap: ((Int, Int) -> Unit)? = null
    /** Square size in metres and eye height (walk mode). */
    var squareM = 1.0f
    var eyeM = 1.4f
    /** Board heading: compass direction (radians) in which the row number grows. */
    var heading = Float.NaN
    var tint = 0
    /** Google AR state (8T and other certified phones). Null = sensor-only camera-lite. */
    var ar: ArState? = null
    /** False when the screen around this view already says what to do (the game's guide line). */
    var hints = true
    /** Where the floating panels leave the picture free, so edge chips are never hidden: top, bottom, and top on the right side (the little map). */
    var insetTop = 0; var insetBottom = 0; var insetRightTop = 0
    private var curR = 0f; private var curC = 0f
    private val tmp = FloatArray(4); private val out = FloatArray(4)

    private val sm = ctx.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val rot = FloatArray(9).also { it[0] = 1f; it[4] = 1f; it[8] = 1f }
    private var haveRot = false
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF8A6A44.toInt(); strokeWidth = 3f; style = Paint.Style.STROKE }
    private val boardFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Art.PARCHMENT; style = Paint.Style.FILL }
    private val shade = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x99000000.toInt(); strokeWidth = 6f; style = Paint.Style.STROKE }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pillar = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeWidth = 10f; strokeCap = Paint.Cap.ROUND }
    private val glow = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeWidth = 26f; strokeCap = Paint.Cap.ROUND }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt(); textAlign = Paint.Align.CENTER; isFakeBoldText = true; textSize = 34f }
    private val hint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt(); textAlign = Paint.Align.CENTER; textSize = 40f; setShadowLayer(6f, 0f, 0f, 0xFF000000.toInt()) }

    fun start() {
        val s = sm.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
            ?: sm.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)
            ?: sm.getDefaultSensor(Sensor.TYPE_GEOMAGNETIC_ROTATION_VECTOR)
        if (s != null) sm.registerListener(this, s, SensorManager.SENSOR_DELAY_GAME)
    }
    fun stop() = sm.unregisterListener(this)

    override fun onSensorChanged(e: SensorEvent) {
        SensorManager.getRotationMatrixFromVector(rot, e.values)
        haveRot = true
        if (heading.isNaN()) heading = headingFacingBoard()
        invalidate()
    }
    override fun onAccuracyChanged(s: Sensor?, a: Int) {}

    /** Compass direction the camera looks at (radians from north, clockwise). */
    fun azimuth(): Float {
        // Where the player faces: the camera's direction (device -Z, column 2 of R, negated) plus the phone's top
        // edge (device +Y, column 1 of R). The camera alone fails when it looks at the ground near the feet: it then
        // points down and slightly BACK toward the player, which put the board behind him (founder, POCO, Nov 8).
        // The top edge still points away from him then; upright it points up and adds nothing.
        val ex = -rot[2] + rot[1]; val ny = -rot[5] + rot[4]
        return atan2(ex, ny)
    }

    /**
     * Sets the board in front of the player: rows and columns stay square to where he faces, turned by quarter
     * turns so that the bigger part of the board, seen from his own square, lies ahead of him.
     */
    fun setHeadingHere() { heading = headingFacingBoard(); invalidate() }

    private fun headingFacingBoard(): Float {
        val f = azimuth()
        val g = game ?: return f
        val me = g.players[humanId]
        val mid = (g.n - 1) / 2f
        val toCentre = atan2(mid - me.c, mid - me.r)
        val quarter = (Math.PI / 2).toFloat()
        return f - Math.round(toCentre / quarter) * quarter
    }

    // world: x east, y north, z up; origin = the player's eye above the centre of his square
    private fun world(dr: Float, dc: Float, up: Float): FloatArray {
        val a = ar
        if (a != null) {
            val r = curR + dr; val c = curC + dc
            return floatArrayOf(a.m1x + (r * a.rAx + c * a.cAx) * a.s, a.floorY + up, a.m1z + (r * a.rAz + c * a.cAz) * a.s)
        }
        val h = if (heading.isNaN()) 0f else heading
        val rx = sin(h); val ry = cos(h)        // row direction
        val cx = cos(h); val cy = -sin(h)       // column direction (to the right)
        return floatArrayOf((dr * rx + dc * cx) * squareM, (dr * ry + dc * cy) * squareM, up - eyeM)
    }

    /** Pixels per metre at 1 m depth (used for the size of icons and banners). */
    private fun focal(): Float = (width / 2f) / tan(Math.toRadians(32.0).toFloat())

    /** Screen point for a world point, or null if behind the camera. */
    private fun project(p: FloatArray): FloatArray? {
        val a = ar
        if (a != null) {
            tmp[0] = p[0]; tmp[1] = p[1]; tmp[2] = p[2]; tmp[3] = 1f
            synchronized(a) { android.opengl.Matrix.multiplyMV(out, 0, a.vp, 0, tmp, 0) }
            if (out[3] < 0.05f) return null
            return floatArrayOf((out[0] / out[3] + 1f) / 2f * width, (1f - out[1] / out[3]) / 2f * height, out[3])
        }
        // device = R^T * world
        val dx = rot[0] * p[0] + rot[3] * p[1] + rot[6] * p[2]
        val dy = rot[1] * p[0] + rot[4] * p[1] + rot[7] * p[2]
        val dz = rot[2] * p[0] + rot[5] * p[1] + rot[8] * p[2]
        val depth = -dz
        if (depth < 0.15f) return null
        val f = focal()
        return floatArrayOf(width / 2f + f * dx / depth, height / 2f - f * dy / depth, depth)
    }

    /** Fills the board's outline; where it runs behind the camera it is cut at the point the camera can still see. */
    private fun solidBoard(c: Canvas, corners: List<FloatArray>) {
        val pts = ArrayList<FloatArray>()
        fun at(a: FloatArray, b: FloatArray, t: Float) = floatArrayOf(a[0] + (b[0] - a[0]) * t, a[1] + (b[1] - a[1]) * t, a[2] + (b[2] - a[2]) * t)
        for (i in corners.indices) {
            val a = corners[i]; val b = corners[(i + 1) % corners.size]
            val pa = project(a); val pb = project(b)
            if (pa != null) pts.add(pa)
            if ((pa == null) != (pb == null)) {
                var seen = if (pa != null) 0f else 1f
                var hidden = 1f - seen
                repeat(12) { val m = (seen + hidden) / 2; if (project(at(a, b, m)) != null) seen = m else hidden = m }
                project(at(a, b, seen))?.let { pts.add(it) }
            }
        }
        if (pts.size < 3) return
        val path = Path(); path.moveTo(pts[0][0], pts[0][1])
        for (k in 1 until pts.size) path.lineTo(pts[k][0], pts[k][1])
        path.close()
        c.drawPath(path, boardFill)
    }

    private fun seg(c: Canvas, a: FloatArray, b: FloatArray, paint: Paint) {
        // draw in small pieces so lines that pass behind the camera are cut cleanly
        val n = 8
        var prev: FloatArray? = null
        for (k in 0..n) {
            val t = k / n.toFloat()
            val q = project(floatArrayOf(a[0] + (b[0] - a[0]) * t, a[1] + (b[1] - a[1]) * t, a[2] + (b[2] - a[2]) * t))
            if (q != null && prev != null) c.drawLine(prev[0], prev[1], q[0], q[1], paint)
            prev = q
        }
    }

    override fun onDraw(c: Canvas) {
        val g = game ?: return
        if (tint != 0) c.drawColor(tint)
        val a = ar
        if (a != null && !a.tracking) { if (hints) c.drawText(context.getString(R.string.ar_look), width / 2f, height / 2f, hint); return }
        if (a != null && !a.ready) { if (hints) c.drawText(context.getString(R.string.ar_mark1), width / 2f, height * 0.85f, hint); return }
        if (a == null && !haveRot) { c.drawText("…", width / 2f, height / 2f, hint); return }
        val me = g.players[humanId]
        val pr = me.r.toFloat(); val pc = me.c.toFloat()
        curR = pr; curC = pc
        // the whole board (founder, Nov 6: "I couldn't see the whole outdoors board")
        val r0 = 0; val r1 = g.n - 1
        val c0 = 0; val c1 = g.n - 1
        // the board itself is solid (founder, Nov 8: no need for it to be transparent); the world shows only around it
        solidBoard(c, listOf(
            world(r0 - pr - 0.5f, c0 - pc - 0.5f, 0f), world(r0 - pr - 0.5f, c1 - pc + 0.5f, 0f),
            world(r1 - pr + 0.5f, c1 - pc + 0.5f, 0f), world(r1 - pr + 0.5f, c0 - pc - 0.5f, 0f)))
        // lit squares (where the roll can take you) and the picked square
        for (i in lit + if (picked >= 0) setOf(picked) else emptySet()) {
            val r = i / g.n; val cc = i % g.n
            val corners = listOf(-0.5f to -0.5f, -0.5f to 0.5f, 0.5f to 0.5f, 0.5f to -0.5f)
                .map { project(world(r - pr + it.first, cc - pc + it.second, 0f)) }
            if (corners.any { it == null }) continue
            val path = Path(); path.moveTo(corners[0]!![0], corners[0]!![1]); for (k in 1..3) path.lineTo(corners[k]!![0], corners[k]!![1]); path.close()
            fill.color = if (i == picked) 0xFF5B7FD1.toInt() else 0xFFE3C46A.toInt()
            c.drawPath(path, fill)
        }
        // the grid on the ground
        for (r in r0..r1 + 1) {
            val a = world(r - pr - 0.5f, c0 - pc - 0.5f, 0f); val b = world(r - pr - 0.5f, c1 - pc + 0.5f, 0f)
            seg(c, a, b, line)
        }
        for (cc in c0..c1 + 1) {
            val a = world(r0 - pr - 0.5f, cc - pc - 0.5f, 0f); val b = world(r1 - pr + 0.5f, cc - pc - 0.5f, 0f)
            seg(c, a, b, line)
        }
        // tall light pillars at the corners and every 5 squares along the edges (grid corners, never on squares)
        val edgePts = ArrayList<Pair<Float, Float>>()
        for (k in 0..g.n step 5) { edgePts.add(k - 0.5f to -0.5f); edgePts.add(k - 0.5f to g.n - 0.5f); edgePts.add(-0.5f to k - 0.5f); edgePts.add(g.n - 0.5f to k - 0.5f) }
        edgePts.add(g.n - 0.5f to g.n - 0.5f)
        for ((er, ec) in edgePts) {
            val base = project(world(er - pr, ec - pc, 0f)) ?: continue
            val top = project(world(er - pr, ec - pc, 3f)) ?: continue
            glow.color = 0x55E3C46A; pillar.color = 0xFFFFF2C0.toInt()
            c.drawLine(base[0], base[1], top[0], top[1], glow); c.drawLine(base[0], base[1], top[0], top[1], pillar)
        }
        // Everything on the board stands perpendicular to the ground and faces this phone's player, never
        // side-on (founder, Nov 8). Each phone draws this for itself. Nearest pieces are drawn last.
        val pieces = ArrayList<Pair<Float, () -> Unit>>()
        fun piece(wr: Float, wc: Float, draw: (RectF) -> Unit) {
            val base = project(world(wr - pr, wc - pc, 0f)) ?: return
            val w = pieceWidth(base[2])
            if (base[0] < -w || base[0] > width + w || base[1] < -w || base[1] > height + 2 * w) return
            val top = project(world(wr - pr, wc - pc, w * base[2] / focal()))
            pieces.add(base[2] to { stand(c, base, top, w, draw) })
        }
        for ((k, lm) in g.landmarks.withIndex()) piece(lm[0].toFloat(), lm[1].toFloat()) { Art.draw(context, c, Art.LANDMARK[k], it, 0xDDE3C46A.toInt(), Art.LANDMARK_LABEL[k]) }
        for (m in g.marks) piece(m[0].toFloat(), m[1].toFloat()) { Art.draw(context, c, Art.MARK[m[2]], it, 0xCC7FB8A6.toInt(), Art.MARK_LABEL[m[2]], round = true) }
        // decorations (all alike until someone lands on them) and opened squares
        for (r in r0..r1) for (cc in c0..c1) {
            val i = g.idx(r, cc)
            if (!g.decorated(i)) continue
            piece(r.toFloat(), cc.toFloat()) { rect ->
                if (!g.opened[i]) Art.draw(context, c, Art.DECO[(i * 7 + 3) % 4], rect, 0xCCA0815A.toInt(), Art.DECO_LABEL[(i * 7 + 3) % 4])
                else {
                    val ct = g.content[i]
                    val cr = if (ct.isPeril) Art.creatureFor(ct, i) else null
                    when {
                        ct == com.metavitae.marcohunt.engine.Types.Content.ORACLE -> Art.draw(context, c, Art.ORACLE, rect, Art.GOLD, "☉", round = true)
                        cr != null && Art.bitmap(context, cr) != null -> Art.draw(context, c, cr, rect, 0, "")
                        else -> Art.draw(context, c, if (ct.isPeril) Art.HOLE else Art.CONTAINER[i % 3], rect, if (ct.isPeril) 0xCC402A1A.toInt() else 0xCCC9A227.toInt(), "")
                    }
                }
            }
        }
        // travellers: a standing piece on the square, and a pole with a banner so they can be spotted from far away.
        // Founder, Nov 7: "the poles became invisible at a distance" -> poles and flags never get smaller
        // than a thumb's width, and travellers outside the picture show as a chip at the edge, pointing to them.
        for (p in g.players) {
            if (p.id == humanId) continue
            val a = anim[p.id]
            val tr = (a?.get(0) ?: p.r.toFloat()) - pr; val tc = (a?.get(1) ?: p.c.toFloat()) - pc
            val base = project(world(tr, tc, 0f))
            val top = project(world(tr, tc, 2.6f))
            val colour = Art.CLASS_COLOR[p.cls.ordinal]
            fun inView(q: FloatArray?) = q != null && q[0] in 0f..width.toFloat() && q[1] in 0f..height.toFloat()
            if (!inView(base) && !inView(top)) { edgeChip(c, p.id, colour, top ?: base); continue }
            val depth = base?.get(2) ?: top!![2]
            pieces.add(depth to {
                if (base != null && top != null) {
                    pole.strokeWidth = (focal() * 0.06f / base[2]).coerceIn(dp(5f), 40f)
                    poleEdge.strokeWidth = pole.strokeWidth + dp(3f)
                    c.drawLine(base[0], base[1], top[0], top[1], poleEdge); c.drawLine(base[0], base[1], top[0], top[1], pole)
                }
                if (top != null) {
                    val flagFoot = project(world(tr, tc, 2.1f))
                    if (flagFoot != null) stand(c, flagFoot, top, (focal() * 0.6f / top[2]).coerceIn(dp(46f), dp(200f))) { rect ->
                        Art.pennant(c, RectF(rect.centerX(), rect.top, rect.right + rect.width() * 0.5f, rect.bottom), colour, "${p.id}")
                    }
                    if (p.id == revealedMarco) c.drawText("☀", top[0], top[1] - 10f, hint)
                }
                if (base != null) {
                    val w = pieceWidth(base[2])
                    stand(c, base, project(world(tr, tc, w * 1.3f * base[2] / focal())), w) { Art.pawn(c, it, colour, "${p.id}", 0) }
                }
            })
        }
        pieces.sortByDescending { it.first }
        for (d in pieces) d.second()
    }

    /** How wide a standing piece looks at this depth: about a square, never too small to see or big enough to fill the screen. */
    private fun pieceWidth(depth: Float) = (focal() * (squareM * 0.85f).coerceIn(0.25f, 1f) / depth).coerceIn(dp(24f), dp(260f))

    private val standMatrix = android.graphics.Matrix()

    /**
     * Draws a piece upright: its foot on the ground point, its height along the true vertical of the world as the
     * camera sees it (so tilting the phone doesn't tilt the piece), its face square to the viewer (so never side-on).
     * Seen from above the height would shrink to nothing, so it keeps at least 70% of its width and stays readable.
     */
    private fun stand(c: Canvas, foot: FloatArray, top: FloatArray?, w: Float, draw: (RectF) -> Unit) {
        var ux = 0f; var uy = -1f; var len = w
        if (top != null) {
            val dx = top[0] - foot[0]; val dy = top[1] - foot[1]
            val l = Math.hypot(dx.toDouble(), dy.toDouble()).toFloat()
            if (l >= 1f) { ux = dx / l; uy = dy / l; len = l }
        }
        len = len.coerceAtLeast(w * 0.7f)
        val rx = -uy; val ry = ux // to the piece's right, seen by the viewer
        val hx = rx * w / 2; val hy = ry * w / 2
        val src = floatArrayOf(0f, 0f, w, 0f, w, len, 0f, len)
        val dst = floatArrayOf(
            foot[0] - hx + ux * len, foot[1] - hy + uy * len, foot[0] + hx + ux * len, foot[1] + hy + uy * len,
            foot[0] + hx, foot[1] + hy, foot[0] - hx, foot[1] - hy)
        standMatrix.setPolyToPoly(src, 0, dst, 0, 4)
        c.save(); c.concat(standMatrix)
        draw(RectF(0f, 0f, w, len))
        c.restore()
    }

    private fun dp(v: Float) = v * resources.displayMetrics.density
    private val pole = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFF2C0.toInt(); strokeCap = Paint.Cap.ROUND }
    private val poleEdge = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF3A2412.toInt(); strokeCap = Paint.Cap.ROUND }
    private val glowFill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val chipText = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF3A2412.toInt(); textAlign = Paint.Align.CENTER; isFakeBoldText = true }

    /** A traveller outside the picture: a chip on the nearest edge with an arrow (or "behind you"). */
    private fun edgeChip(c: Canvas, id: Int, colour: Int, at: FloatArray?) {
        val m = dp(30f)
        val cx: Float; val cy: Float; val arrow: String
        if (at == null) { cx = width / 2f + (id - 2) * dp(64f); cy = height - maxOf(dp(96f), insetBottom + dp(22f)); arrow = "↓" }
        else {
            cx = at[0].coerceIn(m, width - m)
            val minY = maxOf(m + dp(56f), (if (cx > width - dp(160f)) insetRightTop else insetTop) + dp(22f))
            val maxY = height - maxOf(dp(96f), insetBottom + dp(22f))
            cy = at[1].coerceIn(minY, maxY)
            arrow = when { at[0] < m -> "←"; at[0] > width - m -> "→"; at[1] < minY -> "↑"; else -> "↓" }
        }
        glowFill.color = colour
        val r = RectF(cx - dp(30f), cy - dp(18f), cx + dp(30f), cy + dp(18f))
        c.drawRoundRect(r, dp(18f), dp(18f), glowFill)
        poleEdge.strokeWidth = dp(2f); poleEdge.style = Paint.Style.STROKE
        c.drawRoundRect(r, dp(18f), dp(18f), poleEdge); poleEdge.style = Paint.Style.FILL_AND_STROKE
        chipText.textSize = dp(17f)
        c.drawText("$arrow $id", cx, cy - (chipText.descent() + chipText.ascent()) / 2, chipText)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        val g = game ?: return false
        if (e.action != MotionEvent.ACTION_UP) return true
        val a = ar
        if (a != null) {
            if (!a.ready || !a.tracking) return true
            // unproject the tap into a ray and meet the floor
            val nx = e.x / width * 2f - 1f; val ny = 1f - e.y / height * 2f
            val near = FloatArray(4); val far = FloatArray(4)
            synchronized(a) {
                android.opengl.Matrix.multiplyMV(near, 0, a.invVp, 0, floatArrayOf(nx, ny, -1f, 1f), 0)
                android.opengl.Matrix.multiplyMV(far, 0, a.invVp, 0, floatArrayOf(nx, ny, 1f, 1f), 0)
            }
            for (k in 0..2) { near[k] /= near[3]; far[k] /= far[3] }
            val dy = far[1] - near[1]
            if (kotlin.math.abs(dy) < 1e-4f) return true
            val t = (a.floorY - near[1]) / dy
            if (t < 0) return true
            val hx = near[0] + (far[0] - near[0]) * t - a.m1x; val hz = near[2] + (far[2] - near[2]) * t - a.m1z
            val r = Math.round((hx * a.rAx + hz * a.rAz) / a.s); val c = Math.round((hx * a.cAx + hz * a.cAz) / a.s)
            if (r in 0 until g.n && c in 0 until g.n) onTap?.invoke(r, c)
            performClick(); return true
        }
        if (!haveRot) return true
        // ray from the eye through the tapped point, down to the ground
        val f = focal()
        val dx = (e.x - width / 2f) / f; val dy = -(e.y - height / 2f) / f; val dz = -1f
        val wx = rot[0] * dx + rot[1] * dy + rot[2] * dz
        val wy = rot[3] * dx + rot[4] * dy + rot[5] * dz
        val wz = rot[6] * dx + rot[7] * dy + rot[8] * dz
        if (wz >= -0.02f) return true
        val t = -eyeM / wz
        val hx = wx * t; val hy = wy * t
        val h = if (heading.isNaN()) 0f else heading
        val dr = (hx * sin(h) + hy * cos(h)) / squareM
        val dc = (hx * cos(h) - hy * sin(h)) / squareM
        val me = g.players[humanId]
        val r = Math.round(me.r + dr); val c = Math.round(me.c + dc)
        if (r in 0 until g.n && c in 0 until g.n) onTap?.invoke(r, c)
        performClick()
        return true
    }

    override fun performClick(): Boolean { super.performClick(); return true }
}
