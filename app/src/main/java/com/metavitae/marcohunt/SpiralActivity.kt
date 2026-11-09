package com.metavitae.marcohunt

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.app.Activity
import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.animation.PathInterpolator
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.metavitae.marcohunt.engine.Spiral
import com.metavitae.marcohunt.engine.Types.Band

/**
 * The spiral race, flat on the screen (founder, Nov 9): no camera, so the race, the passes and the robbing
 * can be felt cheaply. One screen, no scrolling. You are traveller 0; three rivals play by themselves.
 */
class SpiralActivity : Activity() {

    override fun attachBaseContext(base: Context) = super.attachBaseContext(Lang.wrap(base))

    private lateinit var g: Spiral
    private lateinit var board: SpiralBoardView
    private lateinit var tale: Tale
    private lateinit var diary: Diary
    private lateinit var status: TextView
    private lateinit var you: TextView
    private lateinit var die: TextView
    private lateinit var rollBtn: Button
    private val chips = ArrayList<TextView>()
    private val ui = Handler(Looper.getMainLooper())
    private var running: ValueAnimator? = null
    private var gone = false
    private var reach: Spiral.Reach? = null
    private var waitingForSquare = false
    private var passExplained = false
    private var rivalTreasureTold = false

    private val walkEase = PathInterpolator(0.77f, 0f, 0.175f, 1f)
    private val settleEase = PathInterpolator(0.23f, 1f, 0.32f, 1f)

    private fun name(id: Int) = getString(NAMES[id])
    private fun land(ring: Int) = getString(LANDS[ring])
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun card(color: Int, stroke: Int = Art.INK, w: Int = 1, radius: Int = 10) =
        GradientDrawable().apply { setColor(color); cornerRadius = dp(radius).toFloat(); setStroke(dp(w), stroke) }
    private fun sound(b: Band) { if (!Signal.quiet(this)) Signal.play(b) }

    override fun onCreate(saved: Bundle?) {
        super.onCreate(saved)
        Signal.init(this)
        val frame = FrameLayout(this).apply { setBackgroundColor(Art.PARCHMENT) }
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(8), dp(8), dp(8), dp(12)) }
        frame.addView(col, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))

        val rivals = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        for (id in 1..3) {
            val chip = TextView(this).apply {
                textSize = 13f; setTextColor(Art.INK); gravity = Gravity.CENTER; typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD)
                setPadding(dp(4), dp(8), dp(4), dp(8)); background = card(SpiralBoardView.COLOR[id])
            }
            chips.add(chip)
            rivals.addView(chip, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(6) })
        }
        rivals.addView(TextView(this).apply {
            text = "?"; textSize = 18f; setTextColor(Art.INK); gravity = Gravity.CENTER; typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD)
            background = card(0xFFFBF3DF.toInt()); setOnClickListener { howToPlay() }
        }, LinearLayout.LayoutParams(dp(44), dp(44)))
        col.addView(rivals)

        // the board takes all the width; the banner floats over its top edge
        val stage = FrameLayout(this)
        board = SpiralBoardView(this)
        stage.addView(board, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        val banner = TextView(this).apply {
            textSize = 15f; setTextColor(0xFFFBF3DF.toInt()); typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD); gravity = Gravity.CENTER
            setPadding(dp(12), dp(8), dp(12), dp(8)); background = card(0xEE3A2412.toInt(), 0xFF3A2412.toInt()); visibility = View.GONE
        }
        stage.addView(banner, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP).apply { topMargin = dp(10); marginStart = dp(16); marginEnd = dp(16) })
        col.addView(stage, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f).apply { topMargin = dp(8) })

        you = TextView(this).apply { textSize = 16f; setTextColor(Art.INK); typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD); setPadding(dp(4), dp(10), dp(4), 0) }
        col.addView(you)
        status = TextView(this).apply { textSize = 17f; setTextColor(Art.INK); typeface = Typeface.SERIF; minLines = 2; setPadding(dp(4), dp(4), dp(4), dp(8)) }
        col.addView(status)

        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        die = TextView(this).apply {
            textSize = 28f; setTextColor(0xFFFFFFFF.toInt()); gravity = Gravity.CENTER; typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD)
            background = card(Art.MADDER, 0xFF7A1F1B.toInt(), 2, 12); text = "–"
        }
        row.addView(die, LinearLayout.LayoutParams(dp(60), dp(60)).apply { marginEnd = dp(12) })
        rollBtn = Button(this).apply {
            textSize = 20f; isAllCaps = false; setBackgroundResource(R.drawable.btn_parchment); setTextColor(Art.INK)
            typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD); setOnClickListener { roll() }
        }
        row.addView(rollBtn, LinearLayout.LayoutParams(0, dp(60), 1f))
        col.addView(row)

        val layer = FrameLayout(this).apply { visibility = View.GONE }
        frame.addView(layer, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        setContentView(frame)

        tale = Tale(this, layer, banner)
        board.onTap = { tapped(it) }
        board.onLongTap = { describe(it) }
        newGame()
    }

    private fun newGame() {
        val seed = System.nanoTime()
        g = Spiral(seed, arrayOf(Spiral.Kind.HUMAN, Spiral.Kind.EASY, Spiral.Kind.EASY, Spiral.Kind.EASY))
        board.game = g; board.reach = null; board.anim.clear(); board.invalidate()
        passExplained = false; rivalTreasureTold = false; waitingForSquare = false
        diary = Diary(this)
        diary.start("spiral race · seed $seed · passes " + g.passes.joinToString(" ") { "${it[0]}-${it[1]}" })
        val prefs = getSharedPreferences("marcohunt", Context.MODE_PRIVATE)
        if (intent.getBooleanExtra("howto", false) || !prefs.getBoolean("spiral_intro", false)) {
            prefs.edit().putBoolean("spiral_intro", true).apply()
            intent.removeExtra("howto")
            howToPlay()
        }
        afterCards { nextTurn() }
    }

    private fun howToPlay() {
        tale.show(Tale.Card(getString(R.string.sp_h1_t), getString(R.string.sp_h1), getString(R.string.sp_h1w)))
        tale.show(Tale.Card(getString(R.string.sp_h2_t), getString(R.string.sp_h2), getString(R.string.sp_h2w)))
        tale.show(Tale.Card(getString(R.string.sp_h3_t), getString(R.string.sp_h3), getString(R.string.sp_h3w), accent = Art.MADDER))
        tale.show(Tale.Card(getString(R.string.sp_h4_t), getString(R.string.sp_h4), getString(R.string.sp_h4w), accent = Art.MADDER))
    }

    private fun afterCards(then: () -> Unit) {
        if (gone) return
        if (tale.busy()) ui.postDelayed({ afterCards(then) }, 100) else then()
    }

    private fun hud() {
        val me = g.ps[0]
        for (id in 1..3) {
            val p = g.ps[id]
            chips[id - 1].text = "${name(id)}  " + when {
                p.frozen > 0 -> "❄"
                p.chest -> "💰"
                else -> "🎁${p.gifts}"
            }
        }
        val gifts = "🎁".repeat(me.gifts.coerceAtMost(3)) + "▫".repeat((3 - me.gifts).coerceAtLeast(0))
        val carry = when {
            me.chest -> "💰"
            me.audience -> getString(R.string.sp_robbed_short)
            else -> gifts
        }
        you.text = "★ ${land(Spiral.RING[me.at])} · $carry" + (if (me.tablets > 0) " · 🥇" else "") + (if (me.bonus > 0) " · 🐎" else "")
        status.text = when {
            me.chest -> getString(R.string.sp_goal_home)
            me.audience -> getString(R.string.sp_goal_back)
            me.gifts >= 3 -> getString(R.string.sp_goal_khan)
            else -> getString(R.string.sp_goal_gifts, me.gifts)
        }
    }

    private fun nextTurn() {
        if (gone || g.over()) return
        hud()
        if (g.current == 0) yourTurn() else rivalTurn()
    }

    private fun yourTurn() {
        if (g.spendFrozen()) {
            diary.line(g.round, "YOU frozen, turn lost")
            tale.show(Tale.Card(getString(R.string.sp_frozen_t), getString(R.string.sp_frozen), accent = 0xFF6FA8DC.toInt()))
            afterCards { endTurn() }
            return
        }
        rollBtn.isEnabled = true
        rollBtn.text = getString(R.string.sp_roll)
    }

    private fun roll() {
        rollBtn.isEnabled = false
        val value = g.rollDie()
        var k = 0
        val tumble = object : Runnable {
            override fun run() {
                if (gone) return
                if (k++ < 5) { die.text = (1..6).random().toString(); ui.postDelayed(this, 60); return }
                die.text = value.toString()
                die.scaleX = 0.92f; die.scaleY = 0.92f
                die.animate().scaleX(1f).scaleY(1f).setDuration(160).setInterpolator(settleEase).start()
                val r = g.reach()
                reach = r; board.reach = r; board.invalidate(); waitingForSquare = true
                status.text = getString(R.string.sp_pick, value)
                rollBtn.text = getString(R.string.sp_tap_square)
                diary.line(g.round, "YOU roll $value at ${g.ps[0].at}, gifts ${g.ps[0].gifts}, treasure ${g.ps[0].chest}")
            }
        }
        ui.post(tumble)
    }

    private fun tapped(i: Int) {
        if (!waitingForSquare || tale.busy()) return
        val r = reach ?: return
        if (!r.any(i)) { tale.say(getString(R.string.sp_not_there), 1500); return }
        if (r.risky[i] && !passExplained) {
            passExplained = true
            tale.show(Tale.Card(getString(R.string.sp_cross_t), getString(R.string.sp_cross), getString(R.string.sp_cross_w), accent = Art.MADDER,
                button = getString(R.string.sp_cross_yes), onButton = { tale.dismiss(); go(i) },
                button2 = getString(R.string.sp_cross_no), onButton2 = { tale.dismiss() }))
            return
        }
        go(i)
    }

    private fun go(i: Int) {
        val r = reach ?: return
        waitingForSquare = false; reach = null; board.reach = null; board.invalidate()
        rollBtn.text = getString(R.string.sp_wait)
        val from = g.ps[0].at
        val w = g.move(i, r)
        diary.line(g.round, "YOU chose $i ${if (r.risky[i]) "through a pass" else "by road"}, now at ${g.ps[0].at}")
        walk(0, from, w, 170L) {
            for (e in w.events) tellYours(e)
            hud()
            afterCards { endTurn() }
        }
    }

    private fun rivalTurn() {
        val id = g.current
        if (g.spendFrozen()) {
            diary.line(g.round, "${name(id)} frozen")
            tale.say(getString(R.string.sp_r_frozen, name(id)), 900)
            ui.postDelayed({ endTurn() }, 950)
            return
        }
        val value = g.rollDie()
        die.text = value.toString()
        val r = g.reach()
        val from = g.ps[id].at
        val dest = g.choose(r)
        val w = g.move(dest, r)
        diary.line(g.round, "${name(id)} roll $value, ${from} to ${g.ps[id].at}${if (r.risky[dest]) " through a pass" else ""}, gifts ${g.ps[id].gifts}, treasure ${g.ps[id].chest}")
        tale.say(getString(R.string.sp_r_rolls, name(id), value), 900)
        walk(id, from, w, 100L) {
            for (e in w.events) tellRivals(e)
            hud()
            ui.postDelayed({ afterCards { endTurn() } }, 380)
        }
    }

    private fun endTurn() {
        if (g.over()) return
        g.endTurn()
        nextTurn()
    }

    private fun glide(id: Int, from: Int, to: Int, ms: Long, done: () -> Unit) {
        val fr = Spiral.ROW[from].toFloat(); val fc = Spiral.COL[from].toFloat()
        val tr = Spiral.ROW[to].toFloat(); val tc = Spiral.COL[to].toFloat()
        val a = ValueAnimator.ofFloat(0f, 1f)
        a.duration = ms
        a.interpolator = walkEase
        a.addUpdateListener {
            val t = it.animatedValue as Float
            board.anim[id] = floatArrayOf(fr + (tr - fr) * t, fc + (tc - fc) * t)
            board.invalidate()
        }
        a.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(x: Animator) { if (!gone) done() }
        })
        running = a
        a.start()
    }

    /** Walks the piece square by square, then shows a failed pass: the board shakes and the piece is thrown back. */
    private fun walk(id: Int, from: Int, w: Spiral.Walk, stepMs: Long, done: () -> Unit) {
        fun finish() { board.anim.remove(id); board.invalidate(); done() }
        fun step(k: Int, prev: Int) {
            if (k < w.squares.size) { glide(id, prev, w.squares[k], stepMs) { step(k + 1, w.squares[k]) }; return }
            if (w.thrownTo < 0) { finish(); return }
            shake()
            glide(id, prev, w.thrownTo, 320L) { finish() }
        }
        step(0, from)
    }

    private fun shake() {
        val d = dp(7).toFloat()
        board.animate().translationX(d).setDuration(50).withEndAction {
            board.animate().translationX(-d).setDuration(70).withEndAction {
                board.animate().translationX(0f).setDuration(60).start()
            }.start()
        }.start()
    }

    private fun peril(ring: Int) = getString(PERILS[ring.coerceIn(0, 3)])
    private fun perilText(ring: Int) = getString(PERIL_TEXTS[ring.coerceIn(0, 3)])

    private fun tellYours(e: Spiral.Event) {
        diary.line(g.round, "YOU ${e.type} at ${e.at}" + (if (e.other >= 0) " with ${name(e.other)}" else ""))
        when (e.type) {
            Spiral.Ev.PASS_OK -> { sound(Band.WARM); tale.say(getString(R.string.sp_pass_ok), 1800) }
            Spiral.Ev.PASS_SAVED -> { sound(Band.WARM); tale.show(Tale.Card(peril(e.ring), perilText(e.ring), getString(R.string.sp_pass_saved), accent = Art.GOLD)) }
            Spiral.Ev.PASS_FAIL -> {
                sound(Band.COLD); Signal.buzz(this, Band.COLD)
                tale.show(Tale.Card(peril(e.ring), perilText(e.ring), getString(R.string.sp_pass_fail), accent = Art.MADDER))
            }
            Spiral.Ev.GIFT -> { sound(Band.WARM); tale.show(Tale.Card(getString(GIFTS[e.ring]), getString(R.string.sp_gift, g.ps[0].gifts), getString(R.string.sp_gift_w))) }
            Spiral.Ev.TABLET -> { sound(Band.WARM); tale.show(Tale.Card(getString(R.string.sp_tablet_t), getString(R.string.sp_tablet), accent = Art.GOLD)) }
            Spiral.Ev.HORSE -> { sound(Band.WARM); tale.show(Tale.Card(getString(R.string.sp_horse_t), getString(R.string.sp_horse), accent = Art.GOLD)) }
            Spiral.Ev.TREASURE -> { sound(Band.HOT); tale.show(Tale.Card(getString(R.string.sp_treasure_t), getString(R.string.sp_treasure), getString(R.string.sp_treasure_w), accent = Art.GOLD)) }
            Spiral.Ev.ROB -> { sound(Band.HOT); tale.show(Tale.Card(getString(R.string.sp_rob_t), getString(R.string.sp_rob, name(e.other)), accent = Art.GOLD)) }
            Spiral.Ev.ROB_MISS -> { sound(Band.COLD); tale.show(Tale.Card(getString(R.string.sp_miss_t), getString(R.string.sp_miss, name(e.other)), accent = Art.MADDER)) }
            Spiral.Ev.ROB_SAVED -> { sound(Band.COLD); tale.show(Tale.Card(getString(R.string.sp_miss_t), getString(R.string.sp_saved_them, name(e.other)), accent = Art.MADDER)) }
            Spiral.Ev.WIN -> over(true)
        }
    }

    private fun tellRivals(e: Spiral.Event) {
        val who = name(e.who)
        diary.line(g.round, "$who ${e.type} at ${e.at}" + (if (e.other >= 0) " with ${name(e.other)}" else ""))
        when (e.type) {
            Spiral.Ev.PASS_OK -> tale.say(getString(R.string.sp_r_pass_ok, who), 1500)
            Spiral.Ev.PASS_SAVED -> tale.say(getString(R.string.sp_r_pass_ok, who), 1500)
            Spiral.Ev.PASS_FAIL -> tale.say(getString(R.string.sp_r_pass_fail, who, peril(e.ring)), 1500)
            Spiral.Ev.GIFT -> tale.say(getString(R.string.sp_r_gift, who), 1200)
            Spiral.Ev.TABLET, Spiral.Ev.HORSE -> tale.say(getString(R.string.sp_r_item, who), 1200)
            Spiral.Ev.TREASURE ->
                if (!rivalTreasureTold) {
                    rivalTreasureTold = true
                    tale.show(Tale.Card(getString(R.string.sp_r_treasure_t, who), getString(R.string.sp_r_treasure), accent = Art.MADDER))
                } else tale.say(getString(R.string.sp_r_treasure_again, who), 1500)
            Spiral.Ev.ROB ->
                if (e.other == 0) {
                    sound(Band.COLD); Signal.buzz(this, Band.COLD)
                    tale.show(Tale.Card(getString(R.string.sp_robbed_t), getString(R.string.sp_robbed, who), getString(R.string.sp_robbed_w), accent = Art.MADDER))
                } else tale.say(getString(R.string.sp_r_rob, who, name(e.other)), 1800)
            Spiral.Ev.ROB_MISS ->
                if (e.other == 0) tale.show(Tale.Card(getString(R.string.sp_escaped_t), getString(R.string.sp_escaped, who), accent = Art.VERDIGRIS))
                else tale.say(getString(R.string.sp_r_miss, who, name(e.other)), 1500)
            Spiral.Ev.ROB_SAVED ->
                if (e.other == 0) tale.show(Tale.Card(getString(R.string.sp_escaped_t), getString(R.string.sp_tablet_saved, who), accent = Art.GOLD))
                else tale.say(getString(R.string.sp_r_miss, who, name(e.other)), 1500)
            Spiral.Ev.WIN -> over(false)
        }
    }

    private fun over(won: Boolean) {
        diary.line(g.round, "OVER, winner ${name(g.winner)} after ${g.round} rounds")
        hud()
        rollBtn.isEnabled = false
        sound(if (won) Band.HOT else Band.COLD)
        tale.show(Tale.Card(
            if (won) getString(R.string.sp_won_t) else getString(R.string.sp_lost_t, name(g.winner)),
            if (won) getString(R.string.sp_won, g.round) else getString(R.string.sp_lost, g.round),
            accent = if (won) Art.GOLD else Art.MADDER,
            button = getString(R.string.sp_again), onButton = { tale.dismiss(); ui.postDelayed({ newGame() }, 200) },
            button2 = getString(R.string.sp_home), onButton2 = { finish() }))
    }

    private fun describe(i: Int) {
        val what = when {
            i == Spiral.VENICE -> getString(R.string.sp_d_venice)
            i == Spiral.KHAN -> getString(R.string.sp_d_khan)
            g.gift[i] -> if (g.mayTakeGift(g.ps[0], i)) getString(R.string.sp_d_gift) else getString(R.string.sp_d_gift_no)
            g.tablet[i] -> getString(R.string.sp_tablet_t)
            g.horse[i] -> getString(R.string.sp_horse_t)
            else -> ""
        }
        tale.say(land(Spiral.RING[i]) + (if (what.isEmpty()) "" else ": $what"), 2200)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (tale.busy()) return
        tale.show(Tale.Card(getString(R.string.sp_leave_t), getString(R.string.sp_leave),
            button = getString(R.string.sp_leave_no), onButton = { tale.dismiss() },
            button2 = getString(R.string.sp_leave_yes), onButton2 = { finish() }))
    }

    override fun onDestroy() {
        gone = true
        ui.removeCallbacksAndMessages(null)
        running?.cancel()
        super.onDestroy()
    }

    companion object {
        val NAMES = intArrayOf(R.string.sp_you, R.string.sp_friar, R.string.sp_envoy, R.string.sp_courier)
        val LANDS = intArrayOf(R.string.sp_land0, R.string.sp_land1, R.string.sp_land2, R.string.sp_land3, R.string.sp_land4)
        val GIFTS = intArrayOf(R.string.sp_gift0, R.string.sp_gift1, R.string.sp_gift2, R.string.sp_gift3)
        val PERILS = intArrayOf(R.string.sp_peril0, R.string.sp_peril1, R.string.sp_peril2, R.string.sp_peril3)
        val PERIL_TEXTS = intArrayOf(R.string.sp_peril0_d, R.string.sp_peril1_d, R.string.sp_peril2_d, R.string.sp_peril3_d)
    }
}
