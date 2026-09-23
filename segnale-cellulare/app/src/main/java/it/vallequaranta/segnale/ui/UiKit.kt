package it.vallequaranta.segnale.ui

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** Piccoli helper per costruire la UI da codice (niente XML, niente AndroidX). */
object UiKit {
    const val PRIMARY = 0xFF1565C0.toInt()
    const val TEXT = 0xFF212121.toInt()
    const val MUTED = 0xFF616161.toInt()
    const val BG = 0xFFF5F5F5.toInt()

    fun Context.dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    fun Context.vertical(padding: Int = 12): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(padding), dp(padding), dp(padding), dp(padding))
    }

    fun Context.horizontal(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }

    fun Context.scroll(child: View): ScrollView = ScrollView(this).apply {
        isFillViewport = true
        addView(child)
    }

    fun Context.text(s: CharSequence = "", size: Float = 15f, bold: Boolean = false, color: Int = TEXT): TextView =
        TextView(this).apply {
            text = s
            textSize = size
            setTextColor(color)
            if (bold) setTypeface(typeface, Typeface.BOLD)
            setPadding(0, dp(2), 0, dp(2))
        }

    fun Context.title(s: String): TextView = text(s, 18f, bold = true, color = PRIMARY).apply {
        setPadding(0, dp(12), 0, dp(4))
    }

    fun Context.button(label: String, onClick: () -> Unit): Button = Button(this).apply {
        text = label
        isAllCaps = false
        setOnClickListener { onClick() }
    }

    /** Riquadro con barra colorata a sinistra. */
    fun Context.card(accent: Int, content: View): LinearLayout = horizontal().apply {
        background = GradientDrawable().apply {
            setColor(Color.WHITE)
            cornerRadius = dp(8).toFloat()
        }
        val bar = View(context).apply { setBackgroundColor(accent) }
        addView(bar, LinearLayout.LayoutParams(dp(6), ViewGroup.LayoutParams.MATCH_PARENT))
        content.setPadding(dp(10), dp(8), dp(10), dp(8))
        addView(content, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            setMargins(0, dp(4), 0, dp(4))
        }
    }

    fun matchWrap() = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    fun weight(w: Float = 1f) = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, w)
}

/** Una schermata dell'app. */
interface Screen {
    val view: View
    fun onShown() {}
    fun onCells() {}
    fun onLocation() {}
    fun onHeading() {}
    fun onReport() {}
    fun onPause() {}
    fun onResume() {}
}
