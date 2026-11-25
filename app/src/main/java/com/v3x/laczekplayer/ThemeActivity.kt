package com.v3x.laczekplayer

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

class ThemeActivity : Activity() {
    private data class Accent(val name: String, val color: Int)
    private val palette = listOf(
        Accent("Rose", Color.rgb(244,143,177)), Accent("Pink", Color.rgb(236,64,122)),
        Accent("Red", Color.rgb(239,83,80)), Accent("Coral", Color.rgb(255,112,92)),
        Accent("Orange", Color.rgb(255,152,80)), Accent("Amber", Color.rgb(255,193,7)),
        Accent("Gold", Color.rgb(218,184,75)), Accent("Lime", Color.rgb(192,202,51)),
        Accent("Green", Color.rgb(102,187,106)), Accent("Emerald", Color.rgb(38,166,154)),
        Accent("Teal", Color.rgb(38,166,154)), Accent("Cyan", Color.rgb(38,198,218)),
        Accent("Sky", Color.rgb(79,195,247)), Accent("Blue", Color.rgb(66,165,245)),
        Accent("Indigo", Color.rgb(92,107,192)), Accent("Violet", Color.rgb(126,87,194)),
        Accent("Purple", Color.rgb(171,71,188)), Accent("Lavender", Color.rgb(186,156,232)),
        Accent("Magenta", Color.rgb(216,80,170)), Accent("Plum", Color.rgb(142,83,135)),
        Accent("Mint", Color.rgb(128,203,176)), Accent("Ice", Color.rgb(144,202,220)),
        Accent("Slate", Color.rgb(120,144,156)), Accent("Monochrome", Color.rgb(189,189,189))
    )
    private val prefs get() = getSharedPreferences("laczek_player", MODE_PRIVATE)
    private var accent = Color.rgb(157,104,255)
    private var dark = true
    private val bg get() = if (dark) Color.BLACK else Color.rgb(247,245,252)
    private val surface get() = if (dark) Color.rgb(29,27,35) else Color.WHITE
    private val foreground get() = if (dark) Color.rgb(245,239,247) else Color.rgb(28,27,31)
    private val secondary get() = if (dark) Color.rgb(202,196,208) else Color.rgb(73,69,79)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        accent = prefs.getInt("accent", accent)
        dark = prefs.getBoolean("dark", true)
        render()
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private fun shape(color: Int, radius: Int = 24, stroke: Int = Color.TRANSPARENT) =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(radius).toFloat()
            if (stroke != Color.TRANSPARENT) setStroke(dp(2), stroke)
        }

    private fun render() {
        window.statusBarColor = bg
        window.navigationBarColor = bg
        @Suppress("DEPRECATION")
        if (android.os.Build.VERSION.SDK_INT >= 23) {
            window.decorView.systemUiVisibility = if (dark) 0 else
                View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        }
        val scroll = ScrollView(this).apply { setBackgroundColor(bg); clipToPadding = false }
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(16), dp(20), dp(28))
        }
        val header = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        header.addView(button("‹", surface, foreground) { finish() }, LinearLayout.LayoutParams(dp(48), dp(48)))
        val heading = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(14), 0, 0, 0) }
        heading.addView(label("Appearance", 23f, foreground, true))
        heading.addView(label("Material 3 expressive", 12f, secondary))
        header.addView(heading)
        page.addView(header, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(24) })

        page.addView(label("DISPLAY", 11f, secondary, true).apply { letterSpacing = .12f })
        val modeCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = shape(surface, 28)
            setPadding(dp(16), dp(14), dp(16), dp(14))
        }
        modeCard.addView(label("Theme mode", 16f, foreground, true))
        modeCard.addView(label("Choose the surface style used throughout the player", 12f, secondary).apply {
            setPadding(0, dp(4), 0, dp(12))
        })
        val modes = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        listOf("Dark" to true, "Light" to false).forEach { (name, value) ->
            val selected = dark == value
            modes.addView(button(name, if (selected) accent else bg, if (selected) Color.BLACK else foreground) {
                dark = value
                prefs.edit().putBoolean("dark", dark).apply()
                render()
            }, LinearLayout.LayoutParams(0, dp(46), 1f).apply { marginEnd = dp(6) })
        }
        modeCard.addView(modes)
        page.addView(modeCard, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(24) })

        page.addView(label("ACCENT COLOUR", 11f, secondary, true).apply { letterSpacing = .12f })
        page.addView(label("24 curated colours", 22f, foreground, true).apply { setPadding(0, dp(5), 0, dp(3)) })
        page.addView(label("Choose an accent for buttons, sliders, highlights and player controls.", 13f, secondary)
            .apply { setPadding(0, 0, 0, dp(14)) })

        val grid = GridLayout(this).apply { columnCount = 4; useDefaultMargins = false }
        palette.forEach { item ->
            val selected = accent == item.color
            val tile = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(dp(3), dp(7), dp(3), dp(7))
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    accent = item.color
                    prefs.edit().putInt("accent", accent).apply()
                    render()
                }
            }
            val swatch = View(this).apply {
                background = shape(item.color, 22, if (selected) foreground.toInt() else Color.TRANSPARENT)
                contentDescription = item.name
            }
            tile.addView(swatch, LinearLayout.LayoutParams(dp(48), dp(48)))
            tile.addView(label(item.name, 11f, foreground).apply { gravity = Gravity.CENTER; setPadding(0, dp(5), 0, 0) })
            val params = GridLayout.LayoutParams().apply {
                width = 0
                height = dp(88)
                columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
                setMargins(dp(2), dp(2), dp(2), dp(2))
            }
            grid.addView(tile, params)
        }
        page.addView(grid)
        val preview = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = shape(surface, 28)
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }
        preview.addView(label("Live preview", 15f, foreground, true))
        preview.addView(label("LaCzek Player · Your music, your colours", 12f, secondary).apply {
            setPadding(0, dp(5), 0, dp(12))
        })
        preview.addView(button("Selected accent", accent, Color.BLACK) { })
        page.addView(preview, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(18) })
        page.addView(button("Done", accent, Color.BLACK) { finish() }, LinearLayout.LayoutParams(-1, dp(52)).apply { topMargin = dp(20) })
        scroll.addView(page)
        setContentView(scroll)
    }

    private fun label(value: String, size: Float, color: Int, bold: Boolean = false) =
        TextView(this).apply {
            text = value
            textSize = size
            setTextColor(color)
            if (bold) setTypeface(typeface, Typeface.BOLD)
        }

    private fun button(value: String, color: Int, textColor: Int, action: () -> Unit) =
        Button(this).apply {
            text = value
            textSize = 13f
            isAllCaps = false
            setTextColor(textColor)
            background = shape(color, 24)
            backgroundTintList = null
            stateListAnimator = null
            minHeight = dp(44)
            setPadding(dp(14), dp(5), dp(14), dp(5))
            setOnClickListener { action() }
        }
}