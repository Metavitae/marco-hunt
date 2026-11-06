package com.metavitae.marcohunt

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.metavitae.marcohunt.engine.Ai
import com.metavitae.marcohunt.engine.Game
import com.metavitae.marcohunt.engine.Player
import com.metavitae.marcohunt.engine.Rules
import com.metavitae.marcohunt.engine.Types.Band
import com.metavitae.marcohunt.engine.Types.Cls
import com.metavitae.marcohunt.engine.Types.Content
import com.metavitae.marcohunt.engine.Types.Dir
import com.metavitae.marcohunt.engine.Types.Kind
import com.metavitae.marcohunt.engine.Types.WinBy

/**
 * Solo Hunt on the plain on-screen board. The human is player 0 and never Marco;
 * the AI travellers are rivals (if one of them finds Marco first, the human loses).
 */
class GameActivity : androidx.activity.ComponentActivity(), Game.Listener {

    override fun attachBaseContext(base: Context) = super.attachBaseContext(Lang.wrap(base))

    private lateinit var g: Game
    private lateinit var ai: Ai
    private lateinit var me: Player
    private lateinit var board: BoardView
    private lateinit var stage: android.widget.FrameLayout
    private var camView: CameraBoardView? = null
    private var preview: androidx.camera.view.PreviewView? = null
    private var cameraOn = false
    private var pendingMove = -1
    private lateinit var camBtn: Button
    private lateinit var setBtn: Button
    private lateinit var hereBtn: Button
    private lateinit var status: TextView
    private lateinit var signal: TextView
    private lateinit var suspects: LinearLayout
    private lateinit var sac: LinearLayout
    private lateinit var log: TextView
    private lateinit var coach: TextView
    private val ui = Handler(Looper.getMainLooper())

    private var level = 1
    private var tutorial = false
    private var mode = Mode.WAIT
    private var turnOpen = false
    private val divPicks = ArrayList<Int>()
    private var signalsSeen = 0
    private var lastBand: Band? = null
    private val fits = HashMap<Int, Boolean>()
    private val proven = HashSet<Int>()
    private val lines = ArrayList<String>()
    private val coachShown = HashSet<Int>()
    private var finished = false

    private enum class Mode { WAIT, MOVE, DIVINE, ATTACK }

    override fun onCreate(saved: Bundle?) {
        super.onCreate(saved)
        level = intent.getIntExtra("level", 1)
        tutorial = intent.getBooleanExtra("tutorial", false)
        if (tutorial) level = 0
        newGame(intent.getIntExtra("cls", 0), intent.getIntExtra("n", 10), intent.getIntExtra("rivals", 3))
        buildUi()
        coachTip(1, R.string.t1)
        refresh()
        ui.postDelayed({ loop() }, 400)
    }

    // ------------------------------------------------------------------ setup

    private fun newGame(clsIdx: Int, n: Int, rivals: Int) {
        val r = Rules()
        r.n = n
        r.players = 1 + rivals
        r.teams = false                          // no teams in solo
        r.dieSides = if (n <= 10) 6 else 10      // Task S defaults
        r.oracleTolerance = if (n >= 20) 2 else 1 // Task O: about d, give or take 1 (2 on big boards)
        // solo turn limits (CC's solo run, Marco wins about 1 in 4 against the whole table)
        val base10 = intArrayOf(10, 8, 6, 5); val base15 = intArrayOf(10, 8, 7, 6)
        val t = (if (n <= 10) base10 else base15)[(r.players - 3).coerceIn(0, 3)]
        r.turnLimit = when (level) { 0 -> t + 3; 2 -> maxOf(4, t - 1); else -> t }
        if (level == 0) r.echoCharges = 3        // Apprentice: 3 echoes per game
        if (level == 2) r.decoyShare = 0.75      // Master: more decoys
        r.maxRounds = r.turnLimit
        val classes = Array(r.players) { k -> if (k == 0) Cls.values()[clsIdx] else Cls.values()[(0..3).random()] }
        var seed = System.nanoTime()
        var game: Game
        do { game = Game(r, seed++, classes) } while (game.marco.id == 0) // the human is never Marco in Solo Hunt
        g = game
        me = g.players[0]
        me.human = true
        ai = Ai(g)
        ai.cunning = when (level) { 0 -> 0.5; 2 -> 1.0; else -> 0.8 }
        val kinds = when (level) {
            0 -> arrayOf(Kind.WANDERER, Kind.FOLLOWER)
            2 -> arrayOf(Kind.DETECTIVE, Kind.FIGHTER, Kind.DETECTIVE)
            else -> arrayOf(Kind.FOLLOWER, Kind.FIGHTER, Kind.DETECTIVE)
        }
        for (p in g.players) if (p.id != 0) p.kind = kinds[p.id % kinds.size]
        g.listeners.add(this)
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Art.PARCHMENT); setPadding(dp(8), dp(8), dp(8), dp(8)) }
        status = text(15f, bold = true)
        root.addView(status)
        board = BoardView(this).apply { game = g; humanId = 0; onTap = { r, c -> tapped(r, c) }; onLongTap = { r, c -> readMap(r, c) } }
        val modeRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        camBtn = action(R.string.camera) { toggleCamera() }
        setBtn = action(R.string.set_board) { camView?.setHeadingHere(); say(getString(R.string.board_set)) }
        hereBtn = action(R.string.im_here) { arrive() }
        modeRow.addView(camBtn); modeRow.addView(setBtn); modeRow.addView(hereBtn)
        root.addView(modeRow)
        stage = android.widget.FrameLayout(this)
        stage.addView(board, android.widget.FrameLayout.LayoutParams(android.widget.FrameLayout.LayoutParams.MATCH_PARENT, android.widget.FrameLayout.LayoutParams.WRAP_CONTENT))
        root.addView(stage, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        signal = text(20f, bold = true).apply { gravity = Gravity.CENTER; setPadding(0, dp(6), 0, dp(6)) }
        root.addView(signal)
        suspects = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        root.addView(HorizontalScrollView(this).apply { addView(suspects) })
        sac = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        root.addView(HorizontalScrollView(this).apply { addView(sac) })
        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        actions.addView(action(R.string.rest) { doRest() })
        actions.addView(action(R.string.skill) { doSkill() })
        actions.addView(action(R.string.attack) { doAttack() })
        actions.addView(action(R.string.accuse) { doAccuse() })
        root.addView(actions)
        coach = text(15f).apply {
            setBackgroundColor(0xFFFFF6DD.toInt()); setPadding(dp(10), dp(8), dp(10), dp(8)); visibility = View.GONE
            setOnClickListener { visibility = View.GONE }
        }
        root.addView(coach)
        log = text(13f)
        root.addView(log)
        root.addView(text(12f).apply { text = getString(R.string.long_press) })
        setContentView(ScrollView(this).apply { setBackgroundColor(Art.PARCHMENT); addView(root) })
    }

    private fun text(size: Float, bold: Boolean = false) = TextView(this).apply {
        textSize = size; setTextColor(Art.INK); typeface = Typeface.create(Typeface.SERIF, if (bold) Typeface.BOLD else Typeface.NORMAL)
    }

    private fun action(res: Int, onClick: () -> Unit) = Button(this).apply {
        text = getString(res); isAllCaps = false; textSize = 14f
        setBackgroundResource(R.drawable.btn_parchment); setTextColor(Art.INK); typeface = Typeface.SERIF
        setPadding(dp(4), dp(10), dp(4), dp(10))
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = dp(3); rightMargin = dp(3) }
    }

    // ------------------------------------------------------------------ the turn loop

    private fun loop() {
        if (finished) return
        if (g.over) { showResult(); return }
        val p = g.current()
        if (p.human) {
            if (!turnOpen) {
                if (!g.beginTurn()) { say(getString(R.string.frozen, name(p))); refresh(); ui.postDelayed({ loop() }, 900); return }
                turnOpen = true
                mode = Mode.MOVE
                if (g.round >= 4 && tutorial) coachTip(6, R.string.t6)
            }
            refresh()
            return // wait for taps
        }
        if (g.beginTurn()) {
            status.text = getString(R.string.their_turn, name(p))
            val fr = p.r; val fc = p.c
            try { ai.playTurn(p) } catch (e: RuntimeException) { say("AI: ${e.message}") }
            val d = Math.abs(p.r - fr) + Math.abs(p.c - fc)
            if (!g.over) say(getString(R.string.moved, name(p), d))
        } else say(getString(R.string.frozen, name(p)))
        refresh()
        ui.postDelayed({ loop() }, if (g.over) 300 else 750)
    }

    private fun endHumanTurn() {
        turnOpen = false
        mode = Mode.WAIT
        divPicks.clear()
        if (g.over) { refresh(); ui.postDelayed({ showResult() }, 300); return }
        g.endTurn(me)
        refresh()
        ui.postDelayed({ loop() }, 700)
    }

    private fun tapped(r: Int, c: Int) {
        if (!turnOpen || g.over) return
        when (mode) {
            Mode.MOVE -> if (g.reachable(me, r, c, g.moveAllowance())) {
                if (cameraOn) { pendingMove = g.idx(r, c); say(getString(R.string.walk_there)); refresh(); return }
                guard { g.move(me, r, c) }
                if (tutorial && g.opened[g.idx(r, c)] && g.decorated(g.idx(r, c))) coachTip(4, R.string.t4)
                endHumanTurn()
            }
            Mode.DIVINE -> {
                val i = g.idx(r, c)
                if (g.at(r, c).isNotEmpty() || divPicks.contains(i)) return
                divPicks.add(i)
                if (divPicks.size == 2) {
                    guard { g.divination(me, divPicks[0] / g.n, divPicks[0] % g.n, divPicks[1] / g.n, divPicks[1] % g.n) }
                    divPicks.clear(); mode = Mode.MOVE
                }
                refresh()
            }
            Mode.ATTACK -> {
                val target = g.at(r, c).firstOrNull { it.id != 0 } ?: return
                if (me.dist(target) > com.metavitae.marcohunt.engine.Types.reach(me.cls)) { say(getString(R.string.pick_target, com.metavitae.marcohunt.engine.Types.reach(me.cls))); return }
                guard { g.attack(me, target) }
                mode = Mode.MOVE
                refresh()
            }
            Mode.WAIT -> {}
        }
    }

    private fun doRest() {
        if (!turnOpen || mode == Mode.WAIT) return
        guard { g.rest(me) }
        endHumanTurn()
    }

    private fun doSkill() {
        if (!turnOpen) return
        if (!g.skillReady(me)) { say("⌛"); return }
        when (me.cls) {
            Cls.MAGUS -> { mode = Mode.DIVINE; divPicks.clear(); say(getString(R.string.pick_two)) }
            Cls.MONGOL -> {
                val inReach = g.players.filter { it.id != 0 && me.dist(it) <= 3 }.sortedBy { me.dist(it) }
                if (inReach.isEmpty()) say(getString(R.string.no_target))
                else guard { g.volley(me, inReach[0], inReach.getOrNull(1)) }
            }
            Cls.KNIGHT -> say(getString(R.string.knight_d))
            Cls.TRAVELER -> say(getString(R.string.traveler_d))
        }
        refresh()
    }

    private fun doAttack() {
        if (!turnOpen) return
        val reach = com.metavitae.marcohunt.engine.Types.reach(me.cls)
        if (g.players.none { it.id != 0 && me.dist(it) <= reach }) { say(getString(R.string.no_target)); return }
        mode = Mode.ATTACK
        say(getString(R.string.pick_target, reach))
    }

    private fun doAccuse() {
        if (!turnOpen || me.accused || g.marcoRevealed) return
        val others = g.players.filter { it.id != 0 }
        AlertDialog.Builder(this).setTitle(R.string.accuse_q)
            .setItems(others.map { name(it) }.toTypedArray()) { _, k ->
                val q = others[k]
                val right = try { g.accuse(me, q) } catch (e: RuntimeException) { false }
                if (right) { refresh(); showResult() }
                else { proven.add(q.id); say(getString(R.string.wrong, name(q))); refresh() }
            }
            .setNegativeButton(R.string.cancel, null).show()
    }

    private fun useItem(item: Content) {
        if (!turnOpen) return
        guard {
            when (item) {
                Content.LEAP -> { g.useLeap(me); mode = Mode.MOVE }
                Content.SHIELD -> g.useShield(me)
                Content.SPYGLASS -> g.useSpyglass(me)
                Content.FALCON -> g.useFalcon(me)
                Content.COMPASS -> g.useCompass(me)
                else -> {}
            }
        }
        refresh()
    }

    private fun guard(block: () -> Unit) {
        try { block() } catch (e: RuntimeException) { say(e.message ?: "!") }
    }

    // ------------------------------------------------------------------ news from the engine

    override fun onSignal(g: Game, p: Player, band: Band) {
        if (p.id != 0) return
        signalsSeen++
        lastBand = band
        fits.clear()
        for (s in g.players) if (s.id != 0) fits[s.id] = g.reading(me.cls, me.dist(s)) == band
        if (tutorial && signalsSeen == 1) coachTip(2, R.string.t2)
        if (tutorial && signalsSeen == 2) coachTip(3, R.string.t3)
    }

    override fun onSignalHidden(g: Game, p: Player) { if (p.id == 0) { lastBand = null; say(getString(R.string.hidden)) } }
    override fun onFalcon(g: Game, p: Player, dir: Dir) { if (p.id == 0) say(getString(R.string.falcon, dirName(dir))) }
    override fun onCompass(g: Game, p: Player, rows: Boolean, value: Int) {
        if (p.id == 0) say(getString(if (rows) R.string.compass_rows else R.string.compass_cols, value))
    }
    override fun onDivination(g: Game, p: Player, ar: Int, ac: Int, br: Int, bc: Int, answer: Int) {
        if (p.id == 0) say(getString(when { answer < 0 -> R.string.div_a; answer > 0 -> R.string.div_b; else -> R.string.div_same }))
    }
    override fun onSpyglass(g: Game, p: Player, echoFound: Boolean) {
        if (p.id == 0) say(getString(if (echoFound) R.string.echo_caught else R.string.echo_none))
    }
    override fun onOracle(g: Game, p: Player, mark: Int, about: Int) {
        if (p.id != 0) return
        val m = g.marks[mark]
        val tpl = intArrayOf(R.string.rd0, R.string.rd1, R.string.rd2)[(g.round + mark) % 3]
        val riddle = getString(tpl, lmName(m[3]), markName(m[2]), markVerb(m[2]), about)
        say(riddle)
        ui.post { AlertDialog.Builder(this).setTitle(R.string.oracle_title).setMessage(riddle).setPositiveButton("OK", null).show() }
    }

    /** Long-press: read the map (landmark or mark names), so the riddles can be solved. */
    private fun readMap(r: Int, c: Int) {
        for ((k, lm) in g.landmarks.withIndex()) if (lm[0] == r && lm[1] == c) { say(getString(R.string.here_is, lmName(k))); return }
        for (m in g.marks) if (m[0] == r && m[1] == c) { say(getString(R.string.here_is, getString(R.string.mark_near, markName(m[2]), lmName(m[3])))); return }
    }

    private fun markName(t: Int) = getString(intArrayOf(R.string.mk0, R.string.mk1, R.string.mk2, R.string.mk3)[t])
    private fun markVerb(t: Int) = getString(intArrayOf(R.string.mv0, R.string.mv1, R.string.mv2, R.string.mv3)[t])
    override fun onNotMarco(g: Game, q: Player) { proven.add(q.id); say(getString(R.string.not_marco, name(q))) }
    override fun onReveal(g: Game, marco: Player) { board.revealedMarco = marco.id; say("☀ Spotlight: ${name(marco)} = Marco!") }
    override fun onFight(g: Game, a: Player, b: Player, winner: Int) {
        say(if (winner < 0) getString(R.string.fight_tie) else getString(R.string.fight_won, name(g.players[winner])))
        if (tutorial) coachTip(5, R.string.t5)
    }
    override fun onPeril(g: Game, p: Player, peril: Content, strength: Int, blocked: Boolean) {
        if (p.id != 0) return
        val cr = Art.creatureFor(peril, g.idx(p.r, p.c)) ?: return
        val bmp = Art.bitmap(this, cr) ?: return
        ui.post {
            val img = android.widget.ImageView(this).apply { setImageBitmap(bmp); adjustViewBounds = true; setPadding(dp(16), dp(8), dp(16), dp(8)) }
            AlertDialog.Builder(this).setTitle(itemName(peril)).setView(img).setPositiveButton("OK", null).show()
        }
    }

    override fun onOpen(g: Game, p: Player, what: Content) {
        val shown = if (p.id == 0) itemName(what) else when {
            what.isPeril -> "⚠"; what.isPowerUp -> "🎁"; what == Content.ORACLE -> itemName(what); else -> getString(R.string.nothing)
        }
        say(getString(R.string.opened, name(p), shown))
    }
    override fun onOver(g: Game) { refresh() }

    // ------------------------------------------------------------------ showing

    private fun refresh() {
        board.lit = if (turnOpen && mode == Mode.MOVE && !g.over) reachableSquares() else emptySet()
        camView?.let { cv ->
            cv.lit = board.lit; cv.picked = pendingMove; cv.revealedMarco = board.revealedMarco
            cv.tint = when (lastBand) { Band.HOT -> 0x22E05A3C; Band.WARM -> 0x22F0B040; Band.COLD -> 0x223C7FE0; null -> 0 }
            cv.invalidate()
        }
        if (::setBtn.isInitialized) {
            setBtn.visibility = if (cameraOn) View.VISIBLE else View.GONE
            hereBtn.visibility = if (cameraOn && pendingMove >= 0) View.VISIBLE else View.GONE
            camBtn.text = getString(if (cameraOn) R.string.map else R.string.camera)
        }
        board.picked = divPicks.toSet()
        board.invalidate()
        status.text = when {
            g.over -> ""
            turnOpen && g.current().human -> getString(R.string.round, g.round, g.rules.turnLimit) + " · " +
                getString(R.string.your_turn, g.moveAllowance()) + " · " + getString(R.string.opps, maxOf(0, g.opportunitiesLeft()))
            else -> getString(R.string.round, g.round, g.rules.turnLimit)
        }
        val b = lastBand
        signal.text = if (b == null) "" else getString(R.string.signal_says, bandName(b))
        signal.setBackgroundColor(when (b) { Band.HOT -> 0xFFE9B3A6.toInt(); Band.WARM -> 0xFFF0D59B.toInt(); Band.COLD -> 0xFFB9CDE6.toInt(); null -> Color.TRANSPARENT })
        // travellers strip: who fits your last signal (help depends on the level)
        suspects.removeAllViews()
        val help = tutorial || level == 0 || (level == 1 && signalsSeen <= 3)
        for (p in g.players) {
            if (p.id == 0) continue
            val mark = when {
                p.id == board.revealedMarco -> " ☀"
                proven.contains(p.id) -> " ✗"
                help && fits.containsKey(p.id) -> if (fits[p.id] == true) " ✓" else " ✗"
                else -> ""
            }
            suspects.addView(TextView(this).apply {
                text = "${p.id} ${clsName(p.cls)}$mark"; textSize = 14f; setTextColor(Art.INK)
                setBackgroundColor(Art.CLASS_COLOR[p.cls.ordinal]); setPadding(dp(8), dp(4), dp(8), dp(4))
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { rightMargin = dp(6) }
            })
        }
        // the sac
        sac.removeAllViews()
        sac.addView(TextView(this).apply { text = "${getString(R.string.items)} (${clsName(me.cls)}, ♥${me.vigor}): "; textSize = 14f; setTextColor(Art.INK) })
        for (it in me.items.toList()) sac.addView(Button(this).apply {
            text = itemName(it); isAllCaps = false; textSize = 13f
            setBackgroundResource(R.drawable.btn_parchment); setTextColor(Art.INK)
            setOnClickListener { _ -> useItem(it) }
        })
        log.text = lines.takeLast(6).reversed().joinToString("\n")
    }

    private fun reachableSquares(): Set<Int> {
        val k = g.moveAllowance()
        val out = HashSet<Int>()
        for (r in 0 until g.n) for (c in 0 until g.n) if (g.reachable(me, r, c, k)) out.add(g.idx(r, c))
        return out
    }

    private fun showResult() {
        if (finished) return
        finished = true
        val m = g.marco
        val head = when {
            g.winBy == WinBy.LANDING || g.winBy == WinBy.ACCUSATION ->
                if (g.finder?.id == 0) getString(R.string.you_win) else getString(R.string.rival_wins, name(g.finder!!))
            else -> getString(R.string.marco_escapes, g.rules.turnLimit)
        }
        val reveal = getString(R.string.reveal, name(m), g.casts, g.echoesSet, g.echoesDetected, g.trapsFromMarco[0], g.trapsFromLuck[0])
        board.revealedMarco = m.id
        board.invalidate()
        AlertDialog.Builder(this).setTitle(head).setMessage(reveal).setCancelable(false)
            .setPositiveButton(R.string.again) { _, _ -> recreate() }
            .setNegativeButton(R.string.home) { _, _ -> finish() }
            .show()
    }

    private fun coachTip(id: Int, res: Int) {
        if (!tutorial || coachShown.contains(id)) return
        coachShown.add(id)
        coach.text = "${getString(R.string.tutorial_title)} — ${getString(res)}   (${getString(R.string.got_it)} ✓)"
        coach.visibility = View.VISIBLE
    }

    private fun say(s: String) { lines.add(s); if (::log.isInitialized) log.text = lines.takeLast(6).reversed().joinToString("\n") }

    private fun name(p: Player) = if (p.id == 0) getString(R.string.you) else "${p.id} ${clsName(p.cls)}"
    private fun clsName(c: Cls) = getString(when (c) { Cls.KNIGHT -> R.string.knight; Cls.TRAVELER -> R.string.traveler; Cls.MAGUS -> R.string.magus; Cls.MONGOL -> R.string.mongol })
    private fun bandName(b: Band) = getString(when (b) { Band.HOT -> R.string.hot; Band.WARM -> R.string.warm; Band.COLD -> R.string.cold })
    private fun dirName(d: Dir) = getString(when (d) { Dir.N -> R.string.N; Dir.E -> R.string.E; Dir.S -> R.string.S; Dir.W -> R.string.W })
    private fun lmName(k: Int) = getString(intArrayOf(R.string.lm0, R.string.lm1, R.string.lm2, R.string.lm3, R.string.lm4, R.string.lm5)[k])
    private fun itemName(c: Content) = getString(when (c) {
        Content.LEAP -> R.string.leap; Content.SHIELD -> R.string.shield; Content.SPYGLASS -> R.string.spyglass
        Content.FALCON -> R.string.falcon_i; Content.COMPASS -> R.string.compass; Content.SERPENT -> R.string.serpent
        Content.DARKNESS -> R.string.darkness; Content.FREEZE -> R.string.freeze; Content.SCATTER -> R.string.scatter
        Content.ORACLE -> R.string.oracle; else -> R.string.nothing
    })

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    // ------------------------------------------------------------------ camera-lite board (M2)

    private fun toggleCamera() {
        if (cameraOn) {
            cameraOn = false; pendingMove = -1
            camView?.stop(); stage.removeAllViews()
            stage.addView(board); refresh(); return
        }
        if (checkSelfPermission(android.Manifest.permission.CAMERA) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            say(getString(R.string.cam_why))
            requestPermissions(arrayOf(android.Manifest.permission.CAMERA), 7); return
        }
        cameraOn = true
        val h = (resources.displayMetrics.heightPixels * 0.62f).toInt()
        val pv = androidx.camera.view.PreviewView(this)
        val cv = CameraBoardView(this).apply { game = g; humanId = 0; onTap = { r, c -> tapped(r, c) } }
        stage.removeAllViews()
        stage.addView(pv, android.widget.FrameLayout.LayoutParams(android.widget.FrameLayout.LayoutParams.MATCH_PARENT, h))
        stage.addView(cv, android.widget.FrameLayout.LayoutParams(android.widget.FrameLayout.LayoutParams.MATCH_PARENT, h))
        preview = pv; camView = cv
        val future = androidx.camera.lifecycle.ProcessCameraProvider.getInstance(this)
        future.addListener({
            try {
                val provider = future.get()
                val p = androidx.camera.core.Preview.Builder().build().also { it.setSurfaceProvider(pv.surfaceProvider) }
                provider.unbindAll()
                provider.bindToLifecycle(this, androidx.camera.core.CameraSelector.DEFAULT_BACK_CAMERA, p)
            } catch (e: Exception) { say("Camera: ${e.message}") }
        }, mainExecutor)
        cv.start()
        say(getString(R.string.cam_hint))
        refresh()
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(code, perms, results)
        if (code == 7 && results.isNotEmpty() && results[0] == android.content.pm.PackageManager.PERMISSION_GRANTED) toggleCamera()
        else if (code == 7) say(getString(R.string.cam_denied))
    }

    /** Walk mode: the player stands on the picked square and taps "I'm here"; then the move counts. */
    private fun arrive() {
        if (pendingMove < 0 || !turnOpen) return
        val r = pendingMove / g.n; val c = pendingMove % g.n
        pendingMove = -1
        guard { g.move(me, r, c) }
        endHumanTurn()
    }

    override fun onPause() { super.onPause(); camView?.stop() }
    override fun onResume() { super.onResume(); if (cameraOn) camView?.start() }

    override fun onDestroy() { finished = true; ui.removeCallbacksAndMessages(null); camView?.stop(); super.onDestroy() }
}
