package com.metavitae.marcohunt

import android.app.Activity
import android.content.Context
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import com.google.ar.core.ArCoreApk
import com.google.ar.core.Config
import com.google.ar.core.Coordinates2d
import com.google.ar.core.Session
import com.google.ar.core.TrackingState
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.sqrt

/**
 * What the AR camera knows, shared with the drawing overlay (CameraBoardView).
 * World coordinates are ARCore's: metres, y up. The board is set by two objects at opposite corners.
 */
class ArState {
    @Volatile var tracking = false
    val vp = FloatArray(16)
    val invVp = FloatArray(16)
    @Volatile var camX = 0f; @Volatile var camY = 0f; @Volatile var camZ = 0f
    // the board
    @Volatile var ready = false
    var m1x = 0f; var m1z = 0f; var floorY = 0f
    var rAx = 1f; var rAz = 0f; var cAx = 0f; var cAz = 1f; var s = 1f
    private var pending1: FloatArray? = null

    /** How far the phone is from corner 1 while the second corner is awaited, in metres. */
    fun fromCorner1(): Float {
        val p1 = pending1 ?: return 0f
        return sqrt((camX - p1[0]) * (camX - p1[0]) + (camZ - p1[1]) * (camZ - p1[1]))
    }

    /**
     * Two-point setting: call at Marker 1 (square 1,1) then at Marker 2 (the far corner).
     * Returns 1 (corner 1 saved), 2 (done) or 0 (corner 2 refused: too close to corner 1; walk further and tap again).
     * The refusal exists because two quick taps on one spot used to make a board with 2 cm squares (founder's tests, Nov 7).
     */
    fun mark(n: Int, eye: Float): Int {
        val p1 = pending1
        if (p1 == null) { pending1 = floatArrayOf(camX, camZ, camY - eye); ready = false; return 1 }
        val vx = camX - p1[0]; val vz = camZ - p1[1]
        val len = sqrt(vx * vx + vz * vz)
        if (len < MIN_CORNERS_M) return 0
        val ux = vx / len; val uz = vz / len
        val k = 0.70710678f
        // rows and columns run at 45 degrees either side of the diagonal (same rule on every phone, so no mirror image)
        rAx = (ux - uz) * k; rAz = (ux + uz) * k
        cAx = (ux + uz) * k; cAz = (-ux + uz) * k
        s = len / ((n - 1).coerceAtLeast(1) * 1.41421356f)
        m1x = p1[0]; m1z = p1[1]; floorY = (p1[2] + camY - eye) / 2f
        pending1 = null; ready = true
        return 2
    }

    /** The square the phone stands over, or null. */
    fun squareHere(n: Int): IntArray? {
        if (!ready) return null
        val dx = camX - m1x; val dz = camZ - m1z
        val r = Math.round((dx * rAx + dz * rAz) / s); val c = Math.round((dx * cAx + dz * cAz) / s)
        return if (r in 0 until n && c in 0 until n) intArrayOf(r, c) else null
    }

    companion object {
        /** The two corners must be at least this far apart. */
        const val MIN_CORNERS_M = 2f

        /** True when Google AR is installed and supported on this phone. */
        fun available(ctx: Context): Boolean = try {
            // the first answer can be "still checking": ask again a few times (at most about half a second)
            var a = ArCoreApk.getInstance().checkAvailability(ctx)
            var tries = 0
            while (a.isTransient && tries < 4) { Thread.sleep(120); a = ArCoreApk.getInstance().checkAvailability(ctx); tries++ }
            a == ArCoreApk.Availability.SUPPORTED_INSTALLED
        } catch (e: Throwable) { false }
    }
}

/** The camera picture from Google AR, drawn with OpenGL; it also keeps ArState up to date. */
class ArView(private val act: Activity, val state: ArState) : GLSurfaceView(act), GLSurfaceView.Renderer {
    var session: Session? = null
    var onFrame: (() -> Unit)? = null
    private var tex = 0
    private var prog = 0
    private val quad: FloatBuffer = buf(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f))
    private val uv: FloatBuffer = buf(FloatArray(8))
    private var w = 1; private var h = 1
    private val view = FloatArray(16); private val proj = FloatArray(16)

    init {
        preserveEGLContextOnPause = true
        setEGLContextClientVersion(2)
        setRenderer(this)
        renderMode = RENDERMODE_CONTINUOUSLY
    }

    fun resumeAr() {
        if (session == null) {
            session = Session(act).also { s ->
                val cfg = Config(s)
                cfg.focusMode = Config.FocusMode.AUTO
                cfg.planeFindingMode = Config.PlaneFindingMode.HORIZONTAL
                cfg.updateMode = Config.UpdateMode.LATEST_CAMERA_IMAGE
                s.configure(cfg)
            }
        }
        session?.resume()
        onResume()
    }

    fun pauseAr() { onPause(); session?.pause() }
    fun closeAr() { session?.close(); session = null }

    override fun onSurfaceCreated(gl: GL10?, c: EGLConfig?) {
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        val t = IntArray(1); GLES20.glGenTextures(1, t, 0); tex = t[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, tex)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        prog = program(
            "attribute vec2 p; attribute vec2 t; varying vec2 v; void main(){ v=t; gl_Position=vec4(p,0.0,1.0); }",
            "#extension GL_OES_EGL_image_external : require\nprecision mediump float; varying vec2 v; uniform samplerExternalOES s; void main(){ gl_FragColor=texture2D(s,v); }")
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        w = width; h = height
        GLES20.glViewport(0, 0, width, height)
        session?.setDisplayGeometry(act.windowManager.defaultDisplay.rotation, width, height)
    }

    override fun onDrawFrame(gl: GL10?) {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        val s = session ?: return
        try {
            s.setCameraTextureName(tex)
            val frame = s.update()
            if (frame.hasDisplayGeometryChanged()) {
                quad.position(0); uv.position(0)
                frame.transformCoordinates2d(Coordinates2d.OPENGL_NORMALIZED_DEVICE_COORDINATES, quad, Coordinates2d.TEXTURE_NORMALIZED, uv)
            }
            if (frame.timestamp != 0L) drawBackground()
            val cam = frame.camera
            state.tracking = cam.trackingState == TrackingState.TRACKING
            if (state.tracking) {
                cam.getViewMatrix(view, 0)
                cam.getProjectionMatrix(proj, 0, 0.05f, 200f)
                synchronized(state) {
                    Matrix.multiplyMM(state.vp, 0, proj, 0, view, 0)
                    Matrix.invertM(state.invVp, 0, state.vp, 0)
                }
                val pose = cam.pose
                state.camX = pose.tx(); state.camY = pose.ty(); state.camZ = pose.tz()
            }
            onFrame?.invoke()
        } catch (e: Throwable) { /* camera not ready yet */ }
    }

    private fun drawBackground() {
        GLES20.glDisable(GLES20.GL_DEPTH_TEST)
        GLES20.glUseProgram(prog)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, tex)
        val pa = GLES20.glGetAttribLocation(prog, "p"); val ta = GLES20.glGetAttribLocation(prog, "t")
        quad.position(0); uv.position(0)
        GLES20.glVertexAttribPointer(pa, 2, GLES20.GL_FLOAT, false, 0, quad)
        GLES20.glVertexAttribPointer(ta, 2, GLES20.GL_FLOAT, false, 0, uv)
        GLES20.glEnableVertexAttribArray(pa); GLES20.glEnableVertexAttribArray(ta)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(pa); GLES20.glDisableVertexAttribArray(ta)
    }

    private fun program(vs: String, fs: String): Int {
        fun sh(type: Int, src: String) = GLES20.glCreateShader(type).also { GLES20.glShaderSource(it, src); GLES20.glCompileShader(it) }
        val p = GLES20.glCreateProgram()
        GLES20.glAttachShader(p, sh(GLES20.GL_VERTEX_SHADER, vs)); GLES20.glAttachShader(p, sh(GLES20.GL_FRAGMENT_SHADER, fs))
        GLES20.glLinkProgram(p); return p
    }

    private fun buf(a: FloatArray): FloatBuffer =
        ByteBuffer.allocateDirect(a.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply { put(a); position(0) }
}
