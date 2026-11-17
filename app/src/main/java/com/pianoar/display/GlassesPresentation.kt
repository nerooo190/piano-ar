package com.pianoar.display

import android.app.Presentation
import android.content.Context
import android.graphics.Color
import android.os.Bundle
import android.view.Display
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.pianoar.ar.CameraPane
import com.pianoar.ui.PianoStageView

/**
 * Content on the XREAL 1S (or any external USB-C/DP display):
 * either the "Floating Stage" (falling notes, black = transparent in the glasses)
 * or the camera AR view with notes on the real keys.
 */
class GlassesPresentation(context: Context, display: Display) : Presentation(context, display) {

    lateinit var stage: PianoStageView
        private set
    var cameraPane: CameraPane? = null
        private set
    private lateinit var info: TextView
    private lateinit var container: FrameLayout

    /** The view that needs redrawing every frame. */
    val animated: View?
        get() = cameraPane?.overlay ?: if (::stage.isInitialized) stage else null

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
        container = FrameLayout(context)
        stage = PianoStageView(context, floating = true)
        container.addView(stage, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        root.addView(container, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
    }

    fun showStage() {
        cameraPane?.let { container.removeView(it) }
        cameraPane = null
        stage.visibility = View.VISIBLE
    }

    fun showCamera(factory: (Context) -> CameraPane): CameraPane {
        cameraPane?.let { return it }
        val p = factory(context)
        container.addView(p, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        stage.visibility = View.GONE
        cameraPane = p
        return p
    }

    fun setInfo(text: String) {
        if (::info.isInitialized) info.text = text
    }
}
