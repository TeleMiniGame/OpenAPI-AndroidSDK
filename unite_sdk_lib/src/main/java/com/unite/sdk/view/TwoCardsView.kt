package com.unite.sdk.view

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.util.Log
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import com.unite.sdk.slot.SlotListener
import com.unite.sdk.slot.GameSlot
import com.unite.sdk.game.GameLauncher
import com.unite.sdk.network.SlotApi
import com.unite.sdk.tracking.TrackingReporter
import com.unite.sdk.utils.ClickGuard
import com.unite.sdk.utils.ImpressionTracker
import com.unite.sdk.view.cards.GameAdCardRectangle161_108View
import com.unite.sdk.view.cards.GameAdCardSquare161View
import com.unite.sdk.view.cards.GameAdVideo161View

class TwoCardsView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    enum class CardStyle {
        SQUARE,
        RECTANGLE,
        VIDEO
    }

    private var gameSlot: String? = null
    private var slotListener: SlotListener? = null
    private var slotList: List<GameSlot> = emptyList()
    private var cardStyle: CardStyle = CardStyle.RECTANGLE
    private val handler = Handler(Looper.getMainLooper())
    private val tagName = "mlog"
    private var reloadIntervalMs: Long = 0
    private val reloadRunnable = Runnable { loadSlotInternal() }

    private val scrollView = HorizontalScrollView(context)
    private val container = LinearLayout(context)
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
        Log.d(tagName, "Two Slot 曝光: $ids")
    }

    init {
        scrollView.layoutParams = LayoutParams(
            LayoutParams.MATCH_PARENT,
            LayoutParams.WRAP_CONTENT
        )
        scrollView.isHorizontalScrollBarEnabled = false
        scrollView.overScrollMode = OVER_SCROLL_NEVER

        container.orientation = LinearLayout.HORIZONTAL
        container.layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        scrollView.addView(container)
        addView(scrollView)
        impressionTracker.start()
    }

    fun setSlotListener(listener: SlotListener?) {
        slotListener = listener
    }

    fun setReloadIntervalMillis(interval: Long) {
        reloadIntervalMs = interval
    }

    fun setCardStyle(style: CardStyle) {
        cardStyle = style
        if (slotList.isNotEmpty()) {
            renderSlots(slotList)
        }
    }

    fun loadSlot(gameSlot: String) {
        loadSlot(gameSlot, CardStyle.RECTANGLE)
    }

    fun loadSlot(gameSlot: String, cardStyle: CardStyle) {
        this.gameSlot = gameSlot
        this.cardStyle = cardStyle
        loadSlotInternal()
    }

    private fun loadSlotInternal() {
        val gameSlotId = gameSlot ?: return
        SlotApi.loadSlots(gameSlotId) { result ->
            result.onSuccess { list ->
                slotList = list
                if (slotList.isNotEmpty()) {
                    renderSlots(slotList)
                } else {
                    Log.d(tagName, "no slot available gameSlot=$gameSlotId")
                    slotListener?.onSlotFailed("No slot available", gameSlot ?: "")
                }
                scheduleReload()
            }.onFailure {
                Log.e(tagName, "loadSlots failed gameSlot=$gameSlotId error=${it.message}")
                slotListener?.onSlotFailed(it.message ?: "Load failed", gameSlot ?: "")
                scheduleReload()
            }
        }
    }

    private fun scheduleReload() {
        handler.removeCallbacks(reloadRunnable)
        if (reloadIntervalMs > 0 && gameSlot != null) {
            handler.postDelayed(reloadRunnable, reloadIntervalMs)
        }
    }

    private fun renderSlots(list: List<GameSlot>) {
        impressionTracker.reset()
        container.removeAllViews()
        val showList = list.take(2)
        val preview = showList.joinToString(separator = " | ") { it.title }
        Log.d(tagName, "loadSlots success gameSlot=${gameSlot.orEmpty()} size=${list.size} preview=$preview")
        // provisional（过期缓存兜底）内容照常渲染但不计曝光；刷新结果回来后会以非 provisional 再渲染一次
        val countExposure = showList.firstOrNull()?.provisional != true

        val gap = dp(12)

        showList.forEachIndexed { index, gs ->
            fun handleSlotClick() {
                if (!ClickGuard.canClick(gs.id.ifEmpty { gs.title })) return
                slotListener?.onSlotClick(gs, gameSlot ?: "")
                TrackingReporter.reportClick(gs, slotId = gameSlot)
                GameLauncher.openGame(context, gs, slotListener)
            }

            val card = createCardView(cardStyle)
            val baseLp = card.layoutParams
            val w = baseLp?.width ?: LayoutParams.WRAP_CONTENT
            val h = baseLp?.height ?: LayoutParams.WRAP_CONTENT
            card.layoutParams = LinearLayout.LayoutParams(w, h).apply {
                if (index > 0) marginStart = gap
            }
            bindCard(card, gs) { handleSlotClick() }
            container.addView(card)
            if (countExposure) impressionTracker.track(gs.id, card)
        }

        for (i in showList.indices) {
            slotListener?.onSlotLoaded(showList[i], gameSlot ?: "")
            slotListener?.onSlotShow(showList[i], gameSlot ?: "")
        }

        Log.i(tagName, "show TwoCards")
        if (countExposure) impressionTracker.schedule()
    }

    private fun createCardView(style: CardStyle): FrameLayout {
        return when (style) {
            CardStyle.SQUARE -> GameAdCardSquare161View(context)
            CardStyle.RECTANGLE -> GameAdCardRectangle161_108View(context)
            CardStyle.VIDEO -> GameAdVideo161View(context)
        }
    }

    private fun bindCard(card: FrameLayout, gs: GameSlot, onClick: () -> Unit) {
        when (card) {
            is GameAdCardSquare161View -> card.bind(gs, onClick)
            is GameAdCardRectangle161_108View -> card.bind(gs, onClick)
            is GameAdVideo161View -> card.bind(gs, onClick)
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (gameSlot != null) {
            scheduleReload()
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        handler.removeCallbacks(reloadRunnable)
        impressionTracker.stop()
        impressionTracker.reset()
        for (i in 0 until container.childCount) {
            (container.getChildAt(i) as? GameAdVideo161View)?.release()
        }
    }

    private fun dp(value: Int): Int {
        return (resources.displayMetrics.density * value).toInt()
    }
}
