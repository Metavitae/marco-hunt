package com.metavitae.marcohunt

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast

/** Home: Solo Hunt, the guided hunt, (host and join later), About, language. */
class MainActivity : Activity() {

    override fun attachBaseContext(base: Context) = super.attachBaseContext(Lang.wrap(base))

    override fun onCreate(saved: Bundle?) {
        super.onCreate(saved)
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setBackgroundColor(Art.PARCHMENT)
            setPadding(dp(24), dp(48), dp(24), dp(24))
        }
        col.addView(TextView(this).apply {
            text = getString(R.string.app_name); textSize = 40f; setTextColor(Art.INK)
            typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD); gravity = Gravity.CENTER
        })
        col.addView(TextView(this).apply {
            text = getString(R.string.tagline); textSize = 17f; setTextColor(Art.INK)
            typeface = Typeface.SERIF; gravity = Gravity.CENTER; setPadding(0, dp(8), 0, dp(32))
        })
        col.addView(button(R.string.solo) { soloSetup(false) })
        col.addView(button(R.string.tutorial) { startGame(0, 10, 0, 3, true) })
        col.addView(button(R.string.host, enabled = false) { soon() })
        col.addView(button(R.string.join, enabled = false) { soon() })
        col.addView(button(R.string.about) {
            AlertDialog.Builder(this).setTitle(R.string.app_name).setMessage(R.string.about_text).setPositiveButton("OK", null).show()
        })
        col.addView(button(R.string.language) { Lang.toggle(this); recreate() })
        setContentView(ScrollView(this).apply { setBackgroundColor(Art.PARCHMENT); addView(col) })
    }

    private fun soon() = Toast.makeText(this, R.string.soon, Toast.LENGTH_SHORT).show()

    private fun button(res: Int, enabled: Boolean = true, onClick: () -> Unit) = Button(this).apply {
        text = getString(res); textSize = 18f; isAllCaps = false; isEnabled = enabled
        if (!enabled) text = "${getString(res)} — ${getString(R.string.soon)}"
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) }
    }

    private fun soloSetup(tutorial: Boolean) {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(8), dp(20), 0) }
        fun label(res: Int) = TextView(this).apply { text = getString(res); textSize = 16f; setTypeface(typeface, Typeface.BOLD); setPadding(0, dp(12), 0, dp(4)) }
        box.addView(label(R.string.choose_class))
        val classes = intArrayOf(R.string.knight, R.string.traveler, R.string.magus, R.string.mongol)
        val descs = intArrayOf(R.string.knight_d, R.string.traveler_d, R.string.magus_d, R.string.mongol_d)
        val clsGroup = RadioGroup(this)
        for (k in classes.indices) clsGroup.addView(RadioButton(this).apply {
            id = 100 + k; text = "${getString(classes[k])} — ${getString(descs[k])}"; textSize = 14f
        })
        clsGroup.check(100 + (0..3).random())
        box.addView(clsGroup)
        box.addView(label(R.string.board))
        val boardGroup = RadioGroup(this).apply { orientation = RadioGroup.HORIZONTAL }
        boardGroup.addView(RadioButton(this).apply { id = 200; text = getString(R.string.board10) })
        boardGroup.addView(RadioButton(this).apply { id = 201; text = getString(R.string.board15) })
        boardGroup.check(200)
        box.addView(boardGroup)
        box.addView(label(R.string.level))
        val levelGroup = RadioGroup(this).apply { orientation = RadioGroup.HORIZONTAL }
        intArrayOf(R.string.apprentice, R.string.wayfarer, R.string.master).forEachIndexed { k, r ->
            levelGroup.addView(RadioButton(this).apply { id = 300 + k; text = getString(r) })
        }
        levelGroup.check(301)
        box.addView(levelGroup)
        box.addView(TextView(this).apply { text = getString(R.string.help_after); textSize = 12f })
        val rivalsLabel = label(R.string.rivals).apply { text = "${getString(R.string.rivals)}: 3" }
        box.addView(rivalsLabel)
        val rivals = SeekBar(this).apply { max = 3; progress = 1 } // 2..5
        rivals.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, v: Int, u: Boolean) { rivalsLabel.text = "${getString(R.string.rivals)}: ${v + 2}" }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })
        box.addView(rivals)
        AlertDialog.Builder(this).setTitle(R.string.solo).setView(ScrollView(this).apply { addView(box) })
            .setPositiveButton(R.string.start) { _, _ ->
                startGame(clsGroup.checkedRadioButtonId - 100, if (boardGroup.checkedRadioButtonId == 201) 15 else 10,
                    levelGroup.checkedRadioButtonId - 300, rivals.progress + 2, tutorial)
            }
            .setNegativeButton(R.string.cancel, null).show()
    }

    private fun startGame(cls: Int, n: Int, level: Int, rivals: Int, tutorial: Boolean) {
        startActivity(Intent(this, GameActivity::class.java)
            .putExtra("cls", cls).putExtra("n", n).putExtra("level", level)
            .putExtra("rivals", rivals).putExtra("tutorial", tutorial))
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    @Suppress("unused") private fun gone(v: View) { v.visibility = View.GONE }
}
