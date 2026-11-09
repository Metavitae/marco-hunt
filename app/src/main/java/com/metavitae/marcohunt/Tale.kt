package com.metavitae.marcohunt

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/**
 * The tale cards (founder, Nov 7: "Fighting, using resources… I couldn't know why, when or the results").
 * Every event that touches the player is shown on a card: what happened, why, and what it does to you.
 * Cards queue up; the game waits while one is open. A banner (top of the board) tells the other
 * travellers' turns without stopping the game.
 */
class Tale(private val act: Activity, private val layer: FrameLayout, private val banner: TextView) {

    class Card(val title: String, val what: String, val why: String = "", val img: Bitmap? = null,
               val accent: Int = Art.GOLD, val button: String = "")

    private val queue = ArrayDeque<Card>()
    private var open = false
    var onShow: ((Card) -> Unit)? = null

    fun busy() = open || queue.isNotEmpty()

    fun show(c: Card) { queue.addLast(c); if (!open) next() }

    private fun dp(v: Int) = (v * act.resources.displayMetrics.density).toInt()

    private fun next() {
        val c = queue.removeFirstOrNull()
        if (c == null) { open = false; layer.visibility = View.GONE; layer.removeAllViews(); return }
        open = true
        onShow?.invoke(c)
        layer.removeAllViews()
        layer.visibility = View.VISIBLE
        layer.setBackgroundColor(0x99201408.toInt())
        layer.isClickable = true
        val box = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply { setColor(0xFFFBF3DF.toInt()); cornerRadius = dp(18).toFloat(); setStroke(dp(3), c.accent) }
            setPadding(dp(22), dp(20), dp(22), dp(18))
            elevation = dp(8).toFloat()
        }
        if (c.img != null) box.addView(ImageView(act).apply {
            setImageBitmap(c.img); adjustViewBounds = true; maxHeight = dp(220)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(12) }
        })
        box.addView(TextView(act).apply {
            text = c.title; textSize = 24f; setTextColor(Art.INK); typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD)
            gravity = Gravity.CENTER; setPadding(0, 0, 0, dp(10))
        })
        box.addView(TextView(act).apply { text = c.what; textSize = 18f; setTextColor(Art.INK); typeface = Typeface.SERIF; setLineSpacing(dp(3).toFloat(), 1f) })
        if (c.why.isNotEmpty()) box.addView(TextView(act).apply {
            text = c.why; textSize = 15f; setTextColor(0xFF8A6A44.toInt()); typeface = Typeface.create(Typeface.SERIF, Typeface.ITALIC)
            setLineSpacing(dp(2).toFloat(), 1f); setPadding(0, dp(12), 0, 0)
        })
        box.addView(Button(act).apply {
            text = c.button.ifEmpty { act.getString(R.string.go_on) }; isAllCaps = false; textSize = 18f
            setBackgroundResource(R.drawable.btn_parchment); setTextColor(Art.INK); typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(16) }
            setOnClickListener { close(box) }
        })
        val lp = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER)
        lp.leftMargin = dp(20); lp.rightMargin = dp(20)
        layer.addView(box, lp)
        // enter: a short rise and fade (ease-out, 220 ms), so the card reads as "new news"
        box.alpha = 0f; box.translationY = dp(24).toFloat(); box.scaleX = 0.96f; box.scaleY = 0.96f
        box.animate().alpha(1f).translationY(0f).scaleX(1f).scaleY(1f).setDuration(220).setInterpolator(DecelerateInterpolator()).start()
    }

    private fun close(box: View) {
        box.isEnabled = false
        box.animate().alpha(0f).translationY(-dp(12).toFloat()).setDuration(140).withEndAction { next() }.start()
    }

    /** Non-blocking line at the top of the board, for the other travellers' turns and small news. */
    fun say(text: String, holdMs: Long = 1800) {
        banner.text = text
        banner.animate().cancel()
        if (banner.visibility != View.VISIBLE) { banner.alpha = 0f; banner.visibility = View.VISIBLE }
        banner.animate().alpha(1f).setDuration(150).start()
        banner.removeCallbacks(hide)
        banner.postDelayed(hide, holdMs)
    }

    private val hide = Runnable { banner.animate().alpha(0f).setDuration(250).withEndAction { banner.visibility = View.GONE }.start() }
}
