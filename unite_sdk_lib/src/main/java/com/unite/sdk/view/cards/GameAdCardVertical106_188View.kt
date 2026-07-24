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
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.unite.sdk.R
import com.unite.sdk.slot.GameSlot
import com.unite.sdk.utils.ImageLoader
import com.unite.sdk.utils.designPx
import com.unite.sdk.view.SlotActionButton
import com.unite.sdk.view.SlotBadgeView

class GameAdCardVertical106_188View(context: Context) : AspectRatioFrameLayout(context, 106, 188) {

    companion object {
        // ↓ 这三个常量共同支撑「本卡型恒定端上叠 M 标、不看 m_icon_mode」这一决策，
        //   其不变式由 MIconOcclusionInvariantTest 守护。改动前先读 bindSlot 里的说明。
        const val CARD_WIDTH_DP = 106
        const val BOTTOM_BAR_HEIGHT_DP = 44

        // 服务端合成到竖卡主图 flash 上的角标占「图宽」的比例（OSS 参数 P_19）。
        // 主图与本卡近似同比，故角标在卡片上的边长 ≈ CARD_WIDTH_DP * 该比例。
        const val SERVER_BADGE_WIDTH_RATIO_FLASH = 0.19
    }

    private val imageView = ImageView(context)
    private val badgeView = SlotBadgeView(context)
    // 底部黑色半透明蒙版：所有 Android 版本统一（对齐 OpenAPI-V4 §7 threeCards UI 标注——
    // 深色渐变蒙版 + 纯白标题）。同时承担遮住服务端合成角标的职责（M 标遮挡不变式）。
    private val scrimOverlay = View(context)
    private val bar = LinearLayout(context)
    private val titleView = TextView(context)
    private val playButton = SlotActionButton(context)
    private val cornerBadge = ImageView(context)

    init {
        layoutParams = LayoutParams(resources.designPx(CARD_WIDTH_DP), resources.designPx(188))
        clipToOutline = true

        val cardRadius = resources.designPx(12).toFloat()
        val border = resources.designPx(2)
        setPadding(border, border, border, border)
        background = GradientDrawable().apply {
            setColor(Color.TRANSPARENT)
            cornerRadius = cardRadius
            setStroke(border, Color.WHITE)
        }

        imageView.layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        imageView.scaleType = ImageView.ScaleType.CENTER_CROP
        imageView.background = GradientDrawable().apply {
            setColor(Color.parseColor("#F2F3F5"))
            cornerRadius = cardRadius
        }
        imageView.clipToOutline = true
        addView(imageView)

        badgeView.layoutParams = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.TOP or Gravity.START
            topMargin = resources.designPx(10)
            marginStart = resources.designPx(10)
        }
        addView(badgeView)

        val barHeight = resources.designPx(BOTTOM_BAR_HEIGHT_DP)
        val bottomRadius = resources.designPx(12).toFloat()

        // 底部黑色渐变蒙版（透明→黑），高度 = 底部条高。所有版本一致。
        scrimOverlay.layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, barHeight).apply {
            gravity = Gravity.BOTTOM
        }
        scrimOverlay.background = scrimDrawable(barHeight, bottomRadius)
        scrimOverlay.visibility = GONE
        addView(scrimOverlay)

        bar.orientation = LinearLayout.VERTICAL
        bar.layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, barHeight).apply {
            gravity = Gravity.BOTTOM
        }
        bar.setPadding(resources.designPx(10), resources.designPx(6), resources.designPx(10), resources.designPx(2))
        addView(bar)

        // 纯白标题（12px），无描边——底部黑色蒙版已保证在深/浅封面上都清晰，与 API 对接文档一致。
        titleView.textSize = 12f
        titleView.setTextColor(Color.WHITE)
        titleView.maxLines = 1
        titleView.ellipsize = TextUtils.TruncateAt.END
        titleView.gravity = Gravity.CENTER
        titleView.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            0,
            1f
        )

        playButton.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            topMargin = resources.designPx(2)
        }
        // PLAY 按钮：按 OpenAPI-V4 §7 threeCards UI 标注为蓝青渐变 #23B3CD→#1DE8CA（非纯色），64×16 圆角 24
        playButton.applyPreset(
            size = SlotActionButton.SizePreset.W64_H16,
            background = SlotActionButton.BackgroundPreset.GRADIENT_BLUE_TEAL
        )

        bar.addView(titleView)
        bar.addView(playButton)

        cornerBadge.layoutParams = LayoutParams(
            resources.designPx(20),
            resources.designPx(20)
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.END
            bottomMargin = 0
            marginEnd = 0
        }
        cornerBadge.setImageResource(R.drawable.ic_badge_m)
        cornerBadge.elevation = resources.designPx(4).toFloat()
        addView(cornerBadge)
    }

    fun bind(gs: GameSlot, onClick: () -> Unit) {
        setOnClickListener { onClick() }
        playButton.setOnClickListener { onClick() }

        titleView.text = gs.title.ifEmpty { " " }

        val imgUrl = if (gs.flash.isNotEmpty()) gs.flash else gs.image
        if (imgUrl.isNotEmpty()) {
            val candidates = listOf(gs.flash, gs.banner, gs.image)
            ImageLoader.loadWithFallback(candidates, imageView)
            scrimOverlay.visibility = VISIBLE
        } else {
            scrimOverlay.visibility = GONE
        }

        val badgeIcon = gs.badgeIcon
        if (badgeIcon.isEmpty()) {
            badgeView.hide()
        } else {
            badgeView.setImageBadge(SlotBadgeView.ImageBadge(data = badgeIcon, widthDesignPx = 44, heightDesignPx = 16))
        }

        // M 标：这张卡**不看** m_icon_mode，composited 与否都端上叠，只有 "none" 才隐藏。
        // 原因是服务端烤进主图的角标在这个卡型里必然看不见，不存在双标风险：
        //  · 主图 flash 为 720×1280 竖图，与本卡 106×188 几乎同比，centerCrop 后整图可见，
        //    角标 P_19（图宽 19%）换算到卡上约 20dp，而卡底部压着 44dp 的黑色蒙版条 —— 全被盖住；
        //  · 回退到 banner（580×390 横图）时，横图 centerCrop 进竖容器要裁掉约 62% 的宽度，
        //    贴右边缘的角标整个被裁掉。
        // 而 SDK 自己的 cornerBadge 带 4dp elevation，画在遮挡条之上，是唯一可见的那个。
        // 若哪天卡片底部条改成不遮底部右下角，这里要改回按 gs.overlayBadgeOn(...) 判定。
        if (gs.mIconHidden) {
            cornerBadge.visibility = GONE
        } else {
            cornerBadge.visibility = VISIBLE
            ImageLoader.loadBadge(gs.mIcon, cornerBadge, R.drawable.ic_badge_m)
        }
    }

    // 底部黑色渐变蒙版（透明→黑），下方两角按卡片圆角裁圆。对齐 OpenAPI-V4 §7 threeCards UI 标注：
    // 深色半透明蒙版压住封面底部，白色标题清晰可读。中下部约 60%~85% 黑，与文档实测蒙版深度一致。
    private fun scrimDrawable(heightPx: Int, radiusPx: Float): ShapeDrawable {
        val radii = floatArrayOf(0f, 0f, 0f, 0f, radiusPx, radiusPx, radiusPx, radiusPx)
        val shape = RoundRectShape(radii, null, null)
        return ShapeDrawable(shape).apply {
            paint.shader = LinearGradient(
                0f, 0f, 0f, heightPx.toFloat(),
                intArrayOf(
                    Color.parseColor("#00000000"),
                    Color.parseColor("#99000000"),
                    Color.parseColor("#D9000000")
                ),
                floatArrayOf(0.0f, 0.5f, 1.0f),
                Shader.TileMode.CLAMP
            )
            paint.isAntiAlias = true
        }
    }
}

