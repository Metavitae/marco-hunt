package com.metavitae.marcohunt

import android.content.ContentValues
import android.content.Context
import android.graphics.Typeface
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.metavitae.marcohunt.engine.Game
import com.metavitae.marcohunt.engine.Rules
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.sqrt

/**
 * Marco Hunt's test lab, in the app's own look (the LightComm way): a short plan of tests run inside the real app,
 * each with a countdown and an end beep, every result measured and saved as a file on the phone
 * (Download/MarcoHunt/tests), plus a SUMMARY with pass marks. CC reads the files afterwards.
 * adb: am start -n com.metavitae.marcohunt/.TestActivity --es test facts   (runs the automatic test and closes)
 */
class TestActivity : androidx.activity.ComponentActivity(), SensorEventListener {

    override fun attachBaseContext(base: Context) = super.attachBaseContext(Lang.wrap(base))

    private val ui = Handler(Looper.getMainLooper())
    private val tone by lazy { try { ToneGenerator(AudioManager.STREAM_MUSIC, 90) } catch (e: Exception) { null } }
    private val stamp = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date())
    private val summary = ArrayList<String>()
    private lateinit var root: LinearLayout
    private var arState: ArState? = null
    private var arView: ArView? = null
    private var camView: CameraBoardView? = null
    private var lux = -1f
    private lateinit var game: Game

    override fun onCreate(saved: Bundle?) {
        super.onCreate(saved)
        val r = Rules(); r.n = 10; r.players = 2; r.teams = false
        game = Game(r, 1)
        root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Art.PARCHMENT); setPadding(dp(16), dp(20), dp(16), dp(24)) }
        setContentView(ScrollView(this).apply { setBackgroundColor(Art.PARCHMENT); addView(root) })
        (getSystemService(SENSOR_SERVICE) as SensorManager).getDefaultSensor(Sensor.TYPE_LIGHT)?.let {
            (getSystemService(SENSOR_SERVICE) as SensorManager).registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL)
        }
        if (intent.getStringExtra("test") == "facts") { facts(auto = true); return }
        menu()
    }

    override fun onSensorChanged(e: SensorEvent) { lux = e.values[0] }
    override fun onAccuracyChanged(s: Sensor?, a: Int) {}

    // ------------------------------------------------------------------ look (the app's own UI kit)

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun card(fill: Int, stroke: Int = Art.INK, w: Int = 1) =
        android.graphics.drawable.GradientDrawable().apply { setColor(fill); cornerRadius = dp(12).toFloat(); setStroke(dp(w), stroke) }
    private fun title(t: String) = TextView(this).apply { text = t; textSize = 28f; setTextColor(Art.INK); typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD); gravity = Gravity.CENTER }
    private fun big(t: String) = TextView(this).apply { text = t; textSize = 24f; setTextColor(Art.INK); typeface = Typeface.SERIF; gravity = Gravity.CENTER; setPadding(0, dp(8), 0, dp(8)) }
    private fun small(t: String) = TextView(this).apply { text = t; textSize = 14f; setTextColor(0xFF8A6A44.toInt()); typeface = Typeface.SERIF }
    private fun button(t: String, onClick: () -> Unit) = Button(this).apply {
        text = t; isAllCaps = false; textSize = 20f; setTextColor(Art.INK); typeface = Typeface.SERIF
        setBackgroundResource(R.drawable.btn_parchment); setPadding(dp(12), dp(16), dp(12), dp(16))
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(12) }
        setOnClickListener { onClick() }
    }
    private fun gap(h: Int) = View(this).apply { layoutParams = LinearLayout.LayoutParams(1, dp(h)) }

    // ------------------------------------------------------------------ the plan

    private data class T(val id: String, val name: String, val what: String, val needsAr: Boolean, val run: () -> Unit)

    private val plan by lazy { listOf(
        T("facts", "F · Phone facts", "Automatic. What this phone has: Google AR, sensors, camera.", false) { facts(false) },
        T("still", "S1 · Stand still", "30 seconds standing still. Does the board stay put?", false) { stillTest() },
        T("walk", "S2 · Two objects and back", "Set the board at two objects, walk back to the first. How far did it move?", true) { walkTest() },
        T("here", "S3 · I'm here", "Walk to 3 squares. Does the phone know where you are?", true) { hereTest(1) },
        T("light", "S4 · Sun and shade", "Can you see the lines and pillars? The light sensor measures the light.", false) { lightTest() },
    ) }

    private fun menu() {
        stopCamera()
        root.removeAllViews()
        root.addView(title(getString(R.string.app_name) + " · Tests"))
        root.addView(small("Results are saved on this phone in Download/MarcoHunt/tests. Each test starts with 3-2-1 and a beep and ends with two beeps.").apply { gravity = Gravity.CENTER })
        root.addView(gap(8))
        val ar = ArState.available(this)
        for (t in plan) {
            val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; background = card(0xFFFBF3DF.toInt()); setPadding(dp(16), dp(14), dp(16), dp(14)) }
            box.addView(TextView(this).apply { text = t.name; textSize = 20f; setTextColor(Art.INK); typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD) })
            box.addView(small(t.what + if (t.needsAr && !ar) "  (needs Google AR: the 8T)" else ""))
            val b = button("Start") { t.run() }
            b.isEnabled = !t.needsAr || ar
            box.addView(b)
            root.addView(box, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(14) })
        }
        root.addView(button("Write SUMMARY") { writeSummary(); root.addView(big("SUMMARY saved.")) })
    }

    // ------------------------------------------------------------------ files

    private fun save(name: String, text: String) {
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                val v = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, "${stamp}_$name")
                    put(MediaStore.MediaColumns.MIME_TYPE, if (name.endsWith(".csv")) "text/csv" else "text/plain")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, "Download/MarcoHunt/tests")
                }
                val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v) ?: return
                contentResolver.openOutputStream(uri)?.use { it.write(text.toByteArray()) }
            }
        } catch (e: Exception) { }
    }

    private fun result(line: String) { summary.add(line) }

    private fun writeSummary() {
        val head = "Marco Hunt tests ${stamp} · ${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE} · app ${packageManager.getPackageInfo(packageName, 0).versionName}\n"
        save("SUMMARY.txt", head + summary.joinToString("\n") + "\n")
    }

    // ------------------------------------------------------------------ countdown and beeps

    private fun countdown(then: () -> Unit) {
        val t = big("3").apply { textSize = 96f }
        root.addView(t, 0)
        var n = 3
        fun step() {
            if (n == 0) { root.removeView(t); tone?.startTone(ToneGenerator.TONE_PROP_BEEP2, 400); then(); return }
            t.text = n.toString(); tone?.startTone(ToneGenerator.TONE_PROP_BEEP, 150); n--
            ui.postDelayed({ step() }, 1000)
        }
        step()
    }

    private fun endBeep() {
        tone?.startTone(ToneGenerator.TONE_PROP_ACK, 300)
        ui.postDelayed({ tone?.startTone(ToneGenerator.TONE_PROP_ACK, 300) }, 450)
    }

    // ------------------------------------------------------------------ camera stage (the same board drawing as the game)

    private fun startCamera(): FrameLayout {
        stopCamera()
        val h = (resources.displayMetrics.heightPixels * 0.55f).toInt()
        val stage = FrameLayout(this).apply { background = card(Art.PARCHMENT, Art.INK, 2) }
        val cv = CameraBoardView(this).apply { game = this@TestActivity.game; humanId = 0 }
        if (ArState.available(this)) {
            val st = ArState(); val av = ArView(this, st)
            cv.ar = st; av.onFrame = { cv.postInvalidate() }
            stage.addView(av, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, h))
            stage.addView(cv, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, h))
            arState = st; arView = av
            try { av.resumeAr() } catch (e: Throwable) { }
        } else {
            val pv = androidx.camera.view.PreviewView(this)
            stage.addView(pv, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, h))
            stage.addView(cv, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, h))
            val f = androidx.camera.lifecycle.ProcessCameraProvider.getInstance(this)
            f.addListener({
                try {
                    val p = androidx.camera.core.Preview.Builder().build().also { it.setSurfaceProvider(pv.surfaceProvider) }
                    f.get().unbindAll(); f.get().bindToLifecycle(this, androidx.camera.core.CameraSelector.DEFAULT_BACK_CAMERA, p)
                } catch (e: Exception) { }
            }, mainExecutor)
            cv.start()
        }
        camView = cv
        return stage
    }

    private fun stopCamera() {
        camView?.stop(); arView?.pauseAr(); arView?.closeAr()
        camView = null; arView = null; arState = null
    }

    private fun screen(name: String, steps: String, stage: View?): LinearLayout {
        root.removeAllViews()
        root.addView(TextView(this).apply { text = name; textSize = 22f; setTextColor(Art.INK); typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD) })
        if (stage != null) root.addView(stage)
        root.addView(big(steps))
        val actions = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(actions)
        root.addView(button("Back to the tests") { menu() })
        return actions
    }

    // ------------------------------------------------------------------ F · facts (automatic)

    private fun facts(auto: Boolean) {
        val sm = getSystemService(SENSOR_SERVICE) as SensorManager
        fun has(t: Int) = if (sm.getDefaultSensor(t) != null) "yes" else "no"
        val ar = ArState.available(this)
        var arRaw = "?"
        try { arRaw = com.google.ar.core.ArCoreApk.getInstance().checkAvailability(this).name } catch (e: Throwable) { arRaw = "error" }
        val camOk = checkSelfPermission(android.Manifest.permission.CAMERA) == android.content.pm.PackageManager.PERMISSION_GRANTED
        val rows = listOf(
            "phone" to "${Build.MANUFACTURER} ${Build.MODEL}", "android" to Build.VERSION.RELEASE,
            "app" to packageManager.getPackageInfo(packageName, 0).versionName,
            "google_ar" to (if (ar) "yes" else "no"), "google_ar_detail" to arRaw,
            "board_mode" to (if (ar) "real AR" else "camera-lite"),
            "gyroscope" to has(Sensor.TYPE_GYROSCOPE), "rotation_vector" to has(Sensor.TYPE_ROTATION_VECTOR),
            "game_rotation_vector" to has(Sensor.TYPE_GAME_ROTATION_VECTOR), "compass" to has(Sensor.TYPE_MAGNETIC_FIELD),
            "light_sensor" to has(Sensor.TYPE_LIGHT), "camera_permission" to (if (camOk) "given" else "not given"),
            "screen" to "${resources.displayMetrics.widthPixels}x${resources.displayMetrics.heightPixels}",
        )
        save("F_facts.csv", "fact,value\n" + rows.joinToString("\n") { "${it.first},${it.second}" } + "\n")
        result("F facts: board mode ${if (ar) "real AR" else "camera-lite"}, gyroscope ${has(Sensor.TYPE_GYROSCOPE)}, camera ${if (camOk) "given" else "not given"} · DONE")
        writeSummary()
        if (auto) { finish(); return }
        val a = screen("F · Phone facts", rows.joinToString("\n") { "${it.first}: ${it.second}" }, null)
        endBeep()
    }

    // ------------------------------------------------------------------ S1 · stand still 30 s

    private fun stillTest() {
        val stage = startCamera()
        val a = screen("S1 · Stand still", "1 Point the phone at the ground.\n2 Tap Go and stand still.\n3 Wait for two beeps.", stage)
        a.addView(button("Go") {
            countdown {
                val rows = StringBuilder("t_s,tracking,cam_x_m,cam_z_m,heading_deg\n")
                val st = arState; val cv = camView
                val x0 = st?.camX ?: 0f; val z0 = st?.camZ ?: 0f
                val h0 = cv?.azimuth() ?: 0f
                var maxMove = 0f; var maxTurn = 0f; var tracked = 0; var samples = 0
                val t0 = System.currentTimeMillis()
                fun sample() {
                    val t = (System.currentTimeMillis() - t0) / 1000f
                    samples++
                    if (st != null) {
                        if (st.tracking) tracked++
                        val d = sqrt((st.camX - x0) * (st.camX - x0) + (st.camZ - z0) * (st.camZ - z0)); maxMove = maxOf(maxMove, d)
                        rows.append("%.1f,%s,%.3f,%.3f,\n".format(Locale.US, t, st.tracking, st.camX, st.camZ))
                    } else if (cv != null) {
                        var d = Math.toDegrees((cv.azimuth() - h0).toDouble()).toFloat(); while (d > 180) d -= 360; while (d < -180) d += 360
                        maxTurn = maxOf(maxTurn, kotlin.math.abs(d)); tracked++
                        rows.append("%.1f,true,,,%.1f\n".format(Locale.US, t, d))
                    }
                    if (t < 30f) ui.postDelayed({ sample() }, 500) else {
                        save("S1_still.csv", rows.toString())
                        val line = if (st != null) "S1 stand still (real AR): phone moved at most ${(maxMove * 100).toInt()} cm, tracking ${100 * tracked / maxOf(1, samples)}% · ${if (maxMove < 0.10f) "PASS" else "CHECK"} (pass under 10 cm)"
                                   else "S1 stand still (camera-lite): heading wandered at most ${maxTurn.toInt()}° · ${if (maxTurn < 5f) "PASS" else "CHECK"} (pass under 5°)"
                        result(line); writeSummary(); endBeep()
                        root.addView(big(line), 1)
                    }
                }
                sample()
            }
        })
    }

    // ------------------------------------------------------------------ S2 · two objects and back (real AR)

    private fun walkTest() {
        val stage = startCamera()
        val a = screen("S2 · Two objects and back", "1 Stand at object A. Tap A.\n2 Walk to object B. Tap B.\n3 Walk back to A. Tap Back.", stage)
        var ax = 0f; var az = 0f
        a.addView(button("A — I'm at the first object") {
            val st = arState ?: return@button
            if (!st.tracking) { root.addView(big("Move the phone slowly so it sees the ground."), 1); return@button }
            countdown { st.mark(game.n, 1.4f); ax = st.camX; az = st.camZ; root.addView(big("A saved. Walk to B."), 1) }
        })
        a.addView(button("B — I'm at the second object") {
            val st = arState ?: return@button
            st.mark(game.n, 1.4f)
            camView?.squareM = st.s
            val d = sqrt((st.camX - ax) * (st.camX - ax) + (st.camZ - az) * (st.camZ - az))
            result("S2 A to B: the phone measured ${(d * 100).toInt()} cm between the objects; squares ${(st.s * 100).toInt()} cm")
            root.addView(big("B saved: ${(d * 100).toInt()} cm from A. Walk back to A."), 1)
            tone?.startTone(ToneGenerator.TONE_PROP_BEEP, 200)
        })
        a.addView(button("Back — I'm at A again") {
            val st = arState ?: return@button
            val e = sqrt((st.camX - ax) * (st.camX - ax) + (st.camZ - az) * (st.camZ - az))
            val line = "S2 walk back to A: the board moved ${(e * 100).toInt()} cm · ${if (e < 0.25f) "PASS" else "CHECK"} (pass under 25 cm)"
            save("S2_walk.csv", "a_x,a_z,back_x,back_z,error_cm\n%.3f,%.3f,%.3f,%.3f,%d\n".format(Locale.US, ax, az, st.camX, st.camZ, (e * 100).toInt()))
            result(line); writeSummary(); endBeep()
            root.addView(big(line), 1)
        })
    }

    // ------------------------------------------------------------------ S3 · I'm here (real AR)

    private val hereRows = StringBuilder("round,target_r,target_c,phone_r,phone_c,off_squares\n")
    private var hereHits = 0

    private fun hereTest(round: Int) {
        val stage = startCamera()
        val target = intArrayOf((1..8).random(), (1..8).random())
        camView?.picked = game.idx(target[0], target[1])
        val a = screen("S3 · I'm here ($round of 3)", "1 Set the board: tap A at one object, B at the other.\n2 Walk to the blue square (row ${target[0] + 1}, column ${target[1] + 1}).\n3 Tap I'm here.", stage)
        a.addView(button("Set board (A, then B)") { arState?.let { st -> if (st.mark(game.n, 1.4f) == 2) { camView?.squareM = st.s; tone?.startTone(ToneGenerator.TONE_PROP_BEEP, 200) } } })
        a.addView(button("I'm here") {
            val st = arState ?: return@button
            val here = st.squareHere(game.n)
            val off = if (here == null) -1 else kotlin.math.abs(here[0] - target[0]) + kotlin.math.abs(here[1] - target[1])
            if (off in 0..1) hereHits++
            hereRows.append("$round,${target[0] + 1},${target[1] + 1},${here?.get(0)?.plus(1) ?: ""},${here?.get(1)?.plus(1) ?: ""},$off\n")
            endBeep()
            if (round < 3) hereTest(round + 1) else {
                save("S3_here.csv", hereRows.toString())
                val line = "S3 I'm here: $hereHits of 3 within 1 square · ${if (hereHits == 3) "PASS" else "CHECK"}"
                result(line); writeSummary()
                menu(); root.addView(big(line), 1)
            }
        })
    }

    // ------------------------------------------------------------------ S4 · sun and shade

    private fun lightTest() {
        val stage = startCamera()
        val a = screen("S4 · Sun and shade", "1 Point at the ground.\n2 Look at the lines and pillars.\n3 Tap Easy or Hard.", stage)
        fun answer(easy: Boolean) {
            val line = "S4 light ${if (lux >= 0) "${lux.toInt()} lux" else "(no light sensor)"}: lines and pillars ${if (easy) "easy" else "hard"} to see"
            save("S4_light.csv", "lux,easy\n${lux.toInt()},$easy\n")
            result(line); writeSummary(); endBeep()
            root.addView(big(line), 1)
        }
        a.addView(button("Easy to see") { answer(true) })
        a.addView(button("Hard to see") { answer(false) })
    }

    override fun onPause() { super.onPause(); camView?.stop(); arView?.pauseAr() }
    override fun onResume() { super.onResume(); try { arView?.resumeAr() } catch (e: Throwable) { }; camView?.let { if (arView == null) it.start() } }
    override fun onDestroy() {
        (getSystemService(SENSOR_SERVICE) as SensorManager).unregisterListener(this)
        stopCamera(); tone?.release(); ui.removeCallbacksAndMessages(null); super.onDestroy()
    }
}
