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
    val background = Color.rgb(12, 12, 17)
    val surface = Color.rgb(23, 23, 31)
    val raised = Color.rgb(31, 31, 42)
    val soft = Color.rgb(37, 32, 47)
    val border = Color.rgb(61, 55, 74)
    val primary = Color.rgb(190, 167, 238)
    val violet = Color.rgb(190, 167, 238)
    val white = Color.rgb(245, 248, 251)
    val secondary = Color.rgb(175, 193, 207)
    val subdued = Color.rgb(128, 153, 170)
    val danger = Color.rgb(255, 139, 150)
    val pixivBlue = Color.rgb(100, 176, 249)
    val xCyan = Color.rgb(165, 206, 225)
    val cosplayRose = Color.rgb(238, 159, 198)
    val gamesTeal = Color.rgb(121, 213, 196)
    val booksGold = Color.rgb(234, 196, 137)
    val assetsLilac = Color.rgb(187, 159, 245)
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
        RippleDrawable(ColorStateList.valueOf(Color.argb(46, 190, 255, 247)),
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
