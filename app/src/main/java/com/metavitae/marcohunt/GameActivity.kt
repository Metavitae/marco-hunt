package com.metavitae.marcohunt

import android.animation.ValueAnimator
import android.app.AlertDialog
import android.content.Context
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.animation.LinearInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.Button
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import com.metavitae.marcohunt.engine.Ai
import com.metavitae.marcohunt.engine.Game
import com.metavitae.marcohunt.engine.Player
import com.metavitae.marcohunt.engine.Rules
import com.metavitae.marcohunt.engine.Save
import com.metavitae.marcohunt.engine.Types
import com.metavitae.marcohunt.engine.Types.Band
import com.metavitae.marcohunt.engine.Types.Cls
import com.metavitae.marcohunt.engine.Types.Content
import com.metavitae.marcohunt.engine.Types.Dir
import com.metavitae.marcohunt.engine.Types.Kind
import com.metavitae.marcohunt.engine.Types.WinBy

/**
 * Solo Hunt. The human is player 0 and never Marco; the AI travellers are rivals.
 *
 * Rework of Nov 7 (founder's first real play: "a mess… I couldn't know why, when or the results"):
 *  - one screen, no scrolling, on the map and on the camera board;
 *  - every event that touches you is a tale card (what happened, why, what it does to you);
 *  - the other travellers' turns are played out one by one: the token walks, a banner says what they did;
 *  - the signal says what it means in squares and whether it got warmer or colder;
 *  - the guided hunt speaks in cards at the moment each thing first happens;
 *  - a game diary (Download/MarcoHunt/games) records every game for review.
 */
class GameActivity : androidx.activity.ComponentActivity(), Game.Listener {

    override fun attachBaseContext(base: Context) = super.attachBaseContext(Lang.wrap(base))

    private lateinit var g: Game
    private lateinit var ai: Ai
    private lateinit var me: Player
    private lateinit var board: BoardView
    private lateinit var stage: FrameLayout
    private lateinit var tale: Tale
    private lateinit var diary: Diary
    private lateinit var banner: TextView
    private var camView: CameraBoardView? = null
    private var preview: androidx.camera.view.PreviewView? = null
    private var cameraOn = false
    private var arView: ArView? = null
    private var arState: ArState? = null
    private var hereWarned = false
    private var pendingMove = -1
    private lateinit var camBtn: TextView
    private lateinit var quietBtn: TextView
    private lateinit var camRow: LinearLayout
    private lateinit var setBtn: Button
    private lateinit var hereBtn: Button
    private lateinit var status: TextView
    private lateinit var signalBox: LinearLayout
    private lateinit var signal: TextView
    private lateinit var signalWhy: TextView
    private lateinit var suspects: LinearLayout
    private lateinit var sac: LinearLayout
    private lateinit var ticker: TextView
    private lateinit var restBtn: Button
    private lateinit var skillBtn: Button
    private lateinit var attackBtn: Button
    private lateinit var accuseBtn: Button
    private val ui = Handler(Looper.getMainLooper())

    private var level = 1
    private var tutorial = false
    private var mode = Mode.WAIT
    private var turnOpen = false
    private val divPicks = ArrayList<Int>()
    private var signalsSeen = 0
    private var lastBand: Band? = null
    private var trend = 0
    private val fits = HashMap<Int, Boolean>()
    private val proven = HashSet<Int>()
    private val lines = ArrayList<String>()
    private val coachShown = HashSet<Int>()
    private var finished = false
    private var rivalTurns = 0

    // what each player looked like when the current action started (to tell the results of fights and perils)
    private class Snap(val r: Int, val c: Int, val vigor: Int, val items: List<Content>)
    private val snaps = HashMap<Int, Snap>()
    private var landedAt: IntArray? = null
    private val turnNews = ArrayList<String>()
    private val pendingCards = ArrayList<Tale.Card>()
    private var notMarcoNow = -1

    private enum class Mode { WAIT, MOVE, DIVINE, ATTACK }

    override fun onCreate(saved: Bundle?) {
        super.onCreate(saved)
        level = intent.getIntExtra("level", 1)
        tutorial = intent.getBooleanExtra("tutorial", false)
        Signal.init(this)
        if (tutorial) level = 0
        val resumed = intent.getBooleanExtra("resume", false) && resumeCamp()
        if (!resumed) newGame(intent.getIntExtra("cls", 0), intent.getIntExtra("n", 10), intent.getIntExtra("rivals", 3))
        buildUi()
        diary = Diary(this)
        diary.start("board ${g.n}x${g.n} · level $level · tutorial $tutorial · you ${clsName(me.cls)} · ${g.players.size - 1} rivals · " +
            (if (resumed) "resumed from camp" else "new game") + "\nMarco is ${name(g.marco)} (secret)")
        tale.onShow = { c -> diary.line(g.round, "CARD ${c.title}: ${c.what} ${c.why}") }
        val startLine = getString(R.string.your_start, me.r + 1, me.c + 1)
        if (tutorial && !resumed) teach(1, R.string.tw_title, getString(R.string.tw) + "\n\n" + startLine)
        else if (!resumed) tale.say(startLine, 3200)
        refresh()
        ui.postDelayed({ loop() }, 500)
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

    private lateinit var roundChip: TextView
    private lateinit var die: TextView
    private lateinit var oppsChip: TextView
    private lateinit var hudFrame: FrameLayout
    private lateinit var hudRoot: LinearLayout
    private lateinit var midSlot: FrameLayout
    private lateinit var miniMap: MiniMapView
    private lateinit var guide: TextView
    private lateinit var sacScroll: HorizontalScrollView
    private var miniBig = false

    /** While the camera is on, the guide line and the little map's phone dot follow the player. */
    private val guideTick = object : Runnable {
        override fun run() {
            if (!cameraOn || isDestroyed) return
            updateGuide()
            ui.postDelayed(this, 500)
        }
    }

    /** Rounded parchment card (UI kit): fill, ink border, 12dp corners. */
    private fun card(fill: Int, stroke: Int = Art.INK, strokeDp: Int = 1, radiusDp: Int = 12) =
        android.graphics.drawable.GradientDrawable().apply { setColor(fill); cornerRadius = dp(radiusDp).toFloat(); setStroke(dp(strokeDp), stroke) }

    private fun gap(h: Int) = View(this).apply { layoutParams = LinearLayout.LayoutParams(1, dp(h)) }
    private fun spring() = View(this).apply { layoutParams = LinearLayout.LayoutParams(0, 1, 1f) }

    /**
     * One screen, top to bottom, nothing below the fold:
     * header (round · die · actions · camera · sound) → what to do now → the signal → the board (takes
     * the rest) → the latest news → travellers → you and your sac → the four actions.
     * Cards and the banner float above.
     */
    private fun buildUi() {
        val frame = FrameLayout(this).apply { setBackgroundColor(Art.PARCHMENT) }
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), dp(10), dp(12), dp(10)) }
        frame.addView(root, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        hudFrame = frame; hudRoot = root

        val header = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        roundChip = text(13f, bold = true).apply { background = card(0xFFFBF3DF.toInt()); setPadding(dp(7), dp(8), dp(7), dp(8)) }
        die = TextView(this).apply {
            textSize = 24f; setTextColor(0xFFFFFFFF.toInt()); gravity = Gravity.CENTER; typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD)
            background = card(Art.MADDER, Art.INK, 2, 10)
            layoutParams = LinearLayout.LayoutParams(dp(44), dp(44))
        }
        oppsChip = text(13f, bold = true).apply { background = card(0xFFFBF3DF.toInt()); setPadding(dp(7), dp(8), dp(7), dp(8)) }
        camBtn = iconButton("📷") { toggleCamera() }
        quietBtn = iconButton("🔈") { Signal.setQuiet(this, !Signal.quiet(this)); refresh() }
        header.addView(roundChip); header.addView(spring()); header.addView(die); header.addView(spring()); header.addView(oppsChip)
        header.addView(camBtn); header.addView(quietBtn)
        root.addView(header)
        root.addView(gap(8))
        status = text(16f, bold = true).apply { gravity = Gravity.CENTER }
        root.addView(status)
        root.addView(gap(6))
        signalBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), dp(6), dp(12), dp(8)); visibility = View.GONE }
        signal = text(19f, bold = true).apply { gravity = Gravity.CENTER }
        signalWhy = text(13f).apply { gravity = Gravity.CENTER; minLines = 3 } // keeps its height so the board never jumps under a finger
        signalBox.addView(signal); signalBox.addView(signalWhy)
        signalBox.setOnClickListener { explainSignal() }
        root.addView(signalBox)
        root.addView(gap(8))

        // the stage: board or camera, takes all the height that is left
        stage = FrameLayout(this)
        board = BoardView(this).apply { game = g; humanId = 0; onTap = { r, c -> tapped(r, c) }; onLongTap = { r, c -> readMap(r, c) } }
        stage.addView(board, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT, Gravity.CENTER))
        banner = TextView(this).apply {
            textSize = 16f; setTextColor(0xFFFBF3DF.toInt()); typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD); gravity = Gravity.CENTER
            background = card(0xEE3A2412.toInt(), Art.GOLD, 2, 14); setPadding(dp(14), dp(10), dp(14), dp(10)); visibility = View.GONE
            elevation = dp(4).toFloat()
        }
        stage.addView(banner, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP).apply { topMargin = dp(8); leftMargin = dp(8); rightMargin = dp(8) })
        camRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; visibility = View.GONE }
        setBtn = action(R.string.set_board) { setBoard() }
        hereBtn = action(R.string.im_here) { arrive() }
        camRow.addView(setBtn); camRow.addView(hereBtn)
        stage.addView(camRow, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM).apply { bottomMargin = dp(10) })
        root.addView(stage, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        root.addView(gap(8))

        // what floats in the middle while the camera fills the screen: the little map and the guide line.
        // The slot itself lets taps through to the camera board underneath.
        midSlot = FrameLayout(this)
        miniMap = MiniMapView(this).apply {
            game = g; humanId = 0; elevation = dp(3).toFloat()
            setOnClickListener { miniBig = !miniBig; placeMiniMap() }
        }
        midSlot.addView(miniMap)
        placeMiniMap()
        guide = TextView(this).apply {
            textSize = 15f; setTextColor(0xFFFBF3DF.toInt()); typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD); gravity = Gravity.CENTER
            background = card(0xDD3A2412.toInt(), Art.GOLD, 1, 12); setPadding(dp(10), dp(7), dp(10), dp(7))
        }
        midSlot.addView(guide, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM).apply { bottomMargin = dp(62) })

        ticker = text(14f).apply {
            maxLines = 1; ellipsize = TextUtils.TruncateAt.END
            background = card(0xFFFBF3DF.toInt(), 0xFFC9B892.toInt(), 1, 10); setPadding(dp(12), dp(8), dp(12), dp(8))
            setOnClickListener { showChronicle() }
        }
        root.addView(ticker)
        root.addView(gap(8))
        suspects = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        root.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(suspects) })
        root.addView(gap(8))
        sac = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        sacScroll = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(sac) }
        root.addView(sacScroll)
        root.addView(gap(10))
        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        restBtn = action(R.string.rest) { doRest() }
        skillBtn = action(R.string.skill) { doSkill() }
        attackBtn = action(R.string.attack) { doAttack() }
        accuseBtn = action(R.string.accuse) { doAccuse() }
        actions.addView(restBtn); actions.addView(skillBtn); actions.addView(attackBtn); actions.addView(accuseBtn)
        root.addView(actions)

        val layer = FrameLayout(this).apply { visibility = View.GONE; elevation = dp(12).toFloat() }
        frame.addView(layer, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        tale = Tale(this, layer, banner)
        setContentView(frame)
    }

    private fun text(size: Float, bold: Boolean = false) = TextView(this).apply {
        textSize = size; setTextColor(Art.INK); typeface = Typeface.create(Typeface.SERIF, if (bold) Typeface.BOLD else Typeface.NORMAL)
    }

    private fun iconButton(label: String, onClick: () -> Unit) = TextView(this).apply {
        text = label; textSize = 20f; gravity = Gravity.CENTER
        setBackgroundResource(R.drawable.btn_parchment)
        layoutParams = LinearLayout.LayoutParams(dp(42), dp(42)).apply { leftMargin = dp(5) }
        setOnClickListener { onClick() }
    }

    private fun action(res: Int, onClick: () -> Unit) = Button(this).apply {
        text = getString(res); isAllCaps = false; textSize = 15f
        setBackgroundResource(R.drawable.btn_parchment); setTextColor(Art.INK); typeface = Typeface.SERIF
        setPadding(dp(4), dp(10), dp(4), dp(10))
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = dp(4); rightMargin = dp(4) }
    }

    // ------------------------------------------------------------------ the turn loop

    private fun loop() {
        if (finished) return
        if (tale.busy()) { ui.postDelayed({ loop() }, 200); return }
        if (g.over) { showResult(); return }
        val p = g.current()
        if (p.human) {
            if (!turnOpen) {
                if (!g.beginTurn()) {
                    tale.show(Tale.Card(getString(R.string.frozen_title), getString(R.string.frozen_you), accent = 0xFF6FA8DC.toInt()))
                    refresh(); ui.postDelayed({ loop() }, 300); return
                }
                turnOpen = true
                mode = Mode.MOVE
                diary.line(g.round, "YOUR TURN · roll ${g.moveAllowance()} · at ${sq(me.r, me.c)} · hearts ${me.vigor} · sac ${me.items.joinToString { itemName(it) }}")
                yourTurnCue()
                if (tutorial) teach(2, R.string.tm_title, getString(R.string.tm, g.moveAllowance()))
                if (tutorial && g.round == 2) teach(3, R.string.td_title, getString(R.string.td))
                accuseHint()
            }
            refresh()
            return // wait for taps
        }
        // a rival's turn, played out so it can be followed
        if (tutorial && rivalTurns == 0) { rivalTurns++; teach(4, R.string.tr_title, getString(R.string.tr)); ui.postDelayed({ loop() }, 200); return }
        rivalTurns++
        if (!g.beginTurn()) {
            val s = getString(R.string.frozen, name(p))
            tale.say(s, 1500); news(s); refresh(); ui.postDelayed({ loop() }, 1600); return
        }
        snapAll()
        landedAt = null
        turnNews.clear()
        val fr = p.r; val fc = p.c
        try { ai.playTurn(p) } catch (e: RuntimeException) { diary.line(g.round, "AI error: ${e.message}") }
        val d = Math.abs(p.r - fr) + Math.abs(p.c - fc)
        status.text = getString(R.string.rival_turn, name(p))
        tale.say(getString(R.string.rival_turn, name(p)), 4000)
        board.anim[p.id] = floatArrayOf(fr.toFloat(), fc.toFloat())
        refresh()
        walk(p.id, fr, fc, p.r, p.c) {
            val what = ArrayList<String>()
            what.add(when (d) { 0 -> getString(R.string.stayed); 1 -> getString(R.string.walked_one); else -> getString(R.string.walked, d) })
            what.addAll(turnNews)
            val line = name(p) + ": " + what.joinToString(" · ")
            tale.say(line, 2400)
            news(line)
            refresh()
            ui.postDelayed({
                for (c in pendingCards) tale.show(c)
                pendingCards.clear()
                loop()
            }, 1700L + 500L * turnNews.size)
        }
    }

    /** The token walks its way (rows first, then columns) instead of jumping. */
    private fun walk(id: Int, fr: Int, fc: Int, tr: Int, tc: Int, done: () -> Unit) {
        val dr = tr - fr; val dc = tc - fc
        val steps = Math.abs(dr) + Math.abs(dc)
        if (steps == 0) { board.anim.remove(id); camView?.anim?.remove(id); done(); return }
        val va = ValueAnimator.ofFloat(0f, steps.toFloat())
        va.duration = (240L * steps).coerceIn(400L, 1800L)
        va.interpolator = LinearInterpolator()
        va.addUpdateListener {
            val t = it.animatedValue as Float
            val pos = if (t <= Math.abs(dr)) floatArrayOf(fr + Math.signum(dr.toFloat()) * t, fc.toFloat())
                      else floatArrayOf(tr.toFloat(), fc + Math.signum(dc.toFloat()) * (t - Math.abs(dr)))
            board.anim[id] = pos; camView?.anim?.set(id, pos)
            board.invalidate(); camView?.invalidate()
        }
        va.addListener(object : android.animation.AnimatorListenerAdapter() {
            override fun onAnimationEnd(a: android.animation.Animator) { board.anim.remove(id); camView?.anim?.remove(id); board.invalidate(); camView?.invalidate(); done() }
        })
        va.start()
    }

    private fun yourTurnCue() {
        tale.say(getString(R.string.your_turn_banner, g.moveAllowance()), 1600)
        die.scaleX = 0.6f; die.scaleY = 0.6f
        die.animate().scaleX(1f).scaleY(1f).setDuration(380).setInterpolator(OvershootInterpolator(2.2f)).start()
    }

    private fun endHumanTurn() {
        turnOpen = false
        mode = Mode.WAIT
        divPicks.clear()
        status.text = " " // your turn is over: don't keep saying "tap a lit square"
        if (g.over) { refresh(); ui.postDelayed({ loop() }, 300); return }
        g.endTurn(me)
        refresh()
        ui.postDelayed({ loop() }, 600)
    }

    private fun snapAll() {
        snaps.clear()
        for (p in g.players) snaps[p.id] = Snap(p.r, p.c, p.vigor, p.items.toList())
        notMarcoNow = -1
    }

    /** Runs one of the player's own actions with a snapshot first, so its results can be told. */
    private fun act(label: String, block: () -> Unit) {
        snapAll()
        diary.line(g.round, "YOU · $label")
        guard(block)
        refresh()
    }

    private fun tapped(r: Int, c: Int) {
        if (!turnOpen || g.over || tale.busy()) return
        when (mode) {
            Mode.MOVE -> if (g.reachable(me, r, c, g.moveAllowance())) {
                if (cameraOn) { pendingMove = g.idx(r, c); tale.say(getString(R.string.walk_there), 2600); refresh(); return }
                landedAt = intArrayOf(r, c)
                act("move to ${sq(r, c)}") { g.move(me, r, c) }
                endHumanTurn()
            } else tale.say(getString(R.string.not_lit, g.moveAllowance()), 2000)
            Mode.DIVINE -> {
                val i = g.idx(r, c)
                if (g.at(r, c).isNotEmpty() || divPicks.contains(i)) return
                divPicks.add(i)
                if (divPicks.size == 2) {
                    act("divination ${sq(divPicks[0] / g.n, divPicks[0] % g.n)} vs ${sq(divPicks[1] / g.n, divPicks[1] % g.n)}") {
                        g.divination(me, divPicks[0] / g.n, divPicks[0] % g.n, divPicks[1] / g.n, divPicks[1] % g.n)
                    }
                    divPicks.clear(); mode = Mode.MOVE
                }
                refresh()
            }
            Mode.ATTACK -> {
                val target = g.at(r, c).firstOrNull { it.id != 0 } ?: return
                if (me.dist(target) > Types.reach(me.cls)) { tale.say(getString(R.string.pick_target, Types.reach(me.cls)), 2200); return }
                landedAt = null
                act("attack ${name(target)}") { g.attack(me, target) }
                mode = Mode.MOVE
                refresh()
            }
            Mode.WAIT -> {}
        }
    }

    private fun doRest() {
        if (!turnOpen || mode == Mode.WAIT || tale.busy()) return
        val before = me.vigor
        act("rest") { g.rest(me) }
        tale.say(if (me.vigor > before) getString(R.string.rest_done, hearts(me.vigor)) else getString(R.string.rest_full), 2000)
        endHumanTurn()
    }

    private fun doSkill() {
        if (!turnOpen || tale.busy()) return
        if (!g.skillReady(me)) { tale.say(getString(R.string.skill_wait, me.skillReadyAt), 2200); return }
        when (me.cls) {
            Cls.MAGUS -> { mode = Mode.DIVINE; divPicks.clear(); tale.say(getString(R.string.pick_two), 2600) }
            Cls.MONGOL -> {
                val inReach = g.players.filter { it.id != 0 && me.dist(it) <= 3 }.sortedBy { me.dist(it) }
                if (inReach.isEmpty()) tale.say(getString(R.string.no_target), 2000)
                else { landedAt = null; act("volley") { g.volley(me, inReach[0], inReach.getOrNull(1)) } }
            }
            Cls.KNIGHT -> tale.show(Tale.Card(getString(R.string.knight), getString(R.string.knight_d), getString(R.string.skill_auto)))
            Cls.TRAVELER -> tale.show(Tale.Card(getString(R.string.traveler), getString(R.string.traveler_d), getString(R.string.skill_auto)))
        }
        refresh()
    }

    private fun doAttack() {
        if (!turnOpen || tale.busy()) return
        val reach = Types.reach(me.cls)
        if (g.opportunitiesLeft() <= 0) { tale.say(getString(R.string.no_actions), 2200); return }
        if (g.players.none { it.id != 0 && me.dist(it) <= reach }) { tale.say(getString(R.string.no_target_reach, reach), 2400); return }
        mode = Mode.ATTACK
        tale.say(getString(R.string.pick_target, reach), 2600)
    }

    private fun doAccuse() {
        if (!turnOpen || me.accused || g.marcoRevealed || tale.busy()) return
        val others = g.players.filter { it.id != 0 }
        AlertDialog.Builder(this).setTitle(R.string.accuse_q)
            .setItems(others.map { name(it) }.toTypedArray()) { _, k ->
                val q = others[k]
                diary.line(g.round, "YOU · accuse ${name(q)}")
                val right = try { g.accuse(me, q) } catch (e: RuntimeException) { false }
                if (right) { refresh(); loop() }
                else {
                    proven.add(q.id)
                    tale.show(Tale.Card(getString(R.string.wrong_title), getString(R.string.wrong, name(q)), getString(R.string.wrong_why), accent = Art.MADDER))
                    refresh()
                }
            }
            .setNegativeButton(R.string.cancel, null).show()
    }

    private fun useItem(item: Content) {
        if (!turnOpen || tale.busy()) return
        if (g.opportunitiesLeft() <= 0) { tale.say(getString(R.string.no_actions), 2200); return }
        act("use ${itemName(item)}") {
            when (item) {
                Content.LEAP -> { val s = g.useLeap(me); mode = Mode.MOVE; tale.say(getString(R.string.leap_used, s, g.moveAllowance()), 2600) }
                Content.SHIELD -> { g.useShield(me); tale.say(getString(R.string.shield_up), 2400) }
                Content.SPYGLASS -> g.useSpyglass(me)
                Content.FALCON -> g.useFalcon(me)
                Content.COMPASS -> g.useCompass(me)
                else -> {}
            }
        }
    }

    private fun guard(block: () -> Unit) {
        try { block() } catch (e: RuntimeException) { tale.say(e.message ?: "!", 2000); diary.line(g.round, "refused: ${e.message}") }
    }

    // ------------------------------------------------------------------ news from the engine

    override fun onSignal(g: Game, p: Player, band: Band) {
        if (p.id != 0) return
        signalsSeen++
        val prev = lastBand
        trend = if (prev == null) 0 else heat(band).compareTo(heat(prev))
        lastBand = band
        // colour always; sound while the camera board shows (unless quiet); vibration only when the camera is off
        if (cameraOn) { if (!Signal.quiet(this)) Signal.play(band) } else Signal.buzz(this, band)
        fits.clear()
        for (s in g.players) if (s.id != 0) fits[s.id] = g.reading(me.cls, me.dist(s)) == band
        diary.line(g.round, "SIGNAL ${bandName(band)} (trend $trend) · fits: " + fits.entries.joinToString { "${it.key}:${if (it.value) "yes" else "no"}" } +
            " · true distance to Marco ${me.dist(g.marco)}")
        if (signalsSeen == 1 && (tutorial || level == 0)) ui.post { explainSignal() }
        if (signalsSeen == 1 && !tutorial && level > 0) ui.post { tale.say(getString(R.string.sig_tap_hint), 2600) }
    }

    private fun heat(b: Band) = when (b) { Band.HOT -> 2; Band.WARM -> 1; Band.COLD -> 0 }

    private fun meaning(b: Band): String {
        val h = g.rules.hotMax(); val w = g.rules.warmMax()
        val s = when (b) {
            Band.HOT -> getString(R.string.sig_hot, h)
            Band.WARM -> getString(R.string.sig_warm, h + 1, w)
            Band.COLD -> getString(R.string.sig_cold, if (me.cls == Cls.KNIGHT) h else w)
        }
        return if (me.cls == Cls.KNIGHT && b == Band.COLD) s + " " + getString(R.string.sig_knight) else s
    }

    private fun explainSignal() {
        val b = lastBand ?: return
        tale.show(Tale.Card(getString(R.string.sig_card_title, bandName(b)), meaning(b), getString(R.string.sig_card_why), accent = bandColor(b)))
    }

    override fun onSignalHidden(g: Game, p: Player) {
        if (p.id != 0) return
        lastBand = null
        tale.show(Tale.Card(getString(R.string.darkness), getString(R.string.hidden), getString(R.string.hidden_why), accent = 0xFF4A4A6A.toInt()))
    }
    override fun onFalcon(g: Game, p: Player, dir: Dir) {
        if (p.id == 0) tale.show(Tale.Card(getString(R.string.falcon_i), getString(R.string.falcon, dirName(dir)), getString(R.string.falcon_why)))
    }
    override fun onCompass(g: Game, p: Player, rows: Boolean, value: Int) {
        if (p.id == 0) tale.show(Tale.Card(getString(R.string.compass), getString(if (rows) R.string.compass_rows else R.string.compass_cols, value), getString(R.string.compass_why)))
    }
    override fun onDivination(g: Game, p: Player, ar: Int, ac: Int, br: Int, bc: Int, answer: Int) {
        if (p.id == 0) tale.show(Tale.Card(getString(R.string.magus), getString(when { answer < 0 -> R.string.div_a; answer > 0 -> R.string.div_b; else -> R.string.div_same })))
    }
    override fun onSpyglass(g: Game, p: Player, echoFound: Boolean) {
        if (p.id == 0) tale.show(Tale.Card(getString(R.string.spyglass), getString(if (echoFound) R.string.echo_caught else R.string.echo_none)))
    }
    override fun onOracle(g: Game, p: Player, mark: Int, about: Int) {
        if (p.id != 0) return
        val m = g.marks[mark]
        val tpl = intArrayOf(R.string.rd0, R.string.rd1, R.string.rd2)[(g.round + mark) % 3]
        val riddle = getString(tpl, lmName(m[3]), markName(m[2]), markVerb(m[2]), about)
        news(riddle)
        tale.show(Tale.Card(getString(R.string.oracle_title), riddle, getString(R.string.long_press), accent = Art.GOLD))
    }

    /** Long-press: read the map (landmark or mark names), so the riddles can be solved. */
    private fun readMap(r: Int, c: Int) {
        for ((k, lm) in g.landmarks.withIndex()) if (lm[0] == r && lm[1] == c) { tale.say(getString(R.string.here_is, lmName(k)), 2400); return }
        for (m in g.marks) if (m[0] == r && m[1] == c) { tale.say(getString(R.string.here_is, getString(R.string.mark_near, markName(m[2]), lmName(m[3]))), 2400); return }
        val i = g.idx(r, c)
        tale.say(when {
            g.unopenedDecorated(i) -> getString(R.string.read_deco)
            g.opened[i] -> getString(R.string.read_opened)
            else -> getString(R.string.read_plain)
        }, 2000)
    }

    private fun markName(t: Int) = getString(intArrayOf(R.string.mk0, R.string.mk1, R.string.mk2, R.string.mk3)[t])
    private fun markVerb(t: Int) = getString(intArrayOf(R.string.mv0, R.string.mv1, R.string.mv2, R.string.mv3)[t])

    override fun onNotMarco(g: Game, q: Player) {
        proven.add(q.id)
        notMarcoNow = q.id
        if (!g.current().human && q.id != 0) turnNews.add(getString(R.string.not_marco, name(q))) // you know you are not Marco
    }

    override fun onReveal(g: Game, marco: Player) {
        board.revealedMarco = marco.id
        val c = Tale.Card(getString(R.string.spot_title), getString(R.string.spot, name(marco)), getString(R.string.spot_why), accent = Art.MADDER)
        if (g.current().human) tale.show(c) else pendingCards.add(c)
    }

    override fun onFight(g: Game, a: Player, b: Player, winner: Int) {
        if (a.id != 0 && b.id != 0) {
            turnNews.add(getString(R.string.r_fight, name(if (a.id == g.current().id) b else a),
                if (winner < 0) getString(R.string.r_fight_draw) else getString(R.string.r_fight_won, name(g.players[winner]))))
            return
        }
        val other = if (a.id == 0) b else a
        val s0 = snaps[0]
        val parts = ArrayList<String>()
        when {
            winner < 0 -> parts.add(getString(R.string.fight_draw))
            winner == 0 -> {
                parts.add(getString(R.string.fight_you_won))
                val gained = diff(me.items, s0?.items)
                parts.add(if (gained != null) getString(R.string.f_take, itemName(gained)) else getString(R.string.f_bonus))
                parts.add(getString(R.string.f_push_them))
            }
            else -> {
                parts.add(getString(R.string.fight_they_won, name(other)))
                parts.add(getString(R.string.p_heart, hearts(me.vigor)))
                val from = landedAt ?: s0?.let { intArrayOf(it.r, it.c) }
                val pushed = if (from == null) 0 else Math.abs(me.r - from[0]) + Math.abs(me.c - from[1])
                if (pushed > 0) parts.add(getString(R.string.f_pushed, pushed))
                val lost = diff(s0?.items ?: emptyList(), me.items)
                if (lost != null) parts.add(getString(R.string.f_they_take, itemName(lost)))
            }
        }
        if (notMarcoNow == other.id && a.id == 0) parts.add(getString(R.string.f_not_marco, name(other)))
        val title = getString(if (a.id == 0) R.string.fight_title else R.string.fight_title_them, name(other))
        val c = Tale.Card(title, parts.joinToString("\n"), getString(R.string.fight_why), accent = Art.MADDER)
        if (g.current().human) tale.show(c) else { pendingCards.add(c); turnNews.add(getString(R.string.r_fight, getString(R.string.you_obj), if (winner < 0) getString(R.string.r_fight_draw) else getString(R.string.r_fight_won, name(g.players[winner])))) }
    }

    /** First item in a that is not matched in b (multiset difference), or null. */
    private fun diff(a: List<Content>, b: List<Content>?): Content? {
        val rest = (b ?: emptyList()).toMutableList()
        for (x in a) if (!rest.remove(x)) return x
        return null
    }

    override fun onPeril(g: Game, p: Player, peril: Content, strength: Int, blocked: Boolean) {
        if (p.id != 0) {
            turnNews.add(getString(if (blocked) R.string.r_peril_blocked else R.string.r_peril_hit, itemName(peril)))
            return
        }
        val cr = Art.creatureFor(peril, g.idx(p.r, p.c))
        val bmp = if (cr != null) Art.bitmap(this, cr) else null
        val parts = ArrayList<String>()
        if (blocked) {
            val hadShield = snaps[0]?.items?.contains(Content.SHIELD) == true && !me.items.contains(Content.SHIELD)
            parts.add(getString(if (hadShield) R.string.p_blocked_shield else R.string.p_blocked_weak))
        } else {
            // the move has not happened yet when this arrives: tell what it will do
            parts.add(when (peril) {
                Content.SERPENT -> getString(R.string.p_serpent, strength)
                Content.DARKNESS -> getString(R.string.p_darkness, if (strength <= 3) 1 else 2)
                Content.FREEZE -> getString(R.string.p_freeze)
                Content.SCATTER -> getString(R.string.p_scatter, strength)
                else -> ""
            })
            parts.add(getString(R.string.p_heart, hearts(maxOf(0, me.vigor - 1))))
        }
        tale.show(Tale.Card(getString(R.string.peril_title, itemName(peril)), parts.joinToString("\n"), getString(R.string.p_why) + "\n" + getString(R.string.hearts_why),
            img = bmp, accent = Art.MADDER))
    }

    override fun onOpen(g: Game, p: Player, what: Content) {
        if (p.id != 0) {
            turnNews.add(when {
                what.isPowerUp -> getString(R.string.r_found_good)
                what == Content.ORACLE -> getString(R.string.r_found_oracle)
                what.isPeril -> getString(R.string.r_found_peril)
                else -> getString(R.string.r_found_nothing)
            })
            return
        }
        diary.line(g.round, "YOU OPEN ${itemName(what)}")
        when {
            what.isPowerUp -> tale.show(Tale.Card(getString(R.string.found_title, itemName(what)), itemDesc(what),
                getString(if (what == Content.SHIELD) R.string.shield_sac else R.string.in_sac), accent = Art.GOLD))
            what == Content.ORACLE -> tale.show(Tale.Card(getString(R.string.oracle_found_title), getString(R.string.oracle_found), accent = Art.GOLD))
            what.isPeril -> {} // the peril card tells it
            else -> { tale.say(getString(R.string.nothing_here), 1800); news(getString(R.string.nothing_here)) }
        }
        if (tutorial) teach(3, R.string.td_title, getString(R.string.td))
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
        camRow.visibility = if (cameraOn) View.VISIBLE else View.GONE
        hereBtn.visibility = if (cameraOn && pendingMove >= 0) View.VISIBLE else View.GONE
        camBtn.text = if (cameraOn) "🗺" else "📷"
        quietBtn.text = if (Signal.quiet(this)) "🔇" else "🔈"
        board.picked = divPicks.toSet()
        board.invalidate()
        miniMap.lit = board.lit; miniMap.picked = pendingMove; miniMap.invalidate()
        if (cameraOn) updateGuide()
        roundChip.text = getString(R.string.round_short, g.round, g.rules.turnLimit)
        val myTurn = turnOpen && g.current().human && !g.over
        die.text = if (myTurn) g.moveAllowance().toString() else "·"
        die.alpha = if (myTurn) 1f else 0.45f
        oppsChip.text = getString(R.string.actions_short, if (myTurn) maxOf(0, g.opportunitiesLeft()) else 0)
        status.text = when {
            g.over -> ""
            myTurn && mode == Mode.DIVINE -> getString(R.string.pick_two)
            myTurn && mode == Mode.ATTACK -> getString(R.string.pick_target, Types.reach(me.cls))
            myTurn && cameraOn && pendingMove >= 0 -> getString(R.string.walk_there)
            myTurn -> getString(R.string.your_turn, g.moveAllowance())
            else -> status.text
        }
        status.visibility = if (cameraOn && status.text.isBlank()) View.GONE else View.VISIBLE
        val b = lastBand
        signalBox.visibility = when { b != null -> View.VISIBLE; cameraOn -> View.GONE; else -> View.INVISIBLE }
        if (b != null) {
            signal.text = getString(R.string.signal_says, bandName(b)) + when { signalsSeen < 2 -> ""; trend > 0 -> "  ↑"; trend < 0 -> "  ↓"; else -> "  =" }
            val t = when { signalsSeen < 2 -> ""; trend > 0 -> getString(R.string.trend_up); trend < 0 -> getString(R.string.trend_down); else -> getString(R.string.trend_same) }
            signalWhy.text = meaning(b) + if (t.isNotEmpty()) "\n$t" else ""
            val tone = when (b) { Band.HOT -> 0xFFE9B3A6.toInt(); Band.WARM -> 0xFFF0D59B.toInt(); else -> 0xFFB9CDE6.toInt() }
            signalBox.background = card(if (cameraOn) (tone and 0x00FFFFFF) or 0xE0000000.toInt() else tone, Art.INK, 1, 14)
        }
        // travellers: who fits your last signal (help depends on the level)
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
                background = card(Art.CLASS_COLOR[p.cls.ordinal], Art.INK, 1, 16); setPadding(dp(12), dp(7), dp(12), dp(7)); typeface = Typeface.SERIF
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { rightMargin = dp(8) }
                setOnClickListener { _ -> aboutTraveller(p) }
            })
        }
        // you and your sac
        sac.removeAllViews()
        sac.addView(TextView(this).apply {
            text = "★ ${clsName(me.cls)}  ${hearts(me.vigor)}   "; textSize = 15f; setTextColor(Art.INK); typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD)
            setOnClickListener { _ -> tale.show(Tale.Card(clsName(me.cls), classDesc(me.cls), getString(R.string.hearts_why))) }
        })
        if (me.items.isEmpty()) sac.addView(text(14f).apply { text = getString(R.string.empty_sac); setTextColor(0xFF8A6A44.toInt()) })
        for (it in me.items.toList()) sac.addView(Button(this).apply {
            text = itemIcon(it) + " " + itemName(it); isAllCaps = false; textSize = 14f
            setBackgroundResource(R.drawable.btn_parchment); setTextColor(Art.INK)
            setOnClickListener { _ -> useItem(it) }
            setOnLongClickListener { _ -> tale.show(Tale.Card(itemName(it), itemDesc(it))); true }
        })
        // actions: dimmed when they can't be used now
        val reach = Types.reach(me.cls)
        restBtn.alpha = if (myTurn && !g.hasMoved()) 1f else 0.4f
        skillBtn.alpha = if (myTurn && g.skillReady(me)) 1f else 0.4f
        attackBtn.alpha = if (myTurn && g.opportunitiesLeft() > 0 && g.players.any { it.id != 0 && me.dist(it) <= reach }) 1f else 0.4f
        accuseBtn.alpha = if (myTurn && !me.accused && !g.marcoRevealed) 1f else 0.4f
        ticker.text = "📜 " + (lines.lastOrNull() ?: getString(R.string.long_press))
    }

    private fun aboutTraveller(p: Player) {
        val fit = fits[p.id]
        val state = when {
            p.id == board.revealedMarco -> getString(R.string.tr_marco)
            proven.contains(p.id) -> getString(R.string.tr_not)
            fit == true -> getString(R.string.tr_fits)
            fit == false -> getString(R.string.tr_nofit)
            else -> getString(R.string.tr_unknown)
        }
        tale.show(Tale.Card(name(p), state + "\n\n" + classDesc(p.cls), getString(R.string.tr_dist, me.dist(p))))
    }

    private fun showChronicle() {
        if (lines.isEmpty()) return
        tale.show(Tale.Card(getString(R.string.lbl_chronicle), lines.takeLast(10).reversed().joinToString("\n")))
    }

    private fun reachableSquares(): Set<Int> {
        val k = g.moveAllowance()
        val out = HashSet<Int>()
        for (r in 0 until g.n) for (c in 0 until g.n) if (g.reachable(me, r, c, k)) out.add(g.idx(r, c))
        return out
    }

    private fun accuseHint() {
        if (me.accused || g.marcoRevealed) return
        val still = g.players.filter { it.id != 0 && !proven.contains(it.id) && fits[it.id] == true }
        if (still.size == 1 && signalsSeen >= 3 && (tutorial || level == 0)) teach(6, R.string.ta_title, getString(R.string.ta, name(still[0])))
    }

    private fun showResult() {
        if (finished) return
        finished = true
        Camp.clear(this)
        val m = g.marco
        val head = when {
            g.winBy == WinBy.LANDING || g.winBy == WinBy.ACCUSATION ->
                if (g.finder?.id == 0) getString(R.string.you_win) else getString(R.string.rival_wins, name(g.finder!!))
            else -> getString(R.string.marco_escapes, g.rules.turnLimit)
        }
        val reveal = getString(R.string.reveal, name(m), g.casts, g.echoesSet, g.echoesDetected, g.trapsFromMarco[0], g.trapsFromLuck[0])
        diary.line(g.round, "OVER · $head · $reveal")
        board.revealedMarco = m.id
        board.invalidate()
        tale.show(Tale.Card(head, reveal, button = getString(R.string.again), onButton = { recreate() },
            button2 = getString(R.string.home), onButton2 = { finish() }))
    }

    /** The guided hunt: a card the first time each thing happens. */
    private fun teach(id: Int, title: Int, body: String) {
        if (!tutorial || coachShown.contains(id)) return
        coachShown.add(id)
        tale.show(Tale.Card(getString(R.string.tutorial_title) + " · " + getString(title), body, accent = Art.LAPIS, button = getString(R.string.got_it)))
    }

    private fun news(s: String) {
        lines.add(s)
        diary.line(g.round, s)
        if (::ticker.isInitialized) ticker.text = "📜 $s"
    }

    private fun say(s: String) { news(s) }

    private fun sq(r: Int, c: Int) = "(${r + 1},${c + 1})"
    private fun hearts(v: Int) = "♥".repeat(v) + "♡".repeat(maxOf(0, g.rules.vigorMax - v))
    private fun name(p: Player) = if (p.id == 0) getString(R.string.you) else "${p.id} ${clsName(p.cls)}"
    private fun clsName(c: Cls) = getString(when (c) { Cls.KNIGHT -> R.string.knight; Cls.TRAVELER -> R.string.traveler; Cls.MAGUS -> R.string.magus; Cls.MONGOL -> R.string.mongol })
    private fun classDesc(c: Cls) = getString(when (c) { Cls.KNIGHT -> R.string.knight_d; Cls.TRAVELER -> R.string.traveler_d; Cls.MAGUS -> R.string.magus_d; Cls.MONGOL -> R.string.mongol_d })
    private fun bandName(b: Band) = getString(when (b) { Band.HOT -> R.string.hot; Band.WARM -> R.string.warm; Band.COLD -> R.string.cold })
    private fun bandColor(b: Band) = when (b) { Band.HOT -> 0xFFD0503A.toInt(); Band.WARM -> 0xFFE0A030.toInt(); Band.COLD -> 0xFF3C7FE0.toInt() }
    private fun dirName(d: Dir) = getString(when (d) { Dir.N -> R.string.N; Dir.E -> R.string.E; Dir.S -> R.string.S; Dir.W -> R.string.W })
    private fun lmName(k: Int) = getString(intArrayOf(R.string.lm0, R.string.lm1, R.string.lm2, R.string.lm3, R.string.lm4, R.string.lm5)[k])
    private fun itemName(c: Content) = getString(when (c) {
        Content.LEAP -> R.string.leap; Content.SHIELD -> R.string.shield; Content.SPYGLASS -> R.string.spyglass
        Content.FALCON -> R.string.falcon_i; Content.COMPASS -> R.string.compass; Content.SERPENT -> R.string.serpent
        Content.DARKNESS -> R.string.darkness; Content.FREEZE -> R.string.freeze; Content.SCATTER -> R.string.scatter
        Content.ORACLE -> R.string.oracle; else -> R.string.nothing
    })
    private fun itemDesc(c: Content) = getString(when (c) {
        Content.LEAP -> R.string.d_leap; Content.SHIELD -> R.string.d_shield; Content.SPYGLASS -> R.string.d_spyglass
        Content.FALCON -> R.string.d_falcon; Content.COMPASS -> R.string.d_compass; else -> R.string.nothing
    })
    private fun itemIcon(c: Content) = when (c) {
        Content.LEAP -> "🦘"; Content.SHIELD -> "🛡"; Content.SPYGLASS -> "🔭"; Content.FALCON -> "🦅"; Content.COMPASS -> "🧭"; else -> ""
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    // ------------------------------------------------------------------ camera board (M2)

    private fun toggleCamera() {
        if (tale.busy()) return
        if (cameraOn) {
            cameraOn = false; pendingMove = -1
            camView?.stop(); arView?.pauseAr(); arView?.closeAr(); arView = null; arState = null; camView = null; preview = null
            stage.removeAllViews()
            stage.addView(board, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT, Gravity.CENTER))
            setHud(false)
            reattachOverlays()
            diary.line(g.round, "MAP board")
            refresh(); return
        }
        if (checkSelfPermission(android.Manifest.permission.CAMERA) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            tale.show(Tale.Card(getString(R.string.camera), getString(R.string.cam_why)))
            requestPermissions(arrayOf(android.Manifest.permission.CAMERA), 7); return
        }
        cameraOn = true
        setHud(true)
        val startLine = getString(R.string.your_start, me.r + 1, me.c + 1) + "\n\n"
        val full = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        if (ArState.available(this)) {
            // Google AR (certified phones such as the 8T): the board is pinned to the ground by two objects
            val st = ArState()
            val av = ArView(this, st)
            val cv = CameraBoardView(this).apply { game = g; humanId = 0; ar = st; onTap = { r, c -> tapped(r, c) } }
            av.onFrame = { cv.postInvalidate() }
            stage.removeAllViews()
            stage.addView(av, full)
            stage.addView(cv, FrameLayout.LayoutParams(full))
            arView = av; arState = st; camView = cv
            reattachOverlays()
            try { av.resumeAr() } catch (e: Throwable) { news("AR: ${e.message}") }
            diary.line(g.round, "CAMERA board (real AR)")
            tale.show(Tale.Card(getString(R.string.set_board), startLine + getString(R.string.ar_hint), getString(R.string.cam_safety)))
            refresh(); return
        }
        val pv = androidx.camera.view.PreviewView(this)
        val cv = CameraBoardView(this).apply { game = g; humanId = 0; onTap = { r, c -> tapped(r, c) } }
        stage.removeAllViews()
        stage.addView(pv, full)
        stage.addView(cv, FrameLayout.LayoutParams(full))
        preview = pv; camView = cv
        reattachOverlays()
        val future = androidx.camera.lifecycle.ProcessCameraProvider.getInstance(this)
        future.addListener({
            try {
                val provider = future.get()
                val p = androidx.camera.core.Preview.Builder().build().also { it.setSurfaceProvider(pv.surfaceProvider) }
                provider.unbindAll()
                provider.bindToLifecycle(this, androidx.camera.core.CameraSelector.DEFAULT_BACK_CAMERA, p)
            } catch (e: Exception) { news("Camera: ${e.message}") }
        }, mainExecutor)
        cv.start()
        diary.line(g.round, "CAMERA board (camera-lite)")
        tale.show(Tale.Card(getString(R.string.set_board), startLine + getString(R.string.cam_hint), getString(R.string.cam_safety)))
        refresh()
    }

    /** The banner and the camera buttons always float above the board or the camera picture. */
    private fun reattachOverlays() {
        (banner.parent as? android.view.ViewGroup)?.removeView(banner)
        (camRow.parent as? android.view.ViewGroup)?.removeView(camRow)
        val host = if (cameraOn) midSlot else stage
        host.addView(banner, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP).apply { topMargin = dp(8); leftMargin = dp(8); rightMargin = dp(8) })
        host.addView(camRow, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM).apply { bottomMargin = dp(10) })
    }

    /**
     * The floating screen (founder, Nov 8): with the camera on, the picture fills the whole screen and
     * everything else floats over it, see-through, so nothing is ever out of sight. The stage moves behind
     * the column of panels and the slot it left in the column stays empty, so taps there reach the camera board.
     */
    private fun setHud(on: Boolean) {
        val inColumn = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
        if (on) {
            val i = hudRoot.indexOfChild(stage)
            hudRoot.removeView(stage)
            hudRoot.addView(midSlot, i, inColumn)
            hudFrame.addView(stage, 0, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        } else {
            val i = hudRoot.indexOfChild(midSlot)
            hudRoot.removeView(midSlot)
            hudFrame.removeView(stage)
            hudRoot.addView(stage, i, inColumn)
        }
        val glass = 0xE0FBF3DF.toInt()
        status.background = if (on) card(glass, Art.INK, 1, 10) else null
        status.setPadding(dp(8), if (on) dp(4) else 0, dp(8), if (on) dp(4) else 0)
        ticker.background = card(if (on) glass else 0xFFFBF3DF.toInt(), 0xFFC9B892.toInt(), 1, 10)
        ticker.setPadding(dp(12), dp(8), dp(12), dp(8))
        sacScroll.background = if (on) card(glass, 0xFFC9B892.toInt(), 1, 10) else null
        sacScroll.setPadding(if (on) dp(8) else 0, 0, if (on) dp(8) else 0, 0)
        // compact over the camera: one line for the signal; tap it for the meaning
        signalWhy.minLines = if (on) 0 else 3
        signalWhy.visibility = if (on) View.GONE else View.VISIBLE
        ui.removeCallbacks(guideTick)
        if (on) ui.post(guideTick)
    }

    private fun placeMiniMap() {
        val side = dp(if (miniBig) 240 else 120)
        miniMap.layoutParams = FrameLayout.LayoutParams(side, side, Gravity.TOP or Gravity.END).apply { topMargin = dp(8) }
    }

    /** Says where you are and where to go, from the moment the camera opens. */
    private fun updateGuide() {
        val st = arState
        val target = if (pendingMove >= 0) pendingMove else g.idx(me.r, me.c)
        val tr = target / g.n + 1; val tc = target % g.n + 1
        var hereIdx = -1
        guide.text = when {
            st == null -> getString(R.string.guide_lite, tr, tc)
            !st.tracking -> getString(R.string.ar_look)
            !st.ready && st.waitingCorner2 -> getString(R.string.guide_b, String.format(java.util.Locale.US, "%.1f", st.fromCorner1()))
            !st.ready -> getString(R.string.guide_a)
            else -> {
                val h = st.squareHere(g.n)
                if (h != null) hereIdx = g.idx(h[0], h[1])
                when {
                    h == null -> getString(R.string.guide_outside, tr, tc)
                    hereIdx == target -> getString(R.string.guide_on, tr, tc)
                    else -> getString(R.string.guide_walk, h[0] + 1, h[1] + 1, tr, tc)
                }
            }
        }
        if (miniMap.here != hereIdx) { miniMap.here = hereIdx; miniMap.invalidate() }
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<String>, results: IntArray) {
        super.onRequestPermissionsResult(code, perms, results)
        if (code == 7 && results.isNotEmpty() && results[0] == android.content.pm.PackageManager.PERMISSION_GRANTED) toggleCamera()
        else if (code == 7) tale.say(getString(R.string.cam_denied), 2400)
    }

    private fun setBoard() {
        val st = arState
        if (st == null) { camView?.setHeadingHere(); tale.say(getString(R.string.board_set), 2400); diary.line(g.round, "SET BOARD (heading)"); return }
        if (!st.tracking) { tale.say(getString(R.string.ar_look), 2400); return }
        when (st.mark(g.n, camView?.eyeM ?: 1.4f)) {
            0 -> { tale.say(getString(R.string.ar_too_close, (st.fromCorner1() * 100).toInt()), 3500); diary.line(g.round, "SET BOARD refused, ${(st.fromCorner1() * 100).toInt()} cm from corner 1") }
            1 -> { tale.say(getString(R.string.ar_mark2), 3500); diary.line(g.round, "SET BOARD corner 1") }
            else -> { camView?.squareM = st.s; tale.say(getString(R.string.ar_ready, (st.s * 100).toInt()), 2600); diary.line(g.round, "SET BOARD done, squares ${(st.s * 100).toInt()} cm") }
        }
        camView?.invalidate()
    }

    /** Walk mode: the player stands on the picked square and taps "I'm here"; then the move counts. */
    private fun arrive() {
        if (pendingMove < 0 || !turnOpen || tale.busy()) return
        val r = pendingMove / g.n; val c = pendingMove % g.n
        val st = arState
        val here = st?.squareHere(g.n)
        if (st != null && here != null && (Math.abs(here[0] - r) + Math.abs(here[1] - c)) > 0 && !hereWarned) {
            hereWarned = true
            tale.say(getString(R.string.ar_elsewhere, here[0] + 1, here[1] + 1), 3500); return
        }
        hereWarned = false
        pendingMove = -1
        landedAt = intArrayOf(r, c)
        act("walked to ${sq(r, c)} (I'm here; phone said ${here?.let { sq(it[0], it[1]) } ?: "-"})") { g.move(me, r, c) }
        endHumanTurn()
    }

    override fun onPause() {
        super.onPause(); camView?.stop(); arView?.pauseAr()
        if (!finished && !g.over) Camp.write(this, Save.write(g, ai) + uiState())
    }

    // ------------------------------------------------------------------ camp save (pause today, continue tomorrow)

    private fun uiState(): String {
        val sb = StringBuilder()
        sb.append("ui.level=").append(level).append('\n').append("ui.tutorial=").append(tutorial).append('\n')
        sb.append("ui.band=").append(lastBand?.ordinal ?: -1).append('\n').append("ui.seen=").append(signalsSeen).append('\n')
        sb.append("ui.proven=").append(proven.joinToString(",")).append('\n')
        sb.append("ui.fits=").append(fits.entries.joinToString(",") { "${it.key}:${if (it.value) 1 else 0}" }).append('\n')
        sb.append("ui.lines=").append(lines.takeLast(6).joinToString("\u0001").replace("\n", " ")).append('\n')
        return sb.toString()
    }

    private fun resumeCamp(): Boolean {
        val text = Camp.read(this) ?: return false
        return try {
            val o = Save.read(text)
            g = o[0] as Game; ai = o[1] as Ai
            me = g.players[0]
            g.listeners.add(this)
            val ui = HashMap<String, String>()
            for (line in text.split("\n")) if (line.startsWith("ui.")) { val i = line.indexOf('='); ui[line.substring(3, i)] = line.substring(i + 1) }
            level = ui["level"]?.toIntOrNull() ?: level; tutorial = ui["tutorial"] == "true"
            val b = ui["band"]?.toIntOrNull() ?: -1; lastBand = if (b >= 0) Band.values()[b] else null
            signalsSeen = ui["seen"]?.toIntOrNull() ?: 0
            ui["proven"]?.split(",")?.mapNotNull { it.toIntOrNull() }?.let { proven.addAll(it) }
            ui["fits"]?.split(",")?.forEach { kv -> val p = kv.split(":"); if (p.size == 2) fits[p[0].toInt()] = p[1] == "1" }
            ui["lines"]?.split("\u0001")?.filter { it.isNotBlank() }?.let { lines.addAll(it) }
            lines.add(getString(R.string.camp_resumed))
            // a turn that was already rolled stays open
            if (g.current().human && g.moveAllowance() > 0 && !g.hasMoved()) { turnOpen = true; mode = Mode.MOVE }
            true
        } catch (e: Exception) { Camp.clear(this); false }
    }
    override fun onResume() {
        super.onResume()
        if (!cameraOn) return
        if (arView != null) try { arView?.resumeAr() } catch (e: Throwable) { news("AR: ${e.message}") } else camView?.start()
    }

    override fun onDestroy() { finished = true; ui.removeCallbacksAndMessages(null); camView?.stop(); arView?.closeAr(); super.onDestroy() }
}
