package com.unite.sdk.view.cards

import android.content.Context
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Shader
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.ShapeDrawable
import android.graphics.drawable.shapes.RoundRectShape
import android.text.TextUtils
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.unite.sdk.slot.GameSlot
import com.unite.sdk.utils.ImageLoader
import com.unite.sdk.utils.designPx
import com.unite.sdk.view.SlotActionButton
import com.unite.sdk.view.SlotBadgeView

class GameAdCardRectangle161_108View(context: Context) : FrameLayout(context) {
    private val imageView = ImageView(context)
    private val badgeView = SlotBadgeView(context)
    private val overlay = LinearLayout(context)
    private val titleView = TextView(context)
    private val playButton = SlotActionButton(context)

    init {
        layoutParams = LayoutParams(resources.designPx(161), resources.designPx(108))
        clipToOutline = true
        background = GradientDrawable().apply {
            setColor(Color.parseColor("#F2F3F5"))
            cornerRadius = resources.designPx(6).toFloat()
        }

        imageView.layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        imageView.scaleType = ImageView.ScaleType.CENTER_CROP
        imageView.background = GradientDrawable().apply {
            setColor(Color.parseColor("#F2F3F5"))
            cornerRadius = resources.designPx(6).toFloat()
        }
        imageView.clipToOutline = true
        addView(imageView)

        badgeView.layoutParams = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.TOP or Gravity.START
            topMargin = resources.designPx(4)
            marginStart = resources.designPx(4)
        }
        addView(badgeView)

        val heightPx = resources.designPx(28)
        overlay.orientation = LinearLayout.VERTICAL
        overlay.layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, heightPx).apply {
            gravity = Gravity.BOTTOM
        }
        overlay.background = gradientOverlayDrawable(heightPx)
        overlay.clipToOutline = true
        overlay.setPadding(dp(8), 0, dp(8), 0)
        addView(overlay)

        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        titleView.textSize = 14f
        titleView.setTextColor(Color.WHITE)
        titleView.maxLines = 1
        titleView.ellipsize = TextUtils.TruncateAt.END
        titleView.gravity = Gravity.CENTER_VERTICAL
        titleView.layoutParams = LinearLayout.LayoutParams(0, resources.designPx(26), 1f)

        playButton.applyPreset(size = SlotActionButton.SizePreset.W44_H16, background = SlotActionButton.BackgroundPreset.SOLID_TEAL)

        row.addView(titleView)
        row.addView(playButton)
        overlay.addView(row)
    }

    fun bind(gs: GameSlot, onClick: () -> Unit) {
        setOnClickListener { onClick() }
        playButton.setOnClickListener { onClick() }
        titleView.text = gs.title.ifEmpty { " " }

        val imgUrl = if (gs.cover.isNotEmpty()) gs.cover else gs.image
        if (imgUrl.isNotEmpty()) {
            ImageLoader.loadWithFallback(listOf(gs.cover, gs.banner, gs.image), imageView)
        }

        val badgeIcon = gs.badgeIcon
        if (badgeIcon.isEmpty()) {
            badgeView.hide()
        } else {
            badgeView.setImageBadge(SlotBadgeView.ImageBadge(data = badgeIcon, widthDesignPx = 44, heightDesignPx = 16))
        }
    }

    private fun gradientOverlayDrawable(heightPx: Int): ShapeDrawable {
        val r = resources.designPx(8).toFloat()
        val radii = floatArrayOf(0f, 0f, 0f, 0f, r, r, r, r)
        val shape = RoundRectShape(radii, null, null)
        return ShapeDrawable(shape).apply {
            paint.shader = LinearGradient(
                0f,
                0f,
                0f,
                heightPx.toFloat(),
                intArrayOf(
                    Color.parseColor("#00000000"),
                    Color.parseColor("#66000000"),
                    Color.parseColor("#CC000000")
                ),
                floatArrayOf(0.2309f, 0.4624f, 0.9883f),
                Shader.TileMode.CLAMP
            )
            paint.isAntiAlias = true
        }
    }

    private fun dp(value: Int): Int {
        return (resources.displayMetrics.density * value).toInt()
    }
}
