package com.azrael.pixivdumpsync

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView

/** Shared visual language for Asterion Downloader's source hub, galleries and transfers. */
object NocturneDesign {
    // One neutral charcoal-to-graphite system across every module; media stays vivid.
    // Status-only red is reserved for errors, never for navigation or decoration.
    val background = Color.rgb(8, 9, 11)
    val surface = Color.rgb(17, 18, 20)
    val raised = Color.rgb(28, 29, 32)
    val soft = Color.rgb(34, 35, 39)
    val border = Color.rgb(55, 57, 62)
    val primary = Color.rgb(221, 223, 226)
    val violet = primary
    val white = Color.rgb(247, 248, 250)
    val secondary = Color.rgb(178, 182, 189)
    val subdued = Color.rgb(137, 142, 151)
    val danger = Color.rgb(248, 133, 145)
    val pixivBlue = primary
    val xCyan = primary
    val cosplayRose = primary
    val gamesTeal = primary
    val booksGold = primary
    val assetsLilac = primary
    fun compact(context: Context): Boolean = context
        .getSharedPreferences("asterion_display",0).getBoolean("compact",true)
    fun largeCovers(context: Context): Boolean = context
        .getSharedPreferences("asterion_display",0).getBoolean("large_covers",false)
    fun showPreviews(context: Context): Boolean = context
        .getSharedPreferences("asterion_display",0).getBoolean("previews",true)


    fun px(context: Context, dp: Int): Int =
        (dp * context.resources.displayMetrics.density).toInt()

    fun shape(context: Context, color: Int = surface, corner: Int = 22,
              outline: Int = border): GradientDrawable =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = px(context, corner).toFloat()
            setStroke(px(context, 1), outline)
        }

    fun touch(context: Context, color: Int = surface, corner: Int = 22,
              outline: Int = border): RippleDrawable =
        RippleDrawable(ColorStateList.valueOf(Color.argb(48, 223, 224, 227)),
            shape(context, color, corner, outline), null)

    fun text(context: Context, label: String, sp: Float, color: Int = white,
             heavy: Boolean = false): TextView = TextView(context).apply {
        text = label
        textSize = sp
        setTextColor(color)
        includeFontPadding = true
        if (heavy) setTypeface(Typeface.DEFAULT, Typeface.BOLD)
    }

    fun eyebrow(context: Context, label: String): TextView =
        text(context, label.uppercase(), 11f, subdued, true).apply {
            letterSpacing = .13f
        }

    fun card(context: Context, padding: Int = 18): LinearLayout =
        LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            val inset=if(padding==18 && compact(context))14 else padding
            setPadding(px(context,inset),px(context,inset),
                px(context,inset),px(context,inset))
            background = shape(context,surface,if(compact(context))17 else 22)
        }

    fun space(context: Context, dp: Int) = View(context).apply {
        layoutParams = LinearLayout.LayoutParams(1, px(context, dp))
    }

    fun action(context: Context, label: String, primaryAction: Boolean = false,
               onTap: () -> Unit): TextView = text(context, label, 14f,
        if (primaryAction) background else white, true).apply {
        gravity = Gravity.CENTER
        minHeight = px(context, 48)
        setPadding(px(context, 14), px(context, 10), px(context, 14), px(context, 10))
        background = touch(context, if (primaryAction) primary else raised, 15,
            if (primaryAction) primary else border)
        isClickable = true
        isFocusable = true
        setOnClickListener { onTap() }
    }

    fun chip(context: Context, label: String, selected: Boolean = false,
             onTap: () -> Unit): TextView = text(context, label, 13f,
        if (selected) background else secondary, selected).apply {
        gravity = Gravity.CENTER
        setPadding(px(context, 14), px(context, 10), px(context, 14), px(context, 10))
        background = touch(context, if (selected) primary else raised, 18,
            if (selected) primary else border)
        isClickable = true
        isFocusable = true
        setOnClickListener { onTap() }
    }

    fun row(context: Context): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }

    fun lp(context: Context, top: Int = 0, bottom: Int = 0):
            LinearLayout.LayoutParams = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
        topMargin = px(context, top)
        bottomMargin = px(context, bottom)
    }

    fun weighted(context: Context, left: View, right: View, gap: Int = 10):
            LinearLayout = row(context).apply {
        addView(left, LinearLayout.LayoutParams(0, -2, 1f))
        addView(right, LinearLayout.LayoutParams(0, -2, 1f).apply {
            leftMargin = px(context, gap)
        })
    }

    fun brandMark(context: Context, dp: Int = 44): android.widget.ImageView =
        android.widget.ImageView(context).apply {
            setImageResource(R.drawable.ic_asterion_downloader)
            scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
            layoutParams = LinearLayout.LayoutParams(px(context, dp), px(context, dp))
            contentDescription = "Asterion Downloader logo"
        }
}
