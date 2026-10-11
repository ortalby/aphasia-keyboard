package com.ortalby.aphasiakeyboard

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView

/** Shared colors and view builders: large text, large touch targets, high contrast. */
object UiKit {
    val BACKGROUND = Color.parseColor("#F4F1EA")
    val CARD = Color.WHITE
    val CARD_OUTGOING = Color.parseColor("#E3EFE0")
    val TEXT = Color.parseColor("#151515")
    val TEXT_SECONDARY = Color.parseColor("#555555")
    val PRIMARY = Color.parseColor("#1E5AA8")
    val GREEN = Color.parseColor("#2E7D32")
    val RED = Color.parseColor("#C62828")
    val ORANGE = Color.parseColor("#E65100")
    val GREY = Color.parseColor("#616161")
    val BORDER = Color.parseColor("#C9C2B4")
    val SIMPLE_BOX = Color.parseColor("#FFF8E1")

    fun dp(context: Context, value: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), context.resources.displayMetrics).toInt()

    fun rounded(context: Context, fill: Int, stroke: Int? = null, radiusDp: Int = 14): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(context, radiusDp).toFloat()
            setColor(fill)
            if (stroke != null) setStroke(dp(context, 2), stroke)
        }

    /** A big tappable button. [filled] = solid color with white text; otherwise outlined. */
    fun button(
        context: Context,
        label: String,
        color: Int,
        filled: Boolean = true,
        textSizeSp: Float = 20f,
        onClick: () -> Unit,
    ): TextView = TextView(context).apply {
        text = label
        gravity = Gravity.CENTER
        setTextSize(TypedValue.COMPLEX_UNIT_SP, textSizeSp)
        setTypeface(typeface, Typeface.BOLD)
        setTextColor(if (filled) Color.WHITE else color)
        minHeight = dp(context, 56)
        val pad = dp(context, 10)
        setPadding(pad, pad, pad, pad)
        background = RippleDrawable(
            ColorStateList.valueOf(Color.parseColor("#33000000")),
            rounded(context, if (filled) color else CARD, if (filled) null else color),
            null,
        )
        isClickable = true
        isFocusable = true
        setOnClickListener { onClick() }
    }

    fun text(context: Context, value: String, sizeSp: Float, color: Int = TEXT, bold: Boolean = false): TextView =
        TextView(context).apply {
            text = value
            setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
            setTextColor(color)
            if (bold) setTypeface(typeface, Typeface.BOLD)
            textDirection = View.TEXT_DIRECTION_RTL
            textAlignment = View.TEXT_ALIGNMENT_VIEW_START
            setLineSpacing(0f, 1.15f)
        }

    fun card(context: Context, fill: Int = CARD, stroke: Int? = BORDER): LinearLayout =
        LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(context, fill, stroke)
            val pad = dp(context, 12)
            setPadding(pad, pad, pad, pad)
        }

    fun row(context: Context): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
    }

    fun matchWrap(context: Context, topMarginDp: Int = 0): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(context, topMarginDp)
        }

    fun weighted(context: Context, weight: Float = 1f, marginDp: Int = 4): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, weight).apply {
            val m = dp(context, marginDp)
            setMargins(m, m, m, m)
        }
}
