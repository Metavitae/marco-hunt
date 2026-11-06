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
    var onTap: ((Int, Int) -> Unit)? = null
    /** Square size in metres and eye height (walk mode). */
    var squareM = 1.0f
    var eyeM = 1.4f
    /** Board heading: compass direction (radians) in which the row number grows. */
    var heading = Float.NaN
    var tint = 0
    /** Google AR state (8T and other certified phones). Null = sensor-only camera-lite. */
    var ar: ArState? = null
    private var curR = 0f; private var curC = 0f
    private val tmp = FloatArray(4); private val out = FloatArray(4)

    private val sm = ctx.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val rot = FloatArray(9).also { it[0] = 1f; it[4] = 1f; it[8] = 1f }
    private var haveRot = false
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFF1E4C8.toInt(); strokeWidth = 3f; style = Paint.Style.STROKE }
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
        if (heading.isNaN()) heading = azimuth()
        invalidate()
    }
    override fun onAccuracyChanged(s: Sensor?, a: Int) {}

    /** Compass direction the camera looks at (radians from north, clockwise). */
    fun azimuth(): Float {
        // camera looks along device -Z; in world coordinates that is -(column 2 of R)
        val ex = -rot[2]; val ny = -rot[5]
        return atan2(ex, ny)
    }

    /** Sets the board so that the rows grow in the direction the camera faces now. */
    fun setHeadingHere() { heading = azimuth(); invalidate() }

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
        if (a != null && !a.tracking) { c.drawText(context.getString(R.string.ar_look), width / 2f, height / 2f, hint); return }
        if (a != null && !a.ready) { c.drawText(context.getString(R.string.ar_mark1), width / 2f, height * 0.85f, hint); return }
        if (a == null && !haveRot) { c.drawText("…", width / 2f, height / 2f, hint); return }
        val me = g.players[humanId]
        val pr = me.r.toFloat(); val pc = me.c.toFloat()
        curR = pr; curC = pc
        // the whole board (founder, Nov 6: "I couldn't see the whole outdoors board")
        val r0 = 0; val r1 = g.n - 1
        val c0 = 0; val c1 = g.n - 1
        // lit squares (where the roll can take you) and the picked square
        for (i in lit + if (picked >= 0) setOf(picked) else emptySet()) {
            val r = i / g.n; val cc = i % g.n
            val corners = listOf(-0.5f to -0.5f, -0.5f to 0.5f, 0.5f to 0.5f, 0.5f to -0.5f)
                .map { project(world(r - pr + it.first, cc - pc + it.second, 0f)) }
            if (corners.any { it == null }) continue
            val path = Path(); path.moveTo(corners[0]!![0], corners[0]!![1]); for (k in 1..3) path.lineTo(corners[k]!![0], corners[k]!![1]); path.close()
            fill.color = if (i == picked) 0x885B7FD1.toInt() else 0x66E3C46A
            c.drawPath(path, fill)
        }
        // the grid on the ground
        for (r in r0..r1 + 1) {
            val a = world(r - pr - 0.5f, c0 - pc - 0.5f, 0f); val b = world(r - pr - 0.5f, c1 - pc + 0.5f, 0f)
            seg(c, a, b, shade); seg(c, a, b, line)
        }
        for (cc in c0..c1 + 1) {
            val a = world(r0 - pr - 0.5f, cc - pc - 0.5f, 0f); val b = world(r1 - pr + 0.5f, cc - pc - 0.5f, 0f)
            seg(c, a, b, shade); seg(c, a, b, line)
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
        // decorations (all alike) and opened squares
        for (r in r0..r1) for (cc in c0..c1) {
            val i = g.idx(r, cc)
            if (!g.decorated(i)) continue
            val p = project(world(r - pr, cc - pc, 0.05f)) ?: continue
            val size = (focal() * 0.35f * squareM / p[2]).coerceIn(10f, 220f)
            val rect = RectF(p[0] - size / 2, p[1] - size, p[0] + size / 2, p[1])
            if (!g.opened[i]) Art.draw(context, c, Art.DECO[(i * 7 + 3) % 4], rect, 0xCCA0815A.toInt(), "✦")
            else {
                val ct = g.content[i]
                val cr = if (ct.isPeril) Art.creatureFor(ct, i) else null
                if (cr != null && Art.bitmap(context, cr) != null) Art.draw(context, c, cr, rect, 0, "")
                else Art.draw(context, c, if (ct.isPeril) Art.HOLE else Art.CONTAINER[i % 3], rect, if (ct.isPeril) 0xCC402A1A.toInt() else 0xCCC9A227.toInt(), "")
            }
        }
        // travellers: a class banner on a pole over each square (Marco's looks like anyone's)
        for (p in g.players) {
            if (p.id == humanId) continue
            val base = project(world(p.r - pr, p.c - pc, 0f)) ?: continue
            val top = project(world(p.r - pr, p.c - pc, 2.2f)) ?: continue
            pillar.color = 0xFF5A3A1E.toInt(); c.drawLine(base[0], base[1], top[0], top[1], pillar)
            val w = (focal() * 0.5f / top[2]).coerceIn(24f, 260f)
            val flag = RectF(top[0], top[1], top[0] + w, top[1] + w * 0.6f)
            Art.draw(context, c, "banner_" + Art.CLASS[p.cls.ordinal].removePrefix("class_"), flag, Art.CLASS_COLOR[p.cls.ordinal], "${p.id}")
            if (p.id == revealedMarco) c.drawText("☀", top[0], top[1] - 10f, hint)
        }
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
