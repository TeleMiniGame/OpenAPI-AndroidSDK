package com.unite.sdk.view

import android.annotation.SuppressLint
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ImageView
import android.widget.TextView
import android.content.Context
import com.unite.sdk.utils.ImageLoader
import com.unite.sdk.slot.SlotListener
import com.unite.sdk.slot.GameSlot
import com.unite.sdk.utils.ClickGuard
import com.unite.sdk.game.OpenGameCenterUrl
import com.unite.sdk.game.GameLauncher
import com.unite.sdk.network.SlotApi
import com.unite.sdk.tracking.TrackingReporter
import com.unite.sdk.utils.ImpressionTracker
import androidx.core.graphics.toColorInt
import kotlin.math.ceil

@SuppressLint("ClickableViewAccessibility")
class RandomCardsView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    private var gameSlot: String? = null
    private var slotListener: SlotListener? = null
    private val TAG = "mlog"
    private val handler = Handler(Looper.getMainLooper())
    private var autoScroll = true
    private var onMoreClick: (() -> Unit)? = null
    private var gameCenterUrl: String = "https://minigame.com"
    private var userInteracting = false
    private var scrollDirection = 1
    private var autoScrollIntervalMs: Long = 4000
    private var resumeDelayMs: Long = 1200

    private val tvTitle = TextView(context)
    private val tvMore = TextView(context)
    private val scrollView = HorizontalScrollView(context)
    private val container = LinearLayout(context)
    private var badgeConfigProvider: ((GameSlot) -> SlotBadgeView.Config?)? = null
    private val impressionTracker = ImpressionTracker(
        hostView = scrollView,
        minVisibleRatio = 0.5f,
        minVisibleMs = 800L,
        throttleMs = 200L
    ) { ids ->
        val slotIds = gameSlot
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?.let { slot -> ids.associateWith { slot } }
        TrackingReporter.reportImpression(ids, slotIds = slotIds)
        Log.d("mlog", "曝光RandomSlot:$ids")
    }
    private val autoScrollRunnable = object : Runnable {
        override fun run() {
            if (!autoScroll || userInteracting || container.childCount <= 0) return

            val maxScrollX = getMaxScrollX()
            if (maxScrollX <= 0) return

            val currentX = scrollView.scrollX
            if (scrollDirection > 0 && currentX >= maxScrollX) scrollDirection = -1
            if (scrollDirection < 0 && currentX <= 0) scrollDirection = 1

            val step = getScrollStepPx()
            val targetX = (currentX + scrollDirection * step).coerceIn(0, maxScrollX)
            scrollView.smoothScrollTo(targetX, 0)
            handler.postDelayed(this, autoScrollIntervalMs)
        }
    }

    init {
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
        }

        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            setPadding(dp(0), dp(8), dp(0), dp(8))
        }

        tvTitle.text = "随便玩玩"
        tvTitle.textSize = 14f
        tvTitle.setTypeface(null, Typeface.BOLD)
        tvTitle.setTextColor("#1A1A1A".toColorInt())
        tvTitle.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)

        tvMore.text = " "
        tvMore.textSize = 12f
        tvMore.setTextColor("#8A8A8A".toColorInt())

        header.addView(tvTitle)
        header.addView(tvMore)

        scrollView.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
        scrollView.isHorizontalScrollBarEnabled = false
        scrollView.overScrollMode = OVER_SCROLL_NEVER
        scrollView.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                    userInteracting = true
                    handler.removeCallbacks(autoScrollRunnable)
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    userInteracting = false
                    if (autoScroll) {
                        handler.removeCallbacks(autoScrollRunnable)
                        handler.postDelayed(autoScrollRunnable, resumeDelayMs)
                    }
                }
            }
            false
        }

        container.orientation = LinearLayout.HORIZONTAL
        container.layoutParams = LayoutParams(
            LayoutParams.WRAP_CONTENT,
            LayoutParams.WRAP_CONTENT
        )
        container.setPadding(dp(0), 0, dp(0), dp(8))
        scrollView.addView(container)

        root.addView(header)
        root.addView(scrollView)
        addView(root)
        impressionTracker.start()
    }

    fun setSlotListener(listener: SlotListener?) {
        slotListener = listener
    }

    fun setBadgeConfigProvider(provider: ((GameSlot) -> SlotBadgeView.Config?)?) {
        badgeConfigProvider = provider
    }

    fun setOnMoreClickListener(listener: (() -> Unit)?) {
        onMoreClick = listener
    }

    fun setGameCenterUrl(url: String?) {
        gameCenterUrl = url.orEmpty()
    }

    fun setAutoScroll(enabled: Boolean) {
        autoScroll = enabled
        if (enabled) {
            startAutoScroll()
        } else {
            handler.removeCallbacks(autoScrollRunnable)
        }
    }

    fun loadSlot(gameSlot: String) {
        this.gameSlot = gameSlot

        val gameSlotId = this.gameSlot ?: return

        TrackingReporter.reportRequest(gameSlotId)
        SlotApi.loadSlots(gameSlotId) { result ->
            result.onSuccess { list ->
                if (list.isNotEmpty()) {
                    container.removeAllViews()
                    renderGrid(list)
                    slotListener?.onSlotLoaded(list.first(), gameSlot ?: "")
                    slotListener?.onSlotShow(list.first(), gameSlot ?: "")
                    startAutoScroll()
                } else {
                    slotListener?.onSlotFailed("No slot available", gameSlot ?: "")
                }
            }.onFailure {
                slotListener?.onSlotFailed(it.message ?: "Load failed", gameSlot ?: "")
            }
        }
    }

    private fun renderGrid(list: List<GameSlot>) {
        impressionTracker.reset()
        // provisional（过期缓存兜底）内容照常渲染但不计曝光；刷新结果回来后会以非 provisional 再渲染一次
        val countExposure = list.firstOrNull()?.provisional != true
        val itemSize = dp(52)
        val gap = dp(8)
        val radius = dp(10).toFloat()

        val count = list.size
        val rows = if (count <= 2) 1 else 2
        val columns = ceil(count / rows.toDouble()).toInt()

        var index = 0
        for (c in 0 until columns) {
            val column = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    if (c > 0) marginStart = gap
                }
            }

            for (r in 0 until rows) {
                if (index >= count) break
                val gs = list[index++]

                val item = FrameLayout(context).apply {
                    layoutParams = LinearLayout.LayoutParams(itemSize, itemSize).apply {
                        if (r > 0) topMargin = gap
                    }
                }

                val image = ImageView(context).apply {
                    layoutParams = FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT
                    )
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    background = GradientDrawable().apply {
                        setColor(Color.parseColor("#F2F3F5"))
                        cornerRadius = radius
                    }
                    clipToOutline = true
                }

                val imgUrl = if (gs.thumbnailUrl.isNotEmpty()) gs.thumbnailUrl else gs.image
                if (imgUrl.isNotEmpty()) {
                    ImageLoader.loadWithFallback(listOf(gs.thumbnailUrl, gs.image, gs.icon), image)
                }

                item.setOnClickListener {
                    if (!ClickGuard.canClick(gs.id.ifEmpty { gs.title })) return@setOnClickListener
                    slotListener?.onSlotClick(gs, gameSlot ?: "")
                    TrackingReporter.reportClick(gs, slotId = gameSlot)
                    GameLauncher.openGame(context, gs, slotListener)
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

                item.addView(image)
                item.addView(badge)
                column.addView(item)
                if (countExposure) impressionTracker.track(gs.id, item)
            }
            container.addView(column)
        }
        if (countExposure) impressionTracker.schedule()
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

    private fun startAutoScroll() {
        handler.removeCallbacks(autoScrollRunnable)
        if (autoScroll && !userInteracting && container.childCount > 0) {
            handler.postDelayed(autoScrollRunnable, autoScrollIntervalMs)
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        handler.removeCallbacks(autoScrollRunnable)
        impressionTracker.stop()
        impressionTracker.reset()
    }

    private fun getMaxScrollX(): Int {
        val contentWidth = container.width
        val viewportWidth = scrollView.width
        return (contentWidth - viewportWidth).coerceAtLeast(0)
    }

    private fun getScrollStepPx(): Int {
        if (container.childCount <= 0) return dp(60)
        val itemWidth = container.getChildAt(0).width
        return (itemWidth + dp(8)).coerceAtLeast(1)
    }

    private fun dp(value: Int): Int {
        return (resources.displayMetrics.density * value).toInt()
    }

    private fun openGameCenter() {
        if (gameCenterUrl.isEmpty()) return
        Log.d(TAG, "openGameCenter url=$gameCenterUrl")
        OpenGameCenterUrl.open(context, gameCenterUrl)
    }
}
