package com.unite.sdk.view

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.util.AttributeSet
import android.util.Log
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.unite.sdk.slot.SlotListener
import com.unite.sdk.slot.GameSlot
import com.unite.sdk.game.GameLauncher
import com.unite.sdk.network.SlotApi
import com.unite.sdk.tracking.TrackingReporter
import com.unite.sdk.utils.ClickGuard
import com.unite.sdk.utils.ImpressionTracker
import com.unite.sdk.utils.ImageLoader

class ColCardView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    private var gameSlot: String? = null
    private var slotListener: SlotListener? = null
    private var displayCount: Int = 2
    private var slotList: List<GameSlot> = emptyList()

    private val container = LinearLayout(context)
    private var badgeConfigProvider: ((GameSlot) -> SlotBadgeView.Config?)? = null
    private val impressionTracker = ImpressionTracker(
        hostView = this,
        minVisibleRatio = 0.5f,
        minVisibleMs = 800L,
        throttleMs = 200L
    ) { ids ->
        val slotIds = gameSlot
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?.let { slot -> ids.associateWith { slot } }
        TrackingReporter.reportImpression(ids, slotIds = slotIds)
        Log.d("mlog", "曝光 ColCardSlot")
    }

    init {
        background = GradientDrawable().apply {
            setColor(Color.WHITE)
            cornerRadius = dp(12).toFloat()
        }
        clipToOutline = true
        setPadding(dp(12), dp(12), dp(12), dp(12))

        container.orientation = LinearLayout.VERTICAL
        container.layoutParams = LayoutParams(
            LayoutParams.MATCH_PARENT,
            LayoutParams.WRAP_CONTENT
        )
        addView(container)
        impressionTracker.start()
    }

    fun setSlotListener(listener: SlotListener?) {
        slotListener = listener
    }

    fun setBadgeConfigProvider(provider: ((GameSlot) -> SlotBadgeView.Config?)?) {
        badgeConfigProvider = provider
    }

    fun setDisplayCount(count: Int) {
        displayCount = count.coerceAtLeast(1)
        if (slotList.isNotEmpty()) {
            renderSlots(slotList)
        }
    }

    fun loadSlot(gameSlot: String) {
        this.gameSlot = gameSlot
        val gameSlotId = this.gameSlot ?: return
        SlotApi.loadSlots(gameSlotId) { result ->
            result.onSuccess { list ->
                slotList = list
                if (list.isEmpty()) {
                    slotListener?.onSlotFailed("No slot available", gameSlot ?: "")
                    return@onSuccess
                }
                renderSlots(list)
                slotListener?.onSlotLoaded(list.first(), gameSlot ?: "")
                slotListener?.onSlotShow(list.first(), gameSlot ?: "")
            }.onFailure {
                slotListener?.onSlotFailed(it.message ?: "Load failed", gameSlot ?: "")
            }
        }
    }

    private fun renderSlots(list: List<GameSlot>) {
        impressionTracker.reset()
        container.removeAllViews()
        val showList = list.take(displayCount)
        // provisional（过期缓存兜底）内容照常渲染但不计曝光；刷新结果回来后会以非 provisional 再渲染一次
        val countExposure = showList.firstOrNull()?.provisional != true
        showList.forEachIndexed { index, gs ->
            val row = createRow(gs)
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            if (index > 0) lp.topMargin = dp(10)
            row.layoutParams = lp
            container.addView(row)
            if (countExposure) impressionTracker.track(gs.id, row)
        }
        if (countExposure) impressionTracker.schedule()
    }

    private fun createRow(gs: GameSlot): LinearLayout {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val thumbContainer = FrameLayout(context).apply {
            layoutParams = LinearLayout.LayoutParams(dp(84), dp(64)).apply {
                marginEnd = dp(12)
                gravity = Gravity.CENTER_VERTICAL
            }
        }
        val thumb = ImageView(context).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#F2F3F5"))
                cornerRadius = dp(10).toFloat()
            }
            clipToOutline = true
        }
        val imgUrl = when {
            gs.banner.isNotEmpty() -> gs.banner
            gs.cover.isNotEmpty() -> gs.cover
            gs.image.isNotEmpty() -> gs.image
            else -> ""
        }
        if (imgUrl.isNotEmpty()) {
            ImageLoader.loadWithFallback(listOf(gs.banner, gs.cover, gs.image), thumb)
        }
        val badge = SlotBadgeView(context).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                topMargin = dp(6)
                marginStart = dp(6)
            }
            setConfig(badgeConfigProvider?.invoke(gs) ?: defaultBadgeConfig(gs))
        }
        thumbContainer.addView(thumb)
        thumbContainer.addView(badge)

        val textBox = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        val title = TextView(context).apply {
            text = gs.title
            textSize = 16f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#1A1A1A"))
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }

        val desc = TextView(context).apply {
            text = gs.desc
            textSize = 12f
            setTextColor(Color.parseColor("#8A8A8A"))
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }

        textBox.addView(title)
        textBox.addView(desc)

        fun handleSlotClick() {
            if (!ClickGuard.canClick(gs.id.ifEmpty { gs.title })) return
            slotListener?.onSlotClick(gs, gameSlot ?: "")
            TrackingReporter.reportClick(gs, slotId = gameSlot)
            GameLauncher.openGame(context, gs, slotListener)
        }

        val playButton = SlotActionButton(context).apply {
            applyPreset(size = SlotActionButton.SizePreset.W63_H26, background = SlotActionButton.BackgroundPreset.SOLID_TEAL)
            setOnClickListener { handleSlotClick() }
        }.also { btn ->
            val lp = btn.layoutParams
            btn.layoutParams = LinearLayout.LayoutParams(lp.width, lp.height).apply {
                gravity = Gravity.CENTER_VERTICAL
            }
        }

        row.setOnClickListener { handleSlotClick() }
        row.addView(thumbContainer)
        row.addView(textBox)
        row.addView(playButton)
        return row
    }

    private fun dp(value: Int): Int {
        return (resources.displayMetrics.density * value).toInt()
    }

    private fun defaultBadgeConfig(gs: GameSlot): SlotBadgeView.Config? {
        val icon = gs.badgeIcon
        if (icon.isEmpty()) return null
        return SlotBadgeView.Config.Image(
            SlotBadgeView.ImageBadge(
                data = icon,
                widthDesignPx = 44,
                heightDesignPx = 16
            )
        )
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        impressionTracker.stop()
        impressionTracker.reset()
    }
}
