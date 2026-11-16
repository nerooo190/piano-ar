package com.pianoar.display

import android.app.Presentation
import android.content.Context
import android.graphics.Color
import android.os.Bundle
import android.view.Display
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import com.pianoar.ui.PianoStageView

/**
 * "Floating Stage" on the XREAL 1S (or any external USB-C/DP display).
 * Black = transparent on optical see-through glasses, so only notes, keys and text are visible.
 */
class GlassesPresentation(context: Context, display: Display) : Presentation(context, display) {

    lateinit var stage: PianoStageView
        private set
    private lateinit var info: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
            setPadding(48, 24, 48, 32)
        }
        info = TextView(context).apply {
            text = "Piano AR"
            setTextColor(Color.rgb(0, 229, 255))
            textSize = 18f
            gravity = Gravity.CENTER_HORIZONTAL
        }
        root.addView(info)
        stage = PianoStageView(context, floating = true)
        root.addView(stage, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
    }

    fun setInfo(text: String) {
        if (::info.isInitialized) info.text = text
    }
}
